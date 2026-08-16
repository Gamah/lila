package lila.mistake

import chess.{ Color, Ply }
import chess.format.{ Fen, Uci }
import chess.format.pgn.SanStr

import lila.tree.Eval

/* One position in the queue: a move this player got wrong, torn out of its game.
 *
 * `key` says which game and which ply, and the client gets it. Nothing here is
 * a secret: the shuffle is what makes this exercise, not ignorance of where you
 * are, and knowing the game does not hand you the move.
 */
case class Mistake(
    key: Mistake.Key,
    color: Color,
    fen: Fen.Full, // the position BEFORE the mistake was played
    // the move that led into it, played on arrival the way a puzzle plays its
    // first move. None when the mistake is the very first move of the game.
    intro: Option[Mistake.Intro],
    played: Mistake.Played,
    prevEval: Option[Eval], // eval of the position before, ie of the best move
    solution: List[SanStr], // engine line from `fen`
    // where the opening ends, so the client knows whether to ask the explorer
    divisionMiddle: Option[Ply]
):
  def gameUrl = Mistake.gameUrl(key, color)

object Mistake:

  case class Key(gameId: GameId, ply: Ply)

  case class Played(uci: Uci, san: SanStr)

  // where the board starts, and the move it plays to get to `fen`
  case class Intro(fen: Fen.Full, uci: Uci)

  // deep link into the analysis of the game a position came from
  def gameUrl(key: Key, color: Color) = s"/${key.gameId}/${color.name}#${key.ply}"

  /* Which position a user has in flight, so `record` knows what it is
   * answering - and carries the board with it, because the answer is where the
   * position gets written down and by then it is gone. */
  case class Current(key: Mistake.Key, color: Color, board: Fen.Board):
    def gameUrl = Mistake.gameUrl(key, color)
    def solvePosition = MistakeSolve.Position(color, board)

/* Mimics retro: solving it, failing it and moving on, and skipping it all
 * retire the position. We keep which of the three it was anyway. */
enum MistakeResult:
  case win, fail, skip

object MistakeResult:
  def apply(str: String): Option[MistakeResult] = values.find(_.toString == str)

/* One answered position, as `mistake_solve` remembers it.
 *
 * A position is derived, never stored: it is a game and a ply run through the
 * selector, and once it has been answered nothing keeps it. So the board and
 * the colour are written onto the answer itself, which is the only record of it
 * there will ever be. Rows written before that have neither, and are shown as
 * a date and a result with nothing to look at. */
case class MistakeSolve(
    key: Mistake.Key,
    result: MistakeResult,
    date: Instant,
    position: Option[MistakeSolve.Position]
):
  def gameUrl = position.map(p => Mistake.gameUrl(key, p.color))

object MistakeSolve:
  case class Position(color: Color, board: Fen.Board)

/* Which answers the list is showing. `all` is not a fourth result: it is the
 * absence of the filter, and the URL says nothing when it applies. */
enum MistakeFilter:
  case all, win, fail, skip

  def result: Option[MistakeResult] = MistakeResult(toString)
  def key: Option[String] = (this != MistakeFilter.all).option(toString)

object MistakeFilter:
  def orAll(key: Option[String]): MistakeFilter =
    key.flatMap(k => values.find(_.toString == k)) | all

/* How much of the queue a "put it back" puts back. The window is the one the
 * user is looking at on the page, so the narrow form says what it will do
 * without them having to hold the two controls in their head at once. */
enum MistakeScope:
  case window, allTime

object MistakeScope:
  def orAllTime(key: Option[String]): MistakeScope =
    key.flatMap(k => values.find(_.toString == k)) | allTime

/* How far back we draw from. Anything more recent than `until` is skipped by
 * default: a game from last week is still in your memory, and remembering the
 * move is exactly what this exercise is trying to avoid. It is a default and
 * not a rule - see `untilChoices`. */
