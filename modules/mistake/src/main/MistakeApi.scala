package lila.mistake

import chess.{ Color, Division }
import scalalib.ThreadLocalRandom

import lila.analyse.AnalysisRepo
import lila.game.{ Divider, GameRepo }
import lila.tree.{ ExportOptions, Node, Root, TreeBuilder }

final class MistakeApi(
    gameRepo: GameRepo,
    analysisRepo: AnalysisRepo,
    divider: Divider,
    solveRepo: MistakeSolveRepo,
    currentStore: MistakeCurrentStore,
    fishnet: MistakeFishnet
)(using Executor):

  import MistakeApi.*

  /* Everything the landing page shows, and nothing it changes. Opening
   * /mistakes must not spend a position or a second of fishnet, which is
   * exactly what the old combined call did on every load. */
  def home(userId: UserId, window: MistakeWindow): Fu[MistakeHome] = for
    nbAnalysed <- gameRepo.countAnalysedBetween(userId, window.since, window.until)
    nbUnanalysed <- gameRepo.countUnanalysedBetween(userId, window.since, window.until)
    stats <- solveRepo.stats(userId)
    request <- fishnet.lastRequest(userId)
    warming <- fishnet.warming(request)
  yield MistakeHome(
    window = window,
    nbAnalysed = nbAnalysed,
    nbUnanalysed = nbUnanalysed,
    maxPerRequest = fishnet.maxPerRequest,
    stats = stats,
    warming = warming,
    canRequest = request.forall(_.canRequestAgain)
  )

  /* Hands out the next position and remembers it. Unlike `home` this is not
   * idempotent - it consumes, which is why it lives behind its own request. */
  def next(userId: UserId, window: MistakeWindow): Fu[Option[Mistake]] = for
    ids <- gameRepo.analysedIdsBetween(userId, window.since, window.until, maxGamesToConsider)
    // shuffled, not newest first: the whole point is not knowing what's coming
    shuffled = ThreadLocalRandom.shuffle[GameId, List[GameId]](ids)
    solved <- solveRepo.solvedIn(userId, shuffled)
    mistake <- pick(userId, shuffled.take(maxGamesToVisit.value), solved)
    _ = mistake.foreach(m => currentStore.put(userId, Mistake.Current(m.key, m.color)))
  yield mistake

  def requestAnalysis(userId: UserId, window: MistakeWindow): Fu[Option[Int]] =
    fishnet.request(userId, window)

  /* Answers the position the user was last shown, and only then hands back the
   * game it came from. Returns none if we've forgotten it (stale tab). */
  def record(userId: UserId, result: MistakeResult): Fu[Option[Mistake.Current]] =
    currentStore.take(userId) match
      case None => fuccess(none)
      case Some(current) => solveRepo.record(userId, current.key, result).inject(current.some)

  def clear(userId: UserId): Funit = solveRepo.clear(userId)

  /* Walks the shuffled games one at a time and stops at the first that still
   * has something to offer. Bounded, so a user with an empty queue
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
                    val division = divider(game, initialFen)
                    swings(root, color)
                      .filterNot((_, fault) => solved(Mistake.Key(gameId, fault.ply)))
                      .flatMap((prev, fault) => extract(gameId, color, division, prev, fault))

  /* Retro's own selector, ported: `evalSwings` in ui/analyse/src/nodeFinder.ts.
   *
   * Not `analysis.advices`, which casts a wider net - it also has Inaccuracy,
   * at half this swing, and a broader set of mate sequences. The queue holds the
   * positions retro offers, so it has to select them the way retro does.
   */
  private def swings(root: Root, color: Color): List[(Node, Node)] =
    val nodes = root.mainlineNodeList
    nodes
      .zip(nodes.drop(1))
      .filter: (prev, fault) =>
        (fault.ply - 1).turn == color && isSwing(prev, fault)

  private def isSwing(prev: Node, fault: Node): Boolean = (prev.eval, fault.eval) match
    case (Some(p), Some(f)) =>
      hasCompChild(prev) && (
        WinningChances.povDiff(chess.White, p, f).exists(_.abs > 0.1) ||
          (p.mate.exists(_.value.abs <= 3) && f.mate.isEmpty)
      )
    case _ => false

  // retro only offers a position that has a computer line to compare against
  private def hasCompChild(node: Node): Boolean = node.children.toList.exists(_.comp)

  /* The engine's line hangs off the position BEFORE the mistake as a `comp`
   * child (see TreeBuilder.withAnalysisChild), which is what retro reads too.
   * So rather than replaying the game ourselves, we build the tree and pull the
   * two nodes out of it. */
  private def extract(
      gameId: GameId,
      color: Color,
      division: Division,
      parent: Node,
      fault: Node
  ): Option[Mistake] =
    for
      move <- fault.moveOption
      solution <- parent.children.toList.find(_.comp)
    yield Mistake(
      key = Mistake.Key(gameId, fault.ply),
      color = color,
      fen = parent.fen,
      played = Mistake.Played(move.uci, move.san),
      prevEval = parent.eval,
      solution = solution.mainline.map(_.move.san),
      divisionMiddle = division.middle
    )

object MistakeApi:

  private val maxGamesToConsider = Max(200)
  private val maxGamesToVisit = Max(20)
