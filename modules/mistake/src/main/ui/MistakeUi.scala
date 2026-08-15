package lila.mistake
package ui

import play.api.libs.json.{ JsObject, Json }

import lila.core.i18n.Translate
import lila.ui.*

import ScalatagsTemplate.{ *, given }

/* "Blindspot" is the name on the page. `mistake` stays the token everywhere in
 * the code and `/mistakes` stays the path - see modules/mistake/README.md.
 *
 * The layout is Tutor's, on purpose: a column of boxes, a mascot band, a card
 * grid, a collapsible glossary. Tutor is the site's other "here is your own
 * chess, computed for you" page and the two should not look like they come from
 * different sites. What is not taken from Tutor is its habit of hardcoding
 * English - every string here goes through translation/source/blindspot.xml.
 * Tutor itself wants the same treatment: Gamah/lila#7. */
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
      .i18n(_.study, _.blindspot)
      .js(PageModule("mistake", data))
      .flag(_.zen)
      .flag(_.zoom):
        main(cls := "mistake mistake-app")(
          div(cls := "mistake__board main-board"),
          div(cls := "mistake__side")
        )

  /* The landing page. Everything here is a read: it says what the exercise is,
   * whether there is anything for you yet, and how you have been doing.
   * Starting, asking for analysis and putting positions back are all a link or
   * a POST away. */
  def home(home: MistakeHome)(using Context) =
    Page(name)
      .css("mistake.home")
      .i18n(_.blindspot):
        main(cls := "page page-small blindspot blindspot-home")(
          div(cls := "box blindspot__head")(
            boxTop(
              div(cls := "box__top__title")(
                h1(cls := "text", dataIcon := Icon.ChasingArrows)(
                  name,
                  beta,
                  span(cls := "blindspot__tagline")(trans.blindspot.tagline())
                )
              ),
              div(cls := "box__top__actions")(windowSelect(home))
            ),
            intro,
            div(cls := "blindspot__state")(
              home.state match
                case MistakeHome.State.Cold => cold(home)
                case MistakeHome.State.Warming => warming(home)
                case MistakeHome.State.Ready => ready(home)
            )
          ),
          tabs(answered = false, home.window),
          (home.stats.total > 0).option(cards(home)),
          glossary
        )

  /* Every position ever answered, drawn rather than described. The list that
   * was cut in 0b8dc11906 was three columns of words per row; the data was
   * never the problem, and the board is now on the answer itself so a page of
   * them is still one read. */
  def answered(ans: MistakeAnswered)(using Context) =
    Page(s"$name • ${trans.blindspot.answeredPositions.txt()}")
      .css("mistake.home")
      .i18n(_.blindspot)
      .js(ans.pager.hasNextPage.option(infiniteScrollEsmInit)):
        main(cls := "page page-small blindspot blindspot-answered")(
          div(cls := "box blindspot__head")(
            boxTop(
              div(cls := "box__top__title")(
                h1(cls := "text", dataIcon := Icon.ChasingArrows)(
                  name,
                  span(cls := "blindspot__tagline")(trans.blindspot.answeredPositions())
                )
              )
            )
          ),
          tabs(answered = true, ans.window),
          div(cls := "box blindspot__list")(
            filterTabs(ans),
            if ans.pager.nbResults == 0 then
              div(cls := "blindspot__none")(
                if ans.filter == MistakeFilter.all then trans.blindspot.noPositionsAnsweredYet()
                else trans.blindspot.nothingMatchesThisFilter()
              )
            else
              div(cls := "blindspot-cards blindspot-cards--positions infinite-scroll")(
                ans.pager.currentPageResults.map(solveCard),
                pagerNext(ans.pager, np => routes.Mistake.answered(np, ans.filter.key, ans.window.key.some).url)
              )
          ),
          putBack(ans)
        )

  private def beta = strong(cls := "blindspot__beta")("BETA")

  /* Tutor's band, without Tutor's octopus: the mascot slot is laid out and left
   * empty until Blindspot has one of its own, rather than the page being
   * reflowed around a picture later. The component hides the slot below
   * $x-small anyway, so an empty one is a layout it already handles. */
  private def intro(using Context) =
    div(cls := "mascot-says")(
      div(cls := "mascot-says__content")(
        p(cls := "mascot__intro")(trans.blindspot.mascotIntro()),
        p(trans.blindspot.mascotIntroMore())
      ),
      div(cls := "mascot mascot--empty")
    )

  /* Two pages, so two tabs. The profile's angles are the shape; a page-menu
   * subnav, which is what Tutor and Puzzle use, is where this goes if a third
   * page ever turns up. */
  private def tabs(answered: Boolean, window: MistakeWindow)(using Context) =
    div(cls := "blindspot__tabs number-menu number-menu--tabs menu-box-pop")(
      a(
        cls := List("nm-item" -> true, "active" -> !answered),
        href := routes.Mistake.home(window.key.some)
      )(trans.blindspot.overview()),
      a(
        cls := List("nm-item" -> true, "active" -> answered),
        href := routes.Mistake.answered(1, none, window.key.some)
      )(trans.blindspot.answeredPositions())
    )

  /* Arriving with nothing analysed does *not* ask for analysis by itself. The
   * landing used to submit the request form on load, on the grounds that Tutor
   * does the same; it does not - /tutor renders and waits for "Compute my tutor
   * report". Spending someone's fishnet is worth a press. Gamah/lila#5. */
  private def cold(home: MistakeHome)(using Context) = frag(
    p(
      if home.nbUnanalysed > 0
      then trans.blindspot.noAnalysedGamesFrom(strong(windowName(home.window)))
      else trans.blindspot.noGamesFrom(strong(windowName(home.window)))
    ),
    (home.nbUnanalysed > 0).option(requestForm(home))
  )

  /* A count, not a list of the games and not an ETA. Fishnet never says when it
   * will be done, so any number would be invented, and a list of game rows is
   * noise on a page that is about positions. `bar-glider` is wrong too: it
   * means "any second now", and this is minutes away. */
  private def warming(home: MistakeHome)(using Context) =
    home.warming.map: w =>
      frag(
        p(
          trans.blindspot.nbOfNbGamesAnalysed(strong(w.nbDone), strong(w.nbTotal)),
          " ",
          trans.blindspot.comeBackLater()
        ),
        (home.nbAnalysed > 0).option(startButton(home))
      )

  /* The one thing the landing says about the queue. Not how much is left in it
   * - that means deriving candidates for every game in the window - but how
   * much you have taken out of it, which `mistake_solve` already knows. */
  private def ready(home: MistakeHome)(using Context) = frag(
    p(trans.blindspot.nbAnalysedGamesFrom(strong(home.nbAnalysed), strong(windowName(home.window)))),
    (home.nbAnswered > 0).option(
      p(cls := "blindspot__answered")(
        trans.blindspot.youHaveAnsweredNbPositionsFromThem
          .plural(home.nbAnswered, strong(home.nbAnswered.localize))
      )
    ),
    startButton(home),
    (home.nbUnanalysed > 0 && home.canRequest).option(requestForm(home, secondary = true))
  )

  private def startButton(home: MistakeHome)(using Context) =
    div(cls := "blindspot__start")(
      a(
        cls := "button button-fat button-no-upper text",
        dataIcon := Icon.PlayTriangle,
        href := routes.Mistake.board(home.window.key.some)
      )(trans.site.play())
    )

  /* A POST, always. Rendering a page has never had the right to spend engine
   * time, and the landing existing at all is what makes that possible to keep. */
  private def requestForm(home: MistakeHome, secondary: Boolean = false)(using Context) =
    if !home.canRequest then p(cls := "blindspot__hint")(trans.blindspot.alreadyRequested())
    else
      postForm(
        cls := "blindspot__request",
        action := routes.Mistake.analyse(home.window.key.some)
      )(
        submitButton(
          cls := List(
            "button button-no-upper text" -> true,
            "button-fat" -> !secondary,
            "button-empty" -> secondary
          ),
          dataIcon := Icon.Microscope
        )(trans.blindspot.analyseNbGames.plural(home.nbToRequest, home.nbToRequest.localize)),
        p(cls := "blindspot__hint")(trans.blindspot.onceAnHour())
      )

  // ----------------------------------------------------------------- the record

  /* Two cards rather than the table of percentages this used to be. Same three
   * numbers, plus the one thing a total cannot say: whether it is going any
   * better lately. */
  private def cards(home: MistakeHome)(using Context) =
    div(cls := "blindspot-cards")(
      div(cls := "blindspot-card")(
        cardTop(Icon.BarChart, trans.blindspot.yourRecord.txt(), nbAnswered(home.stats.total)),
        div(cls := "blindspot-card__content")(recordBar(home.stats))
      ),
      a(
        cls := "blindspot-card blindspot-card--link",
        href := routes.Mistake.answered(1, none, home.window.key.some)
      )(
        cardTop(
          Icon.Storm,
          trans.blindspot.answeredPositions.txt(),
          trans.blindspot.theLastNbAnswered
            .pluralSameTxt(home.stats.recent.size)
        ),
        div(cls := "blindspot-card__content")(
          recentStrip(home.stats),
          span(cls := "blindspot-card__more")(trans.blindspot.seeThemAll())
        )
      )
    )

  private def cardTop(icon: Icon, title: String, sub: String) =
    div(cls := "blindspot-card__top")(
      iconTag(icon),
      div(cls := "blindspot-card__top__title")(
        h2(cls := "blindspot-card__top__title__text")(title),
        div(cls := "blindspot-card__top__title__sub")(sub)
      )
    )

  private def nbAnswered(nb: Int)(using Context) =
    trans.blindspot.nbPositionsAnswered.pluralSameTxt(nb)

  /* One bar for the whole record, then the numbers under it. Tutor's grade
   * strip is seven segments lit or unlit against your peers; there are no peers
   * here and nothing to grade against, so the same colours are put to a
   * proportion instead of a score. */
  private def recordBar(s: MistakeStats)(using Context) =
    val rows = List(
      MistakeResult.win -> trans.blindspot.solved.txt(),
      MistakeResult.fail -> trans.blindspot.gaveUp.txt(),
      MistakeResult.skip -> trans.blindspot.skipped.txt()
    )
    div(cls := "blindspot-record")(
      div(cls := "blindspot-record__bar")(
        rows.map: (result, label) =>
          val nb = s.nb(result)
          (nb > 0).option(
            div(
              cls := s"blindspot-record__bar__seg blindspot-record__bar__seg--$result",
              style := s"width:${percent(nb, s.total)}%",
              title := s"$label: $nb"
            )
          )
      ),
      ul(cls := "blindspot-record__legend")(
        rows.map: (result, label) =>
          li(
            span(cls := s"blindspot-record__dot blindspot-record__dot--$result"),
            span(cls := "blindspot-record__legend__label")(label),
            strong(s.nb(result).localize),
            em(s"${percent(s.nb(result), s.total)}%")
          )
      )
    )

  /* One segment per recent answer, oldest on the left. Twenty of them say
   * something a lifetime percentage cannot: that the last handful went badly. */
  private def recentStrip(s: MistakeStats)(using Context) =
    div(cls := "blindspot-strip")(
      s.recent.reverse.map: result =>
        div(cls := s"blindspot-strip__seg blindspot-strip__seg--$result")
    )

  private def percent(nb: Int, total: Int): Int = if total > 0 then 100 * nb / total else 0

  // --------------------------------------------------------------- the list

  /* A small set of mutually exclusive cuts, which is what the profile's angles
   * are for. In the URL, so a filtered list is a link someone can keep. */
  private def filterTabs(ans: MistakeAnswered)(using Context) =
    val counts = List(
      MistakeFilter.all -> (trans.blindspot.all.txt(), ans.stats.total),
      MistakeFilter.win -> (trans.blindspot.solved.txt(), ans.stats.nb(MistakeResult.win)),
      MistakeFilter.fail -> (trans.blindspot.gaveUp.txt(), ans.stats.nb(MistakeResult.fail)),
      MistakeFilter.skip -> (trans.blindspot.skipped.txt(), ans.stats.nb(MistakeResult.skip))
    )
    div(cls := "blindspot__filters number-menu number-menu--tabs")(
      counts.map: (filter, labelled) =>
        val (label, nb) = labelled
        a(
          cls := List("nm-item" -> true, "active" -> (filter == ans.filter)),
          href := routes.Mistake.answered(1, filter.key, ans.window.key.some).url
        )(label, span(cls := "blindspot__filters__nb")(nb.localize))
    )

  /* The card is the position. A row that spelled out "Solved / 45 minutes ago /
   * see the game it came from" is what this list was cut for the first time. */
  private def solveCard(solve: MistakeSolve)(using Context) =
    val body = frag(
      solve.position match
        case Some(p) =>
          div(cls := "blindspot-position__board")(chessgroundMini(p.board, p.color)(div))
        case None =>
          div(cls := "blindspot-position__board blindspot-position__board--gone")(
            trans.blindspot.positionNotKept()
          )
      ,
      div(cls := "blindspot-position__meta")(
        span(cls := s"blindspot-position__result blindspot-position__result--${solve.result}")(
          resultName(solve.result)
        ),
        momentFromNow(solve.date)
      )
    )
    solve.gameUrl match
      case Some(url) =>
        a(cls := "blindspot-position blindspot-card blindspot-card--link", href := url)(body)
      case None => div(cls := "blindspot-position blindspot-card")(body)

  private def resultName(result: MistakeResult)(using Context) = result match
    case MistakeResult.win => trans.blindspot.solved()
    case MistakeResult.fail => trans.blindspot.gaveUp()
    case MistakeResult.skip => trans.blindspot.skipped()

  // -------------------------------------------------------------- putting back

  /* Under the list, where what it will do is in view. The landing had a bare
   * red button under a table of numbers and it said nothing about what it
   * destroyed; here the thing it destroys is the page you are on.
   *
   * Wiping everything is the last option rather than the only one: the answer
   * carries its result, so "the ones I gave up on" is a selector rather than a
   * schema change. */
  private def putBack(ans: MistakeAnswered)(using Context) =
    (ans.stats.total > 0).option(
      div(cls := "box box-pad blindspot__put-back")(
        h2(trans.blindspot.putPositionsBack()),
        p(cls := "blindspot__hint")(trans.blindspot.putPositionsBackExplanation()),
        postForm(cls := "blindspot__put-back__form", action := routes.Mistake.putBack.url)(
          input(tpe := "hidden", st.name := "window", value := ans.window.key),
          div(cls := "blindspot__put-back__scope")(
            scopeRadio(ans, MistakeScope.window, trans.blindspot.scopeWindow.txt(windowName(ans.window))),
            scopeRadio(ans, MistakeScope.allTime, trans.blindspot.scopeAllTime.txt())
          ),
          div(cls := "blindspot__put-back__actions")(
            putBackButton(MistakeResult.fail.some, trans.blindspot.putBackTheOnesYouGaveUpOn.txt()),
            putBackButton(MistakeResult.skip.some, trans.blindspot.putBackTheOnesYouSkipped.txt()),
            /* The filter-driven one: whatever cut is showing above is what this
             * puts back, so narrowing the list is how you narrow the reset. On
             * the unfiltered list it is the wipe, and it is last for that
             * reason. */
            putBackButton(
              ans.filter.result,
              trans.blindspot.putBackTheseNbPositions.pluralSameTxt(showing(ans))
            )
          )
        )
      )
    )

  /* All time is the default because it is what the narrow buttons mean without
   * a window in hand, and because a scope nobody chose should be the one that
   * does not depend on a control further up the page. */
  private def scopeRadio(ans: MistakeAnswered, scope: MistakeScope, label: String) =
    st.label(cls := "blindspot__put-back__scope__choice")(
      input(
        tpe := "radio",
        st.name := "scope",
        value := scope.toString,
        (scope == MistakeScope.allTime).option(checked)
      ),
      label
    )

  private def showing(ans: MistakeAnswered): Int =
    ans.filter.result.fold(ans.stats.total)(ans.stats.nb)

  private def putBackButton(result: Option[MistakeResult], label: String) =
    submitButton(
      cls := "button button-empty button-red button-no-upper",
      st.name := "result",
      value := result.fold("")(_.toString)
    )(label)

  // -------------------------------------------------------------- the glossary

  /* Tutor's collapsible glossary, which is where it puts the "what is a peer"
   * question rather than in the middle of the report. Blindspot's version
   * answers the two things people ask of it: what got selected, and why this is
   * not their puzzle history. */
  private def glossary(using Context) =
    fieldset(cls := "box box-pad blindspot__glossary toggle-box toggle-box--toggle toggle-box--toggle-off")(
      legend(trans.blindspot.whatThisIs()),
      entry(trans.blindspot.whatCountsAsAMistake(), trans.blindspot.whatCountsAsAMistakeAnswer()),
      entry(
        trans.blindspot.howIsThisDifferentFromPuzzles(),
        trans.blindspot.howIsThisDifferentFromPuzzlesAnswer()
      ),
      entry(
        trans.blindspot.whyAreMyRecentGamesMissing(),
        trans.blindspot.whyAreMyRecentGamesMissingAnswer()
      ),
      entry(trans.blindspot.whyCantISeeWhichGame(), trans.blindspot.whyCantISeeWhichGameAnswer())
    )

  private def entry(question: Frag, answer: Frag) =
    div(cls := "blindspot__glossary__entry")(h3(question), p(answer))

  // ---------------------------------------------------------------- the window

  /* Where to draw from, as a from and a to. Both halves are whitelists rather
   * than free numbers: every widening of `from` costs fishnet, and `to` is what
   * keeps the position cold, so neither is a box you can type any month into.
   * Setting one holds the other, and a combination that would invert the range
   * is simply not offered. */
  private def windowSelect(home: MistakeHome)(using Context) =
    val w = home.window
    frag(
      lila.ui.bits.mselect(
        "blindspot-window-from",
        span(cls := "text", dataIcon := Icon.Clock)(fromName(w)),
        MistakeWindow.sinceChoices
          .filter(_ > w.untilMonths)
          .map: since =>
            windowLink(MistakeWindow(since, w.untilMonths), w, fromName)
      ),
      lila.ui.bits.mselect(
        "blindspot-window-to",
        span(cls := "text")(toName(w)),
        MistakeWindow.untilChoices
          .filter(_ < w.sinceMonths)
          .map: until =>
            windowLink(MistakeWindow(w.sinceMonths, until), w, toName)
      )
    )

  private def windowLink(to: MistakeWindow, current: MistakeWindow, label: MistakeWindow => String) =
    a(
      href := routes.Mistake.home(to.key.some),
      cls := (to == current).option("current")
    )(label(to))

  /* The window is a pair of numbers everywhere else in the module; it only
   * becomes words here, where there is a language to say them in. `until == 0`
   * is not "0 months ago", so both halves special case it. */
  private def windowName(w: MistakeWindow)(using Translate): String =
    if w.untilMonths == 0 then trans.blindspot.theLastNbMonths.pluralSameTxt(w.sinceMonths)
    else trans.blindspot.nbToNbMonthsAgo.txt(w.untilMonths, w.sinceMonths)

  private def fromName(w: MistakeWindow)(using Translate): String =
    trans.blindspot.fromNbMonthsAgo.pluralSameTxt(w.sinceMonths)

  private def toName(w: MistakeWindow)(using Translate): String =
    if w.untilMonths == 0 then trans.blindspot.toToday.txt()
    else trans.blindspot.toNbMonthsAgo.pluralSameTxt(w.untilMonths)
