package lila.mistake

import com.softwaremill.macwire.*
import com.softwaremill.tagging.*

import lila.core.config.CollName
import lila.core.fishnet.FishnetRequest

@Module
final class Env(
    db: lila.db.Db,
    gameRepo: lila.game.GameRepo,
    divider: lila.game.Divider,
    analysisRepo: lila.analyse.AnalysisRepo,
    fishnetRequest: FishnetRequest,
    settingStore: lila.memo.SettingStore.Builder,
    cacheApi: lila.memo.CacheApi
)(using Executor):

  private lazy val solveColl = db(CollName("mistake_solve"))
  private lazy val requestColl = db(CollName("mistake_request"))

  lazy val nbAnalysisSetting = settingStore[Int](
    "mistakeNbAnalysis",
    default = 20,
    text = "Number of fishnet analysis to request per user per hour of learning from mistakes".some
  ).taggedWith[NbAnalysis]

  // two collections in scope, so these two are named rather than wired
  private lazy val solveRepo = MistakeSolveRepo(solveColl)
  private lazy val requestRepo = MistakeRequestRepo(requestColl)

  private lazy val currentStore = wire[MistakeCurrentStore]
  private lazy val fishnet = wire[MistakeFishnet]

  lazy val api = wire[MistakeApi]

  lazy val json = wire[MistakeJson]

trait NbAnalysis
