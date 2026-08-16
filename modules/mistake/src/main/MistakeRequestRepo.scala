package lila.mistake

import reactivemongo.api.bson.*

import lila.db.dsl.{ *, given }

/* The last batch of games a user handed to fishnet: when, and which ones.
 *
 * It is two things at once, and that is why it is in mongo rather than in a
 * `OnceEvery`. It rate limits the request, which an in-memory limiter cannot do
 * across a deploy - a rolling restart would let every active user re-enqueue.
 * And the ids are what the warming state reads to say how far along it is,
 * since fishnet tells us nothing when it finishes. */
final private class MistakeRequestRepo(coll: Coll)(using Executor):

  import MistakeRequestRepo.*

  def get(userId: UserId): Fu[Option[Request]] =
    coll.byId[Bdoc](userId.value).map(_.flatMap(readRequest))

  def put(userId: UserId, gameIds: List[GameId]): Funit =
    coll.update
      .one(
        $id(userId),
        $doc("at" -> nowInstant, "ids" -> gameIds),
        upsert = true
      )
      .void

  private def readRequest(doc: Bdoc): Option[Request] =
    for
      at <- doc.getAsOpt[Instant]("at")
      ids <- doc.getAsOpt[List[GameId]]("ids")
    yield Request(at, ids)

object MistakeRequestRepo:

  /* One enqueue per user per hour, however often they reload. `isFresh` is the
   * other bound: after six hours we stop calling it "warming" even if games are
   * still unanalysed, because fishnet may simply have dropped them and a
   * progress bar that never finishes is worse than no progress bar. */
  case class Request(at: Instant, gameIds: List[GameId]):
    def canRequestAgain = at.isBefore(nowInstant.minusHours(1))
    def isFresh = at.isAfter(nowInstant.minusHours(6))
