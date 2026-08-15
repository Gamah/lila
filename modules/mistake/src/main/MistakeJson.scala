package lila.mistake

import play.api.libs.json.*

import lila.common.Json.given
import lila.core.pref.Pref
import lila.tree.evals.jsonWrites

final class MistakeJson:

  import MistakeJson.given

  /* `prevEval` is here because the client judges against it exactly as retro
   * does - it just isn't rendered.
   *
   * The counts aren't here. They belong to the landing page, which can render
   * them without consuming a position to do it. */
  def next(mistake: Option[Mistake], pref: Option[Pref] = none): JsObject =
    Json
      .obj()
      .add("mistake" -> mistake.map(position))
      .add("pref" -> pref)

  def position(m: Mistake): JsObject =
    Json
      .obj(
        "fen" -> m.fen,
        "color" -> m.color.name,
        "played" -> Json.obj(
          "uci" -> m.played.uci.uci,
          "san" -> m.played.san
        ),
        "solution" -> m.solution,
        // where it came from, given up front: the shuffle is the exercise, not
        // hiding the game
        "game" -> m.gameUrl
      )
      .add("intro" -> m.intro.map(i => Json.obj("fen" -> i.fen, "uci" -> i.uci.uci)))
      .add("prevEval" -> m.prevEval)
      .add("divisionMiddle" -> m.divisionMiddle)

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