case class MistakeWindow(sinceMonths: Int, untilMonths: Int):
  def since = nowInstant.minusMonths(sinceMonths)
  def until = nowInstant.minusMonths(untilMonths)
  // what the URL carries, and the only form of it the client ever sees
  def key = s"$sinceMonths-$untilMonths"

object MistakeWindow:
  val default = MistakeWindow(sinceMonths = 3, untilMonths = 1)

  /* Set by two selects, so the whitelist is per half rather than per pair. Both
   * halves stay bounded lists rather than free numbers: every widening of
   * `since` costs fishnet, and a window nobody picked is a window we never have
   * analysis for. Narrowing `until` costs nothing - it is a subset of a window
   * we already pay for - which is why it has more choices than `since`.
   *
   * 0 is among them, and the default is not. Coldness is what the feature is
   * for, so it is what you get without asking - but it is a default and not an
   * invariant, because a heavy player who empties their queue has nowhere left
   * to draw from, and telling them to go and play more games is only an answer
   * if the games they then play can reach the queue. That is what the board's
   * empty state says, so 0 has to be reachable for it to be true. Gamah/lila#5. */
  val sinceChoices: List[Int] = List(3, 6, 12)
  val untilChoices: List[Int] = List(0, 1, 2, 3)

  // what byKey's flat list of pairs used to give us for free
  def valid(w: MistakeWindow): Boolean =
    sinceChoices.contains(w.sinceMonths) &&
      untilChoices.contains(w.untilMonths) &&
      w.sinceMonths > w.untilMonths

  def byKey(key: String): Option[MistakeWindow] = key.split('-') match
    case Array(since, until) =>
      for
        s <- since.toIntOption
        u <- until.toIntOption
        w = MistakeWindow(s, u)
        if valid(w)
      yield w
    case _ => none

  def orDefault(key: Option[String]): MistakeWindow = key.flatMap(byKey) | default

/* Everything the landing page knows, and nothing it doesn't. There is no
 * "positions remaining": finding that out means deriving candidates for every
 * game in the window, which is what the board does one game at a time.
 *
 * `nbAnswered` is the half of that we can afford: what you have already taken
 * out of this window, with no claim about what is left in it. */
case class MistakeHome(
    window: MistakeWindow,
    nbAnalysed: Int, // games in the window we can draw from
    nbUnanalysed: Int, // games in the window we could still send to fishnet
    nbAnswered: Int, // positions already answered from the games the board draws on
    maxPerRequest: Int, // how many of those one request would actually take
    stats: MistakeStats,
    warming: Option[MistakeWarming],
    canRequest: Boolean // the analysis request is not in cooldown
):
  // what the button can honestly promise
  def nbToRequest = nbUnanalysed.atMost(maxPerRequest)

  def state: MistakeHome.State =
    if warming.isDefined then MistakeHome.State.Warming
    else if nbAnalysed > 0 then MistakeHome.State.Ready
    else MistakeHome.State.Cold

object MistakeHome:
  enum State:
    case Cold, Warming, Ready

/* The answered list. The window is not what the list is filtered by - history
 * is every position you ever answered - it is carried so the page can offer to
 * put back only what that window covers, and name it while doing so. */
case class MistakeAnswered(
    window: MistakeWindow,
    filter: MistakeFilter,
    pager: scalalib.paginator.Paginator[MistakeSolve],
    stats: MistakeStats
)

/* Games this user handed to fishnet that have not come back yet. */
case class MistakeWarming(nbDone: Int, nbTotal: Int)

/* Read straight off `mistake_solve`, which stores a result and a date and
 * nothing else. Solved-first-try is the number this feature exists for: not
 * "you blunder in the middlegame" but "shown it cold, you still can't find the
 * refutation". */
case class MistakeStats(byResult: Map[MistakeResult, Int], recent: List[MistakeResult]):
  def nb(r: MistakeResult) = byResult.getOrElse(r, 0)
  def nbRecent(r: MistakeResult) = recent.count(_ == r)
  def total = byResult.values.sum

object MistakeStats:
  val recentSize = 20
