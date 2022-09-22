package com.sbuslab.http.ratelimit

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

import net.spy.memcached.MemcachedClient
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

import com.sbuslab.utils.MemcacheSupport
import com.sbuslab.utils.condition.ConditionalOnConfig


@Lazy
@Component
@ConditionalOnConfig(
  name = Array("sbuslab.rate-limit.storage"),
  havingValue = "memcached"
)
class MemcachedRateLimitStorage(memcache: MemcachedClient)(implicit ec: ExecutionContext) extends MemcacheSupport with RateLimitStorage {

  private val empty = Future.successful(Map.empty[String, AnyRef])

  override def get(keys: Seq[String]): Future[Map[String, AnyRef]] =
    if (keys.nonEmpty) {
      asFutureBulk(memcache.asyncGetBulk(keys.asJava)).map(_.asScala.toMap)
    } else {
      empty
    }

  override def delete(key: String): Future[Unit] =
    asFutureOperation(memcache.delete(key)).map(_ ⇒ {})

  override def increment(key: String, expiration: Duration): Future[Long] =
    asFutureOperation(memcache.asyncIncr(key, 1, 1, ((System.currentTimeMillis + expiration.toMillis) / 1000).toInt))
      .mapTo[Long]

  override def set(key: String, expiration: Duration, value: Any): Future[Unit] =
    asFutureOperation(memcache.set(key, ((System.currentTimeMillis + expiration.toMillis) / 1000).toInt, value)).map(_ ⇒ {})
}
