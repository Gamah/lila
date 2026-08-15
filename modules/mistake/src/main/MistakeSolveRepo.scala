package lila.mistake

import chess.Ply
import reactivemongo.api.bson.*

import lila.db.dsl.{ *, given }

/* One document per (user, game, ply). That's the whole schema.
 * It is what turns the shuffle into a deck rather than a random draw with
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

  // "do it again": puts the whole deck back
  def clear(userId: UserId): Funit = coll.delete.one($doc("u" -> userId)).void

  def count(userId: UserId): Fu[Int] = coll.countSel($doc("u" -> userId))
