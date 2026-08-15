package lila.mistake

import com.softwaremill.tagging.*

import lila.core.fishnet.FishnetRequest
import lila.game.GameRepo
import lila.memo.SettingStore

/* Most of an ordinary player's games aren't analysed, so most of their mistakes
 * aren't visible to us. Same move as TutorFishnet.ensureSomeAnalysis: hand the
 * unanalysed ones to fishnet.
 *
 * Unlike tutor we never await. Tutor is a background report and can block for
 * five minutes; this is a page someone is looking at. Games queued now show up
 * in the deck on a later visit, and until they do the landing page says so.
 *
 * Asking is a POST and only a POST. Loading a page must never spend fishnet:
 * that is the whole reason the landing page exists. */
final private class MistakeFishnet(
    gameRepo: GameRepo,
    requestRepo: MistakeRequestRepo,
    analyser: FishnetRequest,
    nbAnalysis: SettingStore[Int] @@ NbAnalysis
)(using Executor):

  import MistakeRequestRepo.Request

  def maxPerRequest: Int = nbAnalysis.get()

  private def maxToAnalyse = Max(maxPerRequest)

  def lastRequest(userId: UserId): Fu[Option[Request]] = requestRepo.get(userId)

  /* How much of the last batch fishnet has come back with. None once there is
   * nothing outstanding, or once the batch is too old to still be waiting on. */
  def warming(request: Option[Request]): Fu[Option[MistakeWarming]] =
    request.filter(_.isFresh).filter(_.gameIds.nonEmpty) match
      case None => fuccess(none)
      case Some(req) =>
        gameRepo
          .unanalysedGames(req.gameIds, Max(req.gameIds.size))
          .map: pending =>
            (pending.nonEmpty).option:
              MistakeWarming(
                nbDone = req.gameIds.size - pending.size,
                nbTotal = req.gameIds.size,
                since = req.at
              )

  /* Returns how many games were sent, or none if the request was refused for
   * being inside the cooldown. The caller says which, out loud. */
  def request(userId: UserId, window: MistakeWindow): Fu[Option[Int]] =
    requestRepo.get(userId).flatMap { last =>
      if last.exists(!_.canRequestAgain) then fuccess(none)
      else
        gameRepo
          .unanalysedBetween(userId, window.since, window.until, maxToAnalyse)
          .flatMap: games =>
            games.foreach(g => analyser.mistake(g.id))
            // an empty batch doesn't start a cooldown: there was nothing to spend
            games.nonEmpty
              .so(requestRepo.put(userId, games.map(_.id)))
              .inject(games.size.some)
    }
