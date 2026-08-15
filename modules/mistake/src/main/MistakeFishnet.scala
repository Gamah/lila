package lila.mistake

import com.softwaremill.tagging.*
import scalalib.cache.OnceEvery

import lila.core.fishnet.FishnetRequest
import lila.game.GameRepo
import lila.memo.SettingStore

/* Most of an ordinary player's games aren't analysed, so most of their mistakes
 * aren't visible to us. Same move as TutorFishnet.ensureSomeAnalysis: hand the
 * unanalysed ones to fishnet.
 *
 * Unlike tutor we never await. Tutor is a background report and can block for
 * five minutes; this is a page someone is looking at. Games queued now show up
 * in the deck on a later visit. */
final private class MistakeFishnet(
    gameRepo: GameRepo,
    analyser: FishnetRequest,
    nbAnalysis: SettingStore[Int] @@ NbAnalysis
)(using Executor):

  private def maxToAnalyse = Max(nbAnalysis.get())

  // one enqueue per user per hour, however often they reload
  private val dedup = OnceEvery[UserId](1.hour)

  def ensureSomeAnalysis(userId: UserId, window: MistakeWindow): Fu[Int] =
    if !dedup(userId) then fuccess(0)
    else
      gameRepo
        .unanalysedBetween(userId, window.since, window.until, maxToAnalyse)
        .map: games =>
          games.foreach(g => analyser.mistake(g.id))
          games.size
