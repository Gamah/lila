package lila.mistake
package ui

import play.api.libs.json.{ JsObject, Json }

import lila.ui.*

import ScalatagsTemplate.{ *, given }

final class MistakeUi(helpers: Helpers)(endpoints: AnalyseEndpoints):
  import helpers.{ *, given }

  /* The board. Not idempotent: arriving here hands out a position and spends
   * it, which is why it is no longer what /mistakes serves.
   *
   * The opening explorer is queried by the browser, exactly as retro queries
   * it, so the page carries the endpoint. Positions fetched later reuse the one
   * they booted with: it doesn't change. */
  def board(next: JsObject, window: MistakeWindow)(using Context) =
    val data = next ++ Json.obj(
      "explorerEndpoint" -> endpoints.explorer,
      "window" -> window.key
    )
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

  /* The landing page. Everything here is a read: it says what the deck is,
   * whether there is anything in it yet, and how you have been doing. Starting
   * to solve, asking for analysis and resetting are all links or POSTs away. */
  def home(home: MistakeHome)(using Context) =
    Page(trans.site.shuffledMistakes.txt())
      .css("mistake.home")
      // only loaded where there is an auto-submitting request form to submit
      .js(List(autoRequests(home).option(Esm("mistake.landing")))):
        main(cls := "page page-small mistake-home")(
          div(cls := "box")(
            boxTop(h1(trans.site.shuffledMistakes())),
            div(cls := "box-pad")(
              whatThisIs,
              home.state match
                case MistakeHome.State.Cold => cold(home)
                case MistakeHome.State.Warming => warming(home)
                case MistakeHome.State.Ready => ready(home)
            )
          ),
          (home.stats.total > 0).option(statsBox(home.stats)),
          windowBox(home)
        )

  /* Arriving with nothing analysed asks for analysis by itself, because a page
   * that explains the exercise and then makes you press a button before it can
   * start is a worse first visit. It is still a POST, and still the same one
   * the button submits. */
  private def autoRequests(home: MistakeHome) =
    home.state == MistakeHome.State.Cold && home.canRequest && home.nbUnanalysed > 0

  private def whatThisIs(using Context) = frag(
    p(
      "One position at a time, drawn at random from games you played ",
      strong("months ago"),
      ": the board, the move you played drawn on it, and nothing else. ",
      "No opponent, no date, no result, and no way back to the game until you have answered."
    ),
    p(
      "It is the same exercise as ",
      strong("learn from your mistakes"),
      " at the end of a game, except that you meet the position cold. ",
      "Recent games are left out on purpose - you still remember the move."
    )
  )

  private def cold(home: MistakeHome)(using Context) = frag(
    p(cls := "mistake-home__empty")(
      if home.nbUnanalysed > 0
      then
        frag(
          "None of your games from ",
          strong(home.window.name),
          " have been analysed yet, so there is nothing to draw from. ",
          s"There ${if home.nbUnanalysed == 1 then "is" else "are"} ",
          strong(home.nbUnanalysed),
          s" game${if home.nbUnanalysed == 1 then "" else "s"} we can hand to the engine."
        )
      else
        frag(
          "You have no finished games from ",
          strong(home.window.name),
          ". Play some chess, or widen the window below."
        )
    ),
    (home.nbUnanalysed > 0).option(requestForm(home))
  )

  /* Tutor shows a carousel of the games it is analysing while you wait. We
   * cannot: those are exactly the games we withhold, and showing them would
   * hand over every position before it was asked. Counts only. */
  private def warming(home: MistakeHome)(using Context) =
    home.warming.map: w =>
      frag(
        p(cls := "mistake-home__warming")(
          "Your games are with the engine now. ",
          strong(w.nbDone),
          " of ",
          strong(w.nbTotal),
          " analysed",
          if home.nbAnalysed > 0 then " - there is already something to solve." else "."
        ),
        div(cls := "mistake-home__progress")(
          div(cls := "mistake-home__progress__bar", style := s"width:${w.percent}%")
        ),
        p(cls := "mistake-home__hint")(
          "Analysis takes a few minutes per game and runs whether or not this page is open. ",
          "Started ", momentFromNow(w.since), "."
        ),
        (home.nbAnalysed > 0).option(playButton(home))
      )

  private def ready(home: MistakeHome)(using Context) = frag(
    p(cls := "mistake-home__ready")(
      strong(home.nbAnalysed),
      s" of your games from ${home.window.name} have engine analysis. ",
      "Every mistake in them that you have not answered yet is still in the deck."
    ),
    playButton(home),
    (home.nbUnanalysed > 0 && home.canRequest).option(requestForm(home, secondary = true))
  )

  private def playButton(home: MistakeHome)(using Context) =
    a(
      cls := "button button-fat button-no-upper mistake-home__play",
      href := routes.Mistake.board(home.window.key.some)
    )("Show me a position")

  /* A POST, always. A page load has never had the right to spend fishnet time,
   * and the landing existing at all is what makes that possible to honour. */
  private def requestForm(home: MistakeHome, secondary: Boolean = false)(using Context) =
    if !home.canRequest then
      p(cls := "mistake-home__hint")(
        "You have already asked for analysis in the last hour. Come back shortly."
      )
    else
      postForm(
        cls := List(
          "mistake-home__request" -> true,
          // the landing esm submits this one on arrival; without JS it is a button
          "mistake-home__request--auto" -> !secondary
        ),
        action := routes.Mistake.analyse(home.window.key.some)
      )(
        submitButton(
          cls := List(
            "button button-no-upper" -> true,
            "button-fat" -> !secondary,
            "button-empty" -> secondary
          )
        )(s"Analyse ${home.nbToRequest} of my games"),
        p(cls := "mistake-home__hint")("Once an hour, up to a few dozen games at a time.")
      )

  private def statsBox(stats: MistakeStats)(using Context) =
    div(cls := "box box-pad mistake-home__stats")(
      h2("How you do when you meet them cold"),
      /* The number this feature knows and tutor doesn't. Tutor says you blunder
       * in the middlegame; this says whether you can still find the refutation
       * with the game taken away. */
      div(cls := "mistake-home__hit-rate")(
        stats.hitRate.map: rate =>
          frag(
            strong(cls := "mistake-home__big")(s"$rate%"),
            span("found something better")
          )
      ),
      div(cls := "mistake-home__counts")(
        countTile(stats.nb(MistakeResult.win), "solved", "win"),
        countTile(stats.nb(MistakeResult.fail), "gave up", "fail"),
        countTile(stats.nb(MistakeResult.skip), "skipped", "skip")
      ),
      stats.recentHitRate.map: rate =>
        p(cls := "mistake-home__hint")(
          s"Last ${stats.recent.size}: $rate% found something better."
        ),
      p(cls := "mistake-home__hint")(
        "Which openings and phases you go wrong in is ",
        a(href := routes.Tutor.home())("Lichess Tutor"),
        "'s question, not this one's."
      )
    )

  private def countTile(nb: Int, label: String, key: String) =
    div(cls := s"mistake-home__tile mistake-home__tile--$key")(
      strong(nb),
      span(label)
    )

  private def windowBox(home: MistakeHome)(using Context) =
    div(cls := "box box-pad mistake-home__window")(
      h2("How far back to draw from"),
      p(cls := "mistake-home__hint")(
        "Anything more recent than the end of the window is left out on purpose: ",
        "a game from last week is still in your memory."
      ),
      st.form(method := "get", action := routes.Mistake.home(none).url)(
        st.select(st.name := "window")(
          MistakeWindow.choices.map: w =>
            st.option(
              st.value := w.key,
              (w == home.window).option(st.selected)
            )(w.name.capitalize)
        ),
        submitButton(cls := "button button-empty")("Change")
      ),
      (home.stats.total > 0).option(
        postForm(cls := "mistake-home__reset", action := routes.Mistake.reset(home.window.key.some))(
          submitButton(cls := "button button-red button-empty yes-no-confirm")(
            s"Put all ${home.stats.total} answered positions back"
          )
        )
      )
    )
