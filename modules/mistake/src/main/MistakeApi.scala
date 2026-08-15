package lila.mistake

import chess.{ Color, Division }
import scalalib.ThreadLocalRandom
import scalalib.paginator.Paginator

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
    // the same bounded pool `next` draws on, so the count below is about the
    // games the board can actually reach rather than about the whole window
    ids <- gameRepo.analysedIdsBetween(userId, window.since, window.until, maxGamesToConsider)
    nbAnswered <- solveRepo.countIn(userId, ids)
    stats <- solveRepo.stats(userId)
    request <- fishnet.lastRequest(userId)
    warming <- fishnet.warming(request)
  yield MistakeHome(
    window = window,
    nbAnalysed = nbAnalysed,
    nbUnanalysed = nbUnanalysed,
    nbAnswered = nbAnswered,
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
    _ = mistake.foreach(m => currentStore.put(userId, Mistake.Current(m.key, m.color, m.fen.board)))
  yield mistake

  def requestAnalysis(userId: UserId, window: MistakeWindow): Fu[Option[Int]] =
    fishnet.request(userId, window)

  /* Answers the position the user was last shown. Returns none if we've
   * forgotten it (stale tab), in which case nothing is recorded. */
  def record(userId: UserId, result: MistakeResult): Fu[Option[Mistake.Current]] =
    currentStore.take(userId) match
      case None => fuccess(none)
      case Some(current) =>
        solveRepo.record(userId, current.key, result, current.solvePosition).inject(current.some)

  def answered(userId: UserId, filter: MistakeFilter, page: Int): Fu[Paginator[MistakeSolve]] =
    solveRepo.list(userId, filter, page)

  def stats(userId: UserId): Fu[MistakeStats] = solveRepo.stats(userId)

  /* Puts answered positions back. A window scope resolves to the same bounded
   * pool the board draws on, so "in this window" means the same set of games
   * there and here. */
  def putBack(
      userId: UserId,
      result: Option[MistakeResult],
      window: Option[MistakeWindow]
  ): Fu[Int] =
    val ids: Fu[Option[List[GameId]]] = window match
      case None => fuccess(none)
      case Some(w) =>
        gameRepo.analysedIdsBetween(userId, w.since, w.until, maxGamesToConsider).map(some)
    ids.flatMap(solveRepo.putBack(userId, result, _))

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
                      .filterNot((_, _, fault) => solved(Mistake.Key(gameId, fault.ply)))
                      .flatMap(extract(gameId, color, division, _, _, _))

  /* Retro's own selector, ported: `evalSwings` in ui/analyse/src/nodeFinder.ts.
   *
   * Not `analysis.advices`, which casts a wider net - it also has Inaccuracy,
   * at half this swing, and a broader set of mate sequences. The queue holds the
   * positions retro offers, so it has to select them the way retro does.
   *
   * Three nodes wide rather than two: the board plays the move that led into
   * the position before asking about it, so we also need where that move was
   * played from. `mainlineNodeList` starts at the root, so the first pair has
   * nothing before it.
   */
  private def swings(root: Root, color: Color): List[(Option[Node], Node, Node)] =
    val nodes = root.mainlineNodeList
    (none[Node] :: nodes.map(_.some))
      .lazyZip(nodes)
      .lazyZip(nodes.drop(1))
      .toList
      .filter: (_, prev, fault) =>
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
      before: Option[Node],
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
      intro = for
        from <- before
        into <- parent.moveOption
      yield Mistake.Intro(from.fen, into.uci),
      played = Mistake.Played(move.uci, move.san),
      prevEval = parent.eval,
      solution = solution.mainline.map(_.move.san),
      divisionMiddle = division.middle
    )

object MistakeApi:

  private val maxGamesToConsider = Max(200)
  private val maxGamesToVisit = Max(20)
