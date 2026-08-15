package lila.mistake
package ui

import play.api.libs.json.JsObject

import lila.ui.*

import ScalatagsTemplate.*

final class MistakeUi(helpers: Helpers):
  import helpers.{ *, given }

  def home(data: JsObject)(using Context) =
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
