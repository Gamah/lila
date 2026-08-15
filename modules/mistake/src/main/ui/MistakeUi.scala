package lila.mistake
package ui

import play.api.libs.json.{ JsObject, Json }
import scalalib.paginator.Paginator

import lila.ui.*

import ScalatagsTemplate.{ *, given }

/* "Blindspot" is the name on the page. `mistake` stays the token everywhere in
 * the code and `/mistakes` stays the path - see modules/mistake/README.md. */
final class MistakeUi(helpers: Helpers)(endpoints: AnalyseEndpoints):
  import helpers.{ *, given }

  private val name = "Blindspot"

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
    Page(name)
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

  /* The landing page. Everything here is a read: it says what the exercise is,
   * whether there is anything for you yet, and how you have been doing.
   * Starting, asking for analysis and resetting are all a link or a POST away. */
  def home(home: MistakeHome)(using Context) =
    Page(name)
      .css("mistake.home")
      // only loaded where there is an auto-submitting request form to submit
      .js(List(autoRequests(home).option(Esm("mistake.landing")))):
        main(cls := "box box-pad page-small blindspot")(
          boxTop(
            div(cls := "box__top__title")(
              h1(cls := "text", dataIcon := Icon.ChasingArrows)(
                name,
                beta,
                span(cls := "blindspot__tagline")(trans.site.learnFromYourMistakes(), ", shuffled")
              )
            ),
            div(cls := "box__top__actions")(windowSelect(home))
          ),
          home.state match
            case MistakeHome.State.Cold => cold(home)
            case MistakeHome.State.Warming => warming(home)
            case MistakeHome.State.Ready => ready(home)
          ,
          (home.stats.total > 0).option(stats(home)),
          (home.stats.total > 0).option(reset(home))
        )

  /* What is in the deck, as far as it can honestly be said: the cards already
   * turned over, newest first. There is no "positions remaining" here either,
   * and for the same reason as on the landing - see modules/mistake/README.md.
   *
   * The game id is not withheld on this page. It stops being one the moment a
   * position is answered: the board hands back this same link in the reply. */
  def answered(pager: Paginator[MistakeSolve], window: Option[String])(using Context) =
    Page(s"$name • Answered")
      .css("mistake.home"):
        main(cls := "box box-pad page-small blindspot")(
          boxTop(
            // the title is the way back: two pages does not earn a subnav
            h1(a(href := routes.Mistake.home(window), dataIcon := Icon.LessThan, cls := "text")("Answered"))
          ),
          if pager.nbResults == 0 then p("Nothing answered yet.")
          else
            frag(
              table(cls := "blindspot__answered slist slist-pad")(
                tbody(
                  pager.currentPageResults.map: solve =>
                    tr(
                      td(resultName(solve.result)),
                      td(momentFromNow(solve.date)),
                      td(a(href := solve.gameUrl)(trans.site.seeTheGameItCameFrom()))
                    )
                )
              ),
              pager.hasToPaginate.option(
                pagination(
                  page => routes.Mistake.answered(page, window).url,
                  pager.currentPage,
                  pager.nbPages,
                  showPost = true
                )
              )
            )
        )

  private def beta = strong(cls := "blindspot__beta")("BETA")

  /* Arriving with nothing analysed asks for analysis by itself, because a page
   * that explains the exercise and then makes you press a button before it can
   * start is a worse first visit. It is still a POST, and still the same one
   * the button submits. */
  private def autoRequests(home: MistakeHome) =
    home.state == MistakeHome.State.Cold && home.canRequest && home.nbUnanalysed > 0

  private def cold(home: MistakeHome)(using Context) = frag(
    p(
      if home.nbUnanalysed > 0
      then frag("No analysed games from ", strong(home.window.name), ".")
      else frag("No games from ", strong(home.window.name), ".")
    ),
    (home.nbUnanalysed > 0).option(requestForm(home))
  )

  /* Tutor shows the games it is analysing while you wait, and we cannot: those
   * are exactly the games we withhold. No waiting notice either: `bar-glider`
   * means "any second now" - tournament pairing, a clock running out - and
   * this is minutes away. A sentence and a count that has moved when you
   * come back. */
  private def warming(home: MistakeHome)(using Context) =
    home.warming.map: w =>
      frag(
        p(strong(w.nbDone), " of ", strong(w.nbTotal), " games analysed. Come back later."),
        (home.nbAnalysed > 0).option(startButton(home))
      )

  private def ready(home: MistakeHome)(using Context) = frag(
    p(strong(home.nbAnalysed), " analysed games from ", strong(home.window.name), "."),
    startButton(home),
    (home.nbUnanalysed > 0 && home.canRequest).option(requestForm(home, secondary = true))
  )

  private def startButton(home: MistakeHome)(using Context) =
    div(cls := "blindspot__start")(
      a(
        cls := "button button-fat text",
        dataIcon := Icon.PlayTriangle,
        href := routes.Mistake.board(home.window.key.some)
      )(trans.site.play())
    )

  /* A POST, always. Rendering a page has never had the right to spend engine
   * time, and the landing existing at all is what makes that possible to keep. */
  private def requestForm(home: MistakeHome, secondary: Boolean = false)(using Context) =
    if !home.canRequest then
      p(cls := "blindspot__hint")("Already requested. Try again in an hour.")
    else
      postForm(
        cls := List(
          "blindspot__request" -> true,
          // the landing esm submits this one on arrival; without JS it is a button
          "blindspot__request--auto" -> !secondary
        ),
        action := routes.Mistake.analyse(home.window.key.some)
      )(
        submitButton(
          cls := List("button text" -> true, "button-fat" -> !secondary, "button-empty" -> secondary),
          dataIcon := Icon.Microscope
        )(s"Analyse ${home.nbToRequest} games"),
        p(cls := "blindspot__hint")("Once an hour.")
      )

  /* Read off mistake_solve and nothing else. Same shape as the perf stat page's
   * counter: the total, then the breakdown under it with a percent column. */
  private def stats(home: MistakeHome)(using Context) =
    val s = home.stats
    st.section(cls := "blindspot__stats")(
      table(
        tbody(
          tr(
            // the only thing here that is a list rather than a number
            th(a(href := routes.Mistake.answered(1, home.window.key.some))("Answered")),
            td(s.total.toString),
            td
          ),
          row(resultName(MistakeResult.win), s.nb(MistakeResult.win), s.total),
          row(resultName(MistakeResult.fail), s.nb(MistakeResult.fail), s.total),
          row(resultName(MistakeResult.skip), s.nb(MistakeResult.skip), s.total),
          row(s"Solved, last ${s.recent.size}", s.nbRecent(MistakeResult.win), s.recent.size)
        )
      )
    )

  private def resultName(r: MistakeResult) = r match
    case MistakeResult.win => "Solved"
    case MistakeResult.fail => "Gave up"
    case MistakeResult.skip => "Skipped"

  private def row(label: String, nb: Int, total: Int) =
    tr(cls := "full")(
      th(label),
      td(nb.toString),
      td(if total > 0 then s"${100 * nb / total}%" else "")
    )

  /* Where to draw from, as a from and a to - tutor's date range is the same two
   * fields. Both halves are whitelists rather than free numbers: every widening
   * of `from` costs fishnet, and `to` is what keeps the position cold, so
   * neither is a box you can type any month into. Setting one holds the other,
   * and a combination that would invert the range is simply not offered. */
  private def windowSelect(home: MistakeHome)(using Context) =
    val w = home.window
    frag(
      lila.ui.bits.mselect(
        "blindspot-window-from",
        span(cls := "text", dataIcon := Icon.Clock)(w.fromName),
        MistakeWindow.sinceChoices
          .filter(_ > w.untilMonths)
          .map: since =>
            windowLink(MistakeWindow(since, w.untilMonths), w, _.fromName)
      ),
      lila.ui.bits.mselect(
        "blindspot-window-to",
        span(cls := "text")(w.toName),
        MistakeWindow.untilChoices
          .filter(_ < w.sinceMonths)
          .map: until =>
            windowLink(MistakeWindow(w.sinceMonths, until), w, _.toName)
      )
    )

  private def windowLink(to: MistakeWindow, current: MistakeWindow, label: MistakeWindow => String) =
    a(
      href := routes.Mistake.home(to.key.some),
      cls := (to == current).option("current")
    )(label(to))

  private def reset(home: MistakeHome)(using Context) =
    postForm(cls := "blindspot__reset", action := routes.Mistake.reset(home.window.key.some))(
      submitButton(cls := "button button-red button-empty yes-no-confirm")("Reset")
    )
