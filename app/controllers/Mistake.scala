package controllers

import play.api.libs.json.Json
import play.api.mvc.*

import lila.app.{ *, given }
import lila.mistake.MistakeResult

final class Mistake(env: Env) extends LilaController(env):

  private def api = env.mistake.api
  private def jsonView = env.mistake.json

  def home = Auth { ctx ?=> me ?=>
    api.deal(me.userId).flatMap { deal =>
      Ok.page(views.mistake.home(jsonView.deal(deal, ctx.pref.some))).map(_.noCache)
    }
  }

  def next = Auth { ctx ?=> me ?=>
    api.deal(me.userId).map(jsonView.deal(_, ctx.pref.some)).map(JsonOk)
  }

  /* The client never learns which position it was looking at, so it can only
   * tell us how it went. In exchange we hand back the game it came from. */
  def record(result: String) = AuthBody { _ ?=> me ?=>
    MistakeResult(result).fold(fuccess(notFoundJson("Unknown result"))) { r =>
      api.record(me.userId, r).map { dealt =>
        JsonOk(Json.obj().add("game" -> dealt.map(_.gameUrl)))
      }
    }
  }

  def reset = AuthBody { ctx ?=> me ?=>
    for
      _ <- api.clear(me.userId)
      deal <- api.deal(me.userId)
    yield JsonOk(jsonView.deal(deal, ctx.pref.some))
  }
