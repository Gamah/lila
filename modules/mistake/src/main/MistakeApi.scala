package lila.mistake

import chess.{ Color, Ply }
import scalalib.ThreadLocalRandom

import lila.analyse.AnalysisRepo
import lila.game.GameRepo
import lila.tree.{ ExportOptions, Root, TreeBuilder }

final class MistakeApi(
    gameRepo: GameRepo,
    analysisRepo: AnalysisRepo,
    solveRepo: MistakeSolveRepo,
    dealStore: MistakeDealStore,
    fishnet: MistakeFishnet
)(using Executor):

  import MistakeApi.*

  def deal(userId: UserId, window: MistakeWindow = MistakeWindow.default): Fu[Deal] = for
    ids <- gameRepo.analysedIdsBetween(userId, window.since, window.until, maxGamesToConsider)
    // shuffled, not newest first: the whole point is not knowing what's coming
    shuffled = ThreadLocalRandom.shuffle[GameId, List[GameId]](ids)
    solved <- solveRepo.solvedIn(userId, shuffled)
    mistake <- pick(userId, shuffled.take(maxGamesToVisit.value), solved)
    _ = mistake.foreach(m => dealStore.put(userId, Mistake.Dealt(m.key, m.color)))
    nbSolved <- solveRepo.count(userId)
    queued <- fishnet.ensureSomeAnalysis(userId, window)
  yield Deal(mistake, nbGames = ids.size, nbSolved = nbSolved, nbQueued = queued)

  /* Answers the position the user was last dealt, and only then hands back the
   * game it came from. Returns none if we've forgotten the deal (stale tab). */
  def record(userId: UserId, result: MistakeResult): Fu[Option[Mistake.Dealt]] =
    dealStore.take(userId) match
      case None => fuccess(none)
      case Some(dealt) => solveRepo.record(userId, dealt.key, result).inject(dealt.some)

  def clear(userId: UserId): Funit = solveRepo.clear(userId)

  /* Walks the shuffled games one at a time and stops at the first that still
   * has something to offer. Bounded, so a user whose whole deck is spent
   * doesn't cost us a hundred round trips. */
  private def pick(userId: UserId, ids: List[GameId], solved: Set[Mistake.Key]): Fu[Option[Mistake]] =
    ids match
      case Nil => fuccess(none)
      case id :: rest =>
        candidatesOf(userId, id, solved).flatMap: candidates =>
          if candidates.isEmpty then pick(userId, rest, solved)
          else fuccess(ThreadLocalRandom.oneOf(candidates.toVector))

  private def candidatesOf(
      userId: UserId,
      gameId: GameId,
      solved: Set[Mistake.Key]
  ): Fu[List[Mistake]] =
    gameRepo.game(gameId).flatMap:
      case None => fuccess(Nil)
      case Some(game) =>
        game.player(userId).map(_.color) match
          case None => fuccess(Nil)
          case Some(color) =>
            analysisRepo.byGame(game).flatMap:
              case None => fuccess(Nil)
              case Some(analysis) =>
                val plies = analysis.advices.collect:
                  case advice if advice.color == color && !solved(Mistake.Key(gameId, advice.ply)) =>
                    advice.ply
                if plies.isEmpty then fuccess(Nil)
                else
                  gameRepo
                    .initialFen(game)
                    .map: initialFen =>
                      val root = TreeBuilder(
                        game = game,
                        analysis = analysis.some,
                        initialFen = initialFen | game.variant.initialFen,
                        withFlags = ExportOptions.default,
                        logChessError = logger.warn
                      )
                      plies.flatMap(extract(gameId, color, root, _))

  /* The analyse tree already holds everything we need, and holds it in exactly
   * the shape retro reads: the engine's line hangs off the position BEFORE the
   * mistake as a `comp` child (see TreeBuilder.withAnalysisChild). So rather
   * than replaying the game ourselves, build the tree and pull one node out. */
  private def extract(gameId: GameId, color: Color, root: Root, ply: Ply): Option[Mistake] =
    val nodes = root.mainlineNodeList
    val faultIndex = (ply - root.ply).value
    for
      parent <- nodes.lift(faultIndex - 1)
      fault <- nodes.lift(faultIndex)
      move <- fault.moveOption
      // retro only offers a position that has a computer line to compare against
      solution <- parent.children.toList.find(_.comp)
    yield Mistake(
      key = Mistake.Key(gameId, ply),
      color = color,
      fen = parent.fen,
      played = Mistake.Played(move.uci, move.san, fault.eval),
      prevEval = parent.eval,
      solution = solution.mainline.map(_.move.san)
    )

object MistakeApi:

  private val maxGamesToConsider = Max(200)
  private val maxGamesToVisit = Max(20)

  case class Deal(mistake: Option[Mistake], nbGames: Int, nbSolved: Int, nbQueued: Int)
