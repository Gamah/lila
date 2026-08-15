package lila.mistake

import chess.{ Color, Ply }
import chess.format.{ Fen, Uci }
import chess.format.pgn.SanStr

import lila.tree.Eval

/* One card of the deck: a move this player got wrong, torn out of its game.
 *
 * Everything that would say *where* it comes from - opponent, date, game id -
 * is deliberately absent. `key` is the only link back, it stays server side
 * until the position has been answered.
 */
case class Mistake(
    key: Mistake.Key,
    color: Color,
    fen: Fen.Full, // the position BEFORE the mistake was played
    played: Mistake.Played,
    prevEval: Option[Eval], // eval of the position before, ie of the best move
    solution: List[SanStr], // engine line from `fen`
    // where the opening ends, so the client knows whether to ask the explorer
    divisionMiddle: Option[Ply]
)

object Mistake:

  case class Key(gameId: GameId, ply: Ply)

  case class Played(uci: Uci, san: SanStr)

  // what the server remembers about the position a user is currently looking at
  case class Current(key: Mistake.Key, color: Color):
    // deep link into the analysis of the game it came from
    def gameUrl = s"/${key.gameId}/${color.name}#${key.ply}"

/* Mimics retro: solving it, failing it and moving on, and skipping it all
 * retire the position. We keep which of the three it was anyway. */
enum MistakeResult:
  case win, fail, skip

object MistakeResult:
  def apply(str: String): Option[MistakeResult] = values.find(_.toString == str)

/* How far back we draw from. Anything more recent than `until` is skipped on
 * purpose: a game from last week is still in your memory, and remembering the
 * move is exactly what this exercise is trying to avoid. */
case class MistakeWindow(sinceMonths: Int, untilMonths: Int):
  def since = nowInstant.minusMonths(sinceMonths)
  def until = nowInstant.minusMonths(untilMonths)
  // what the URL carries, and the only form of it the client ever sees
  def key = s"$sinceMonths-$untilMonths"
  def name = s"$untilMonths to $sinceMonths months ago"
  // the two halves, named for the two selects that set them
  def fromName = s"from ${MistakeWindow.months(sinceMonths)} ago"
  def toName = s"to ${MistakeWindow.months(untilMonths)} ago"

object MistakeWindow:
  val default = MistakeWindow(sinceMonths = 3, untilMonths = 1)

  /* Set by two selects, so the whitelist is per half rather than per pair. Both
   * halves stay bounded lists rather than free numbers: every widening of
   * `since` costs fishnet, and a window nobody picked is a window we never have
   * analysis for. Narrowing `until` costs nothing - it is a subset of a window
   * we already pay for - which is why it has more choices than `since`.
   *
   * 0 is not among them. `until` is not decoration: it is what makes the
   * position cold, and a control that can switch it off has broken the feature. */
  val sinceChoices: List[Int] = List(3, 6, 12)
  val untilChoices: List[Int] = List(1, 2, 3)

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

  private def months(n: Int) = if n == 1 then "1 month" else s"$n months"

/* Everything the landing page knows, and nothing it doesn't. There is no
 * "positions remaining": finding that out means deriving candidates for every
 * game in the window, which is what the board does one game at a time. */
case class MistakeHome(
    window: MistakeWindow,
    nbAnalysed: Int, // games in the window we can draw from
    nbUnanalysed: Int, // games in the window we could still send to fishnet
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

/* Games this user handed to fishnet that have not come back yet. */
case class MistakeWarming(nbDone: Int, nbTotal: Int)

/* Read straight off `mistake_solve`, which stores a result and a date and
 * nothing else. Solved-first-try is the number this feature knows and tutor
 * doesn't: not "you blunder in the middlegame" but "shown it cold, you still
 * can't find the refutation". */
case class MistakeStats(byResult: Map[MistakeResult, Int], recent: List[MistakeResult]):
  def nb(r: MistakeResult) = byResult.getOrElse(r, 0)
  def nbRecent(r: MistakeResult) = recent.count(_ == r)
  def total = byResult.values.sum

object MistakeStats:
  val recentSize = 20
