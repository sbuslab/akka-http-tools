package com.sbuslab.http.ratelimit

import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait RateLimitStorage {
  def get(keys: Seq[String]): Future[Map[String, AnyRef]]

  def delete(key: String): Future[Unit]

  def increment(key: String, expiration: Duration): Future[Long]

  def set(key: String, expiration: Duration, value: Any): Future[Unit]
}

object RateLimitStorage {
  final val ConfigKey = "sbuslab.rate-limit.storage"
  final val MemcachedStorage = "memcached"
  final val RedisStorage = "redis"
}