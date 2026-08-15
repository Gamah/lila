package lila.mistake

import chess.Ply
import reactivemongo.api.bson.*

import lila.db.dsl.{ *, given }

/* One document per (user, game, ply). That's the whole schema.
 * It is what makes the queue deplete rather than being a random draw with
 * repeats, and what lets the page say how many are left. */
final private class MistakeSolveRepo(coll: Coll)(using Executor):

  private def makeId(userId: UserId, key: Mistake.Key) = s"$userId/${key.gameId}/${key.ply}"

  def solvedIn(userId: UserId, gameIds: List[GameId]): Fu[Set[Mistake.Key]] =
    if gameIds.isEmpty then fuccess(Set.empty)
    else
      coll
        .find($doc("u" -> userId, "g".$in(gameIds)), $doc("g" -> true, "p" -> true).some)
        .cursor[Bdoc](ReadPref.sec)
        .listAll()
        .map: docs =>
          docs
            .flatMap: doc =>
              for
                gameId <- doc.getAsOpt[GameId]("g")
                ply <- doc.getAsOpt[Int]("p")
              yield Mistake.Key(gameId, Ply(ply))
            .toSet

  def record(userId: UserId, key: Mistake.Key, result: MistakeResult): Funit =
    coll.update
      .one(
        $id(makeId(userId, key)),
        $doc(
          "u" -> userId,
          "g" -> key.gameId,
          "p" -> key.ply.value,
          "r" -> result.toString,
          "d" -> nowInstant
        ),
        upsert = true
      )
      .void

  // "do it again": puts every position back in the queue
  def clear(userId: UserId): Funit = coll.delete.one($doc("u" -> userId)).void

  def count(userId: UserId): Fu[Int] = coll.countSel($doc("u" -> userId))

  /* The whole of the stats page, in two reads. Deliberately not a second
   * insights: by phase, by piece and by opening belong elsewhere and would be a
   * worse copy of what already exists. */
  def stats(userId: UserId): Fu[MistakeStats] =
    for
      byResult <- countByResult(userId)
      recent <- recentResults(userId)
    yield MistakeStats(byResult, recent)

  private def countByResult(userId: UserId): Fu[Map[MistakeResult, Int]] =
    coll
      .aggregateList(MistakeResult.values.size, _.sec): framework =>
        import framework.*
        Match($doc("u" -> userId)) -> List(GroupField("r")("nb" -> SumAll))
      .map: docs =>
        docs.flatMap: doc =>
          for
            result <- doc.string("_id").flatMap(MistakeResult.apply)
            nb <- doc.int("nb")
          yield result -> nb
        .toMap

  private def recentResults(userId: UserId): Fu[List[MistakeResult]] =
    coll
      .find($doc("u" -> userId), $doc("r" -> true).some)
      .sort($doc("d" -> -1))
      .cursor[Bdoc](ReadPref.sec)
      .list(MistakeStats.recentSize)
      .map(_.flatMap(_.string("r").flatMap(MistakeResult.apply)))
