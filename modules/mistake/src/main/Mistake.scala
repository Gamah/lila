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
  def name =
    if untilMonths == 0 then s"the last $sinceMonths months"
    else s"$untilMonths to $sinceMonths months ago"

object MistakeWindow:
  val default = MistakeWindow(sinceMonths = 3, untilMonths = 1)

  /* A fixed list rather than free numbers. Every widening costs fishnet, and a
   * window nobody picked is a window we never have analysis for. */
  val choices: List[MistakeWindow] = List(
    default,
    MistakeWindow(sinceMonths = 6, untilMonths = 1),
    MistakeWindow(sinceMonths = 12, untilMonths = 1),
    MistakeWindow(sinceMonths = 12, untilMonths = 0)
  )

  def byKey(key: String): Option[MistakeWindow] = choices.find(_.key == key)

  def orDefault(key: Option[String]): MistakeWindow = key.flatMap(byKey) | default

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
case class MistakeWarming(nbDone: Int, nbTotal: Int, since: Instant):
  def percent = if nbTotal > 0 then 100 * nbDone / nbTotal else 0

/* Read straight off `mistake_solve`, which stores a result and a date and
 * nothing else. Solved-first-try is the number this feature knows and tutor
 * doesn't: not "you blunder in the middlegame" but "shown it cold, you still
 * can't find the refutation". */
case class MistakeStats(byResult: Map[MistakeResult, Int], recent: List[MistakeResult]):
  def nb(r: MistakeResult) = byResult.getOrElse(r, 0)
  def total = byResult.values.sum
  def hitRate: Option[Int] = (total > 0).option(100 * nb(MistakeResult.win) / total)
  def recentHitRate: Option[Int] =
    recent.nonEmpty.option(100 * recent.count(_ == MistakeResult.win) / recent.size)

object MistakeStats:
  val empty = MistakeStats(Map.empty, Nil)
  val recentSize = 20
