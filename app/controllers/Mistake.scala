package controllers

import play.api.data.*
import play.api.data.Forms.*
import play.api.mvc.*

import lila.app.*
import lila.mistake.{ MistakeAnswered, MistakeFilter, MistakeResult, MistakeScope, MistakeWindow }

final class Mistake(env: Env) extends LilaController(env):

  private def api = env.mistake.api
  private def jsonView = env.mistake.json

  private def windowOf(key: Option[String]) = MistakeWindow.orDefault(key)

  /* Three submit buttons share one form: `result` is whichever one was pressed,
   * empty for "all of them", and `scope` is the radio above them. Form fields
   * rather than route parameters, because a POST body is where a button that
   * carries its own value puts it. */
  private val putBackForm = Form(
    tuple(
      "result" -> optional(nonEmptyText),
      "scope" -> optional(nonEmptyText),
      "window" -> optional(nonEmptyText)
    )
  )

  /* Reads only. No position is handed out and no analysis is asked for, so
   * opening this in a background tab costs nothing - which is the whole reason
   * it is not the board any more. */
  def home(window: Option[String]) = Auth { ctx ?=> me ?=>
    api.home(me.userId, windowOf(window)).flatMap { home =>
      Ok.page(views.mistake.home(home))
    }
  }

  def board(window: Option[String]) = Auth { ctx ?=> me ?=>
    val w = windowOf(window)
    api.next(me.userId, w).flatMap { mistake =>
      Ok.page(views.mistake.board(jsonView.next(mistake, ctx.pref.some), w)).map(_.noCache)
    }
  }

  def next(window: Option[String]) = Auth { ctx ?=> me ?=>
    api.next(me.userId, windowOf(window)).map(jsonView.next(_, ctx.pref.some)).map(JsonOk)
  }

  /* Hands unanalysed games to fishnet. A POST because it spends something, and
   * rate limited in mongo rather than in memory, so a deploy doesn't reset it. */
  def analyse(window: Option[String]) = AuthBody { _ ?=> me ?=>
    api.requestAnalysis(me.userId, windowOf(window)).map { _ =>
      Redirect(routes.Mistake.home(window))
    }
  }

  /* The client says how it went, not what it was answering: which position is
   * in flight is the server's to know, so a stale tab can't record against
   * whatever it still has on screen. */
  def record(result: String) = AuthBody { _ ?=> me ?=>
    MistakeResult(result).fold(fuccess(notFoundJson("Unknown result"))) { r =>
      api.record(me.userId, r).inject(jsonOkResult)
    }
  }

  /* Every position ever answered. Reads only, and paged: nothing here is
   * derived from a game, because the answer carries its own board. */
  def answered(page: Int, result: Option[String], window: Option[String]) = Auth { ctx ?=> me ?=>
    val filter = MistakeFilter.orAll(result)
    for
      pager <- api.answered(me.userId, filter, page)
      stats <- api.stats(me.userId)
      res <- Ok.page(views.mistake.answered(MistakeAnswered(windowOf(window), filter, pager, stats)))
    yield res
  }

  /* Puts answered positions back in the queue. Narrow by result, and by the
   * window if that is the scope asked for; with neither it is everything.
   * Lands back on the list, which is where you can see what it did. */
  def putBack = AuthBody { ctx ?=> me ?=>
    bindForm(putBackForm)(
      _ => Redirect(routes.Mistake.answered(1, none, none)),
      { case (result, scope, window) =>
        val w = windowOf(window)
        api
          .putBack(
            me.userId,
            result.flatMap(MistakeResult.apply),
            (MistakeScope.orAllTime(scope) == MistakeScope.window).option(w)
          )
          // back to the unfiltered list: what just changed is what is left in it
          .inject(Redirect(routes.Mistake.answered(1, none, w.key.some)))
      }
    )
  }

  /* Posted from the board only, which wants the next position back. The page's
   * own put-back is `putBack` above: it narrows, and it redirects. */
  def reset(window: Option[String]) = AuthBody { ctx ?=> me ?=>
    val w = windowOf(window)
    for
      _ <- api.clear(me.userId)
      res <- negotiate(
        Redirect(routes.Mistake.home(window)).toFuccess,
        api.next(me.userId, w).map(jsonView.next(_, ctx.pref.some)).map(JsonOk)
      )
    yield res
  }
