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
  case class Dealt(key: Mistake.Key, color: Color):
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

object MistakeWindow:
  val default = MistakeWindow(sinceMonths = 3, untilMonths = 1)
