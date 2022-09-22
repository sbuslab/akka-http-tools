package com.sbuslab.http.ratelimit

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

import io.lettuce.core.ScriptOutputType
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

import com.sbuslab.utils.condition.ConditionalOnConfig


@Lazy
@Component
@ConditionalOnConfig(
  name = Array("sbuslab.rate-limit.storage"),
  havingValue = "redis"
)
class RedisRateLimitStorage(redisClient: StatefulRedisClusterConnection[String, String])(implicit ec: ExecutionContext) extends RateLimitStorage {

  private val IncrementLuaScript =
    """
      |local current
      |current = redis.call("incr",KEYS[1])
      |if current == 1 then
      |    redis.call("expire",KEYS[1],ARGV[1])
      |end
      |return current
      |""".stripMargin

  override def get(keys: Seq[String]): Future[Map[String, AnyRef]] =
    Future.sequence(keys.map(key ⇒ redisClient.async.get(key).toScala.map(key → _)))
      .map(seq ⇒ seq.filter(_._2 != null).toMap)

  override def delete(key: String): Future[Unit] =
    redisClient.async
      .del(key)
      .toScala
      .map(_ ⇒ {})

  override def increment(key: String, expiration: Duration): Future[Long] =
    redisClient.async
      .eval(IncrementLuaScript, ScriptOutputType.INTEGER, Array(key), expiration.toSeconds.toString)
      .toScala

  override def set(key: String, expiration: Duration, value: Any): Future[Unit] =
    redisClient.async()
      .setex(key, expiration.toSeconds, value.toString)
      .toScala
      .map(_ ⇒ {})
}
