package controllers

import play.api.libs.json.Json
import play.api.mvc.*

import lila.app.{ *, given }
import lila.mistake.{ MistakeResult, MistakeWindow }

final class Mistake(env: Env) extends LilaController(env):

  private def api = env.mistake.api
  private def jsonView = env.mistake.json

  private def windowOf(key: Option[String]) = MistakeWindow.orDefault(key)

  /* Reads only. No position is handed out and no analysis is asked for, so
   * opening this in a background tab costs nothing - which is the whole reason
   * it is not the board any more. */
  def home(window: Option[String]) = Auth { ctx ?=> me ?=>
    api.home(me.userId, windowOf(window)).flatMap { home =>
      Ok.page(views.mistake.home(home))
    }
  }

  /* The half of "what is in the deck" that is already stored. Also a read, and
   * also idempotent: it lists what has been answered, and derives nothing. */
  def answered(page: Int, window: Option[String]) = Auth { ctx ?=> me ?=>
    api.answered(me.userId, page).flatMap { pager =>
      Ok.page(views.mistake.answered(pager, window))
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

  /* The client never learns which position it was looking at, so it can only
   * tell us how it went. In exchange we hand back the game it came from. */
  def record(result: String) = AuthBody { _ ?=> me ?=>
    MistakeResult(result).fold(fuccess(notFoundJson("Unknown result"))) { r =>
      api.record(me.userId, r).map { current =>
        JsonOk(Json.obj().add("game" -> current.map(_.gameUrl)))
      }
    }
  }

  /* Posted from two places: the board, which wants the next position back, and
   * the landing page, which wants to look at its own stats again. */
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
