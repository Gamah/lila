package lila.mistake

import chess.{ Color, Ply }
import reactivemongo.api.bson.*
import scalalib.paginator.Paginator

import lila.db.dsl.{ *, given }

/* One document per (user, game, ply). That's the whole schema.
 * It is what turns the shuffle into a deck rather than a random draw with
 * repeats, and what lets the page say how many are left. */
final private class MistakeSolveRepo(coll: Coll)(using Executor):

  import MistakeSolveRepo.given

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

  /* Colour is stored as a boolean rather than looked up again later: the whole
   * point of the answered list is that it costs one indexed query, and the
   * board already knows which side you were. */
  def record(userId: UserId, current: Mistake.Current, result: MistakeResult): Funit =
    coll.update
      .one(
        $id(makeId(userId, current.key)),
        $doc(
          "u" -> userId,
          "g" -> current.key.gameId,
          "p" -> current.key.ply.value,
          "c" -> current.color.white,
          "r" -> result.toString,
          "d" -> nowInstant
        ),
        upsert = true
      )
      .void

  // "do it again": puts the whole deck back
  def clear(userId: UserId): Funit = coll.delete.one($doc("u" -> userId)).void

  def count(userId: UserId): Fu[Int] = coll.countSel($doc("u" -> userId))

  /* The whole of the stats page, in two reads. Deliberately not a second
   * insights: by phase, by piece and by opening are tutor's job and would be a
   * worse copy of it. */
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

  /* The answered list, newest first. One query on the `u:1, d:-1` index the
   * landing's recent form already needs, and no derivation at all. */
  def answered(userId: UserId, page: Int): Fu[Paginator[MistakeSolve]] =
    Paginator[MistakeSolve](
      lila.db.paginator.Adapter[MistakeSolve](
        collection = coll,
        selector = $doc("u" -> userId),
        projection = none,
        sort = $doc("d" -> -1),
        readPref = _.sec
      ),
      currentPage = page,
      maxPerPage = MistakeSolveRepo.maxPerPage
    )

  private def recentResults(userId: UserId): Fu[List[MistakeResult]] =
    coll
      .find($doc("u" -> userId), $doc("r" -> true).some)
      .sort($doc("d" -> -1))
      .cursor[Bdoc](ReadPref.sec)
      .list(MistakeStats.recentSize)
      .map(_.flatMap(_.string("r").flatMap(MistakeResult.apply)))

private object MistakeSolveRepo:

  val maxPerPage = MaxPerPage(50)

  given BSONDocumentReader[MistakeSolve] = BSONDocumentReader.option[MistakeSolve]: doc =>
    for
      gameId <- doc.getAsOpt[GameId]("g")
      ply <- doc.int("p")
      result <- doc.string("r").flatMap(MistakeResult.apply)
      date <- doc.getAsOpt[Instant]("d")
    yield MistakeSolve(
      gameId = gameId,
      ply = Ply(ply),
      color = doc.getAsOpt[Boolean]("c").map(Color.fromWhite),
      result = result,
      date = date
    )
