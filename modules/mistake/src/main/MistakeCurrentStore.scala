package lila.mistake

import lila.memo.CacheApi

/* Remembers which position each user is currently looking at.
 *
 * The client is never told the game id, so it can't tell us which position it
 * just answered - it only says how it went. That withholding is the feature,
 * and this is what makes it server side rather than a secret the client is
 * trusted to keep. */
final private class MistakeCurrentStore(cacheApi: CacheApi):

  private val cache = cacheApi.notLoadingSync[UserId, Mistake.Current](256, "mistake.current"):
    _.expireAfterWrite(1.hour).build()

  def put(userId: UserId, current: Mistake.Current): Unit = cache.put(userId, current)

  def take(userId: UserId): Option[Mistake.Current] =
    val current = cache.getIfPresent(userId)
    current.foreach(_ => cache.invalidate(userId))
    current
