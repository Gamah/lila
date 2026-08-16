package lila.mistake

import chess.{ Color, Ply }
import chess.format.Fen
import reactivemongo.api.bson.*
import scalalib.paginator.{ AdapterLike, Paginator }

import lila.db.dsl.{ *, given }

/* One document per (user, game, ply), plus the board it was played on. That's
 * the whole schema.
 * It is what makes the queue deplete rather than being a random draw with
 * repeats. It does *not* let the page say how many are left - #2 claimed it
 * would, and it cannot: it knows what came out of the queue, never what is
 * still in it. `countIn` is the half of that promise we can keep. */
final private class MistakeSolveRepo(coll: Coll)(using Executor):

  import MistakeSolveRepo.{ *, given }

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

  /* How many positions this user has answered out of a given set of games -
   * the landing's only honest statement about a window, and served by the same
   * (u, g) index `solvedIn` uses. */
  def countIn(userId: UserId, gameIds: List[GameId]): Fu[Int] =
    if gameIds.isEmpty then fuccess(0)
    else coll.secondary.countSel($doc("u" -> userId, "g".$in(gameIds)))

  def record(
      userId: UserId,
      key: Mistake.Key,
      result: MistakeResult,
      position: MistakeSolve.Position
  ): Funit =
    coll.update
      .one(
        $id(makeId(userId, key)),
        $doc(
          "u" -> userId,
          "g" -> key.gameId,
          "p" -> key.ply.value,
          "r" -> result.toString,
          "d" -> nowInstant,
          "c" -> position.color.white,
          "f" -> position.board
        ),
        upsert = true
      )
      .void

  /* The answered list. Sorted newest first over the whole history: the window
   * governs what the queue draws from, and what you have already answered is a
   * different question from what you could be asked next. */
  def list(userId: UserId, filter: MistakeFilter, page: Int): Fu[Paginator[MistakeSolve]] =
    Paginator(
      adapter = new AdapterLike[MistakeSolve]:
        private val selector = filterSelect(userId, filter)
        def nbResults = coll.secondary.countSel(selector)
        def slice(offset: Int, length: Int) =
          coll
            .find(selector)
            .sort($sort.desc("d"))
            .skip(offset)
            .cursor[MistakeSolve](ReadPref.sec)
            .list(length)
      ,
      currentPage = page,
      maxPerPage = maxPerPage
    )

  /* Puts positions back in the queue: deleting the answer is what makes the
   * position drawable again. A window narrows it to the games that window
   * covers, which is why it arrives as ids rather than as dates - the same
   * bounded pool the board draws on. */
  def putBack(userId: UserId, result: Option[MistakeResult], gameIds: Option[List[GameId]]): Fu[Int] =
    gameIds match
      case Some(Nil) => fuccess(0)
      case _ =>
        val selector = $doc("u" -> userId) ++
          result.fold($empty)(r => $doc("r" -> r.toString)) ++
          gameIds.fold($empty)(ids => $doc("g".$in(ids)))
        coll.delete.one(selector).map(_.n)

  // "do it again" from the board: puts every position back in the queue
  def clear(userId: UserId): Funit = putBack(userId, none, none).void

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
        docs
          .flatMap: doc =>
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

private object MistakeSolveRepo:

  /* Cards rather than rows, so the page is a grid and a short page looks
   * broken. Twelve rows of four on a wide screen. */
  val maxPerPage = MaxPerPage(48)

  def filterSelect(userId: UserId, filter: MistakeFilter) =
    $doc("u" -> userId) ++ filter.result.fold($empty)(r => $doc("r" -> r.toString))

  given BSONDocumentReader[MistakeSolve] = BSONDocumentReader.option[MistakeSolve]: doc =>
    for
      gameId <- doc.getAsOpt[GameId]("g")
      ply <- doc.getAsOpt[Int]("p")
      result <- doc.string("r").flatMap(MistakeResult.apply)
      date <- doc.getAsOpt[Instant]("d")
    yield MistakeSolve(
      key = Mistake.Key(gameId, Ply(ply)),
      result = result,
      date = date,
      // both or neither: a row from before we kept the board has no colour either
      position = for
        white <- doc.getAsOpt[Boolean]("c")
        board <- doc.getAsOpt[Fen.Board]("f")
      yield MistakeSolve.Position(Color.fromWhite(white), board)
    )
