package lila.mistake
package ui

import play.api.libs.json.{ JsObject, Json }

import lila.ui.*

import ScalatagsTemplate.*

final class MistakeUi(helpers: Helpers)(endpoints: AnalyseEndpoints):
  import helpers.{ *, given }

  /* The opening explorer is queried by the browser, exactly as retro queries
   * it, so the page carries the endpoint. Deals fetched later reuse the one
   * they booted with: it doesn't change. */
  def home(deal: JsObject)(using Context) =
    val data = deal ++ Json.obj("explorerEndpoint" -> endpoints.explorer)
    Page(trans.site.shuffledMistakes.txt())
      .css("mistake")
      // study for goodMove, the one string retro borrows from outside site
      .i18n(_.study)
      .js(PageModule("mistake", data))
      .flag(_.zen)
      .flag(_.zoom):
        main(cls := "mistake mistake-app")(
          div(cls := "mistake__board main-board"),
          div(cls := "mistake__side")
        )
