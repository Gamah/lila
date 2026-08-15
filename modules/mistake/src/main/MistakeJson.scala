package lila.mistake

import play.api.libs.json.*

import lila.common.Json.given
import lila.core.pref.Pref
import lila.tree.evals.jsonWrites

final class MistakeJson:

  import MistakeJson.given

  /* Note what is NOT in here: game id, opponent, date, result.
   * The eval of the played move is, because the client needs it as the
   * baseline to judge against - it just isn't rendered. */
  def deal(deal: MistakeApi.Deal, pref: Option[Pref] = none): JsObject =
    Json
      .obj(
        "nbGames" -> deal.nbGames,
        "nbSolved" -> deal.nbSolved
      )
      .add("mistake" -> deal.mistake.map(position))
      .add("nbQueued" -> deal.nbQueued.some.filter(_ > 0))
      .add("pref" -> pref)

  def position(m: Mistake): JsObject =
    Json
      .obj(
        "fen" -> m.fen,
        "color" -> m.color.name,
        "played" -> Json
          .obj(
            "uci" -> m.played.uci.uci,
            "san" -> m.played.san
          )
          .add("eval" -> m.played.eval),
        "solution" -> m.solution
      )
      .add("prevEval" -> m.prevEval)

object MistakeJson:

  // same board prefs the puzzle and storm boards read
  given Writes[Pref] = Writes: p =>
    Json.obj(
      "coords" -> p.coords,
      "rookCastle" -> p.rookCastle,
      "destination" -> p.destination,
      "moveEvent" -> p.moveEvent,
      "highlight" -> p.highlight,
      "is3d" -> p.is3d,
      "animation" -> p.animationMillisForSpeedPuzzles
    )
