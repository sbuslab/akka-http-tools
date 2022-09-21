package com.sbuslab.http

import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

import com.sbuslab.http.ratelimit.RateLimitStorage
import com.typesafe.config.{Config, ConfigUtil}
import io.prometheus.client.Counter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import com.sbuslab.utils.{Digest, JsonFormatter, Logging}


sealed trait CheckLimitResult
case object LimitExceeded extends CheckLimitResult
case object NotExceeded extends CheckLimitResult


trait RateLimitProvider {
  def check(action: String, keys: Seq[(String, String)]): Future[CheckLimitResult]
  def increment(success: Boolean, action: String, keys: Seq[(String, String)]): Unit
}

object RateLimitProvider {
  val noop = new RateLimitProvider {
    private val notExceeded = Future.successful(NotExceeded)
    def increment(success: Boolean, action: String, keys: Seq[(String, String)]) = {}
    def check(action: String, keys: Seq[(String, String)]) = notExceeded
  }
}


@Lazy
@Service
@Autowired
class RateLimitService(config: Config, storage: RateLimitStorage)(implicit ec: ExecutionContext) extends RateLimitProvider with Logging  {

  case class CounterConfig(max: Int, ttl: Duration, lockTimeout: Duration, clearOnSuccess: Boolean)

  val FailResult = "failure"
  val SuccResult = "success"
  val Exceeded   = "exceeded"

  private val conf          = config.getConfig("sbuslab.rate-limit")
  private val counters      = conf.getConfig("counters")
  private val defaultConfig = conf.getConfig("default-counter")

  private val excludes: Map[String, Set[String]] =
    conf.getObject("excludes").keySet().asScala.map({ key ⇒
      key → conf.getStringList("excludes." + key).asScala.toSet
    }).toMap

  private val configCache = new ConcurrentHashMap[String, Option[CounterConfig]]()


  /**
   * Check rate limit for given action and keys
   * return: current status (is exceeded): overflow or not
   */
  def check(action: String, keys: Seq[(String, String)]): Future[CheckLimitResult] = {
    val hashedKeys = for {
      (keyName, keyValue) ← filterExcludes(keys)
      resultType          ← Seq(FailResult, SuccResult)
      _                   ← findConfig(action, resultType, keyName)
    } yield makeKey(action, resultType, keyName, keyValue)

    storage.get(hashedKeys) map { valuesMap ⇒
      if (valuesMap.values.exists(_ == Exceeded)) LimitExceeded else NotExceeded
    }
  }

  /**
   * Increment counter for given action and keys
   * Reset failure counter on success result if it specified in config file (clear-on-success = true)
   */
  def increment(success: Boolean, action: String, keys: Seq[(String, String)]): Unit = {
    filterExcludes(keys) foreach { case (keyName, keyValue) ⇒
      if (success && findConfig(action, FailResult, keyName).exists(_.clearOnSuccess)) {
          log.trace(s"Reset rate limit for: $action, $FailResult, $keyName, $keyValue")
          storage.delete(makeKey(action, FailResult, keyName, keyValue))
      } else {
        val resultType = if (success) SuccResult else FailResult

        findConfig(action, resultType, keyName) foreach { cntConfig ⇒
          val cntKey = makeKey(action, resultType, keyName, keyValue)
          storage.increment(cntKey, cntConfig.ttl) foreach { cnt ⇒
            log.trace(s"Incremented rate limit for: $action, $resultType, $keyName, $keyValue = $cnt")

            if (cnt >= cntConfig.max) {
              log.info(s"Rate limit exceeded for $action (${if (success) "success" else "failure"}) by $keyName=$keyValue! Keys: $keys. Memcache: $cntKey, Config: ${JsonFormatter.serialize(cntConfig)}")

              RateLimitMetrics.rateLimitExceededCount
                .labels(action)
                .inc()

              storage.set(cntKey, cntConfig.lockTimeout, Exceeded)
            }
          }
        }
      }
    }
  }

  /**
   * Get counter config for given action and keys
   */
  private def findConfig(action: String, resultType: String, keyName: String): Option[CounterConfig] =
    configCache.computeIfAbsent(action + resultType + keyName, { _ ⇒
      counters.optionConfig(action, "_")
        .flatMap(a ⇒ a.optionConfig(resultType, "_"))
        .flatMap(rsl ⇒ rsl.optionConfig(keyName, "_").map(_.withFallback(defaultConfig)))
        .map { cfg ⇒
          CounterConfig(
            max            = cfg.getInt("max"),
            ttl            = cfg.getDuration("per"),
            lockTimeout    = cfg.getDuration("lock-timeout"),
            clearOnSuccess = cfg.getBoolean("clear-on-success")
          )
        }
    })

  private def filterExcludes(keys: Seq[(String, String)]) =
    keys filterNot { case (key, value) ⇒
      excludes.get(key).exists(_.contains(value))
    }

  private implicit class OptionConfig(cfg: Config) {
    def optionConfig(key: String, default: String = null): Option[Config] = {
      val escapedKey = ConfigUtil.quoteString(key)
      val optCfg = if (cfg.hasPath(escapedKey)) Some(cfg.getConfig(escapedKey)) else None

      if (default != null) {
        optCfg.orElse(cfg.optionConfig(default))
      } else {
        optCfg
      }
    }
  }

  implicit def asFiniteDuration(d: java.time.Duration): Duration =
    scala.concurrent.duration.Duration.fromNanos(d.toNanos)

  private def makeKey(parts: Any*) =
    "ratelimit-" + Digest.md5(parts.map(_.toString.trim.toLowerCase).mkString("-"))
}



object RateLimitMetrics {

  val rateLimitExceededCount = Counter.build()
    .name("rate_limit_exceeded_count")
    .help("Rate limit exceeded count")
    .labelNames("action")
    .register()
}
