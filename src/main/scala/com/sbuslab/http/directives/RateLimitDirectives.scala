package com.sbuslab.http.directives

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

import akka.http.scaladsl.model.{HttpMethods, HttpResponse, RemoteAddress, StatusCodes}
import akka.http.scaladsl.model.headers.{`Remote-Address`, `X-Forwarded-For`, `X-Real-Ip`}
import akka.http.scaladsl.server.{Directive1, Directives, ExceptionHandler, Route}

import com.sbuslab.http.{LimitExceeded, RateLimitProvider}
import com.sbuslab.model.TooManyRequestError
import com.sbuslab.utils.Logging


case class RateLimitOptions(
  passOptionsRequests: Boolean = false,
  isSuccessResult: Try[HttpResponse] ⇒ Boolean = {
    case Success(resp) ⇒ resp.status.isSuccess
    case Failure(_) ⇒ false
  }
)


trait RateLimitDirectives extends Directives with Logging {

  private val defaultRateLimitOptions = RateLimitOptions()

  def rateLimitIf(condition: ⇒ Boolean)(action: String, keys: (String, Any)*)(inner: ⇒ Route)(implicit rlp: RateLimitProvider, ec: ExecutionContext): Route =
    rateLimitIf(condition, defaultRateLimitOptions)(action, keys: _*)(inner)

  def rateLimitIf(condition: ⇒ Boolean, options: RateLimitOptions)(action: String, keys: (String, Any)*)(inner: ⇒ Route)(implicit rlp: RateLimitProvider, ec: ExecutionContext): Route =
    if (condition) {
      rateLimit(action, options, keys: _*)(inner)
    } else {
      inner
    }

  def rateLimit(action: String, keys: (String, Any)*)(inner: ⇒ Route)(implicit rlp: RateLimitProvider, ec: ExecutionContext): Route =
    rateLimit(action, defaultRateLimitOptions, keys: _*)(inner)

  def rateLimit(action: String, options: RateLimitOptions, keys: (String, Any)*)(inner: ⇒ Route)(implicit rlp: RateLimitProvider, ec: ExecutionContext): Route =
    extractClientIP { ip ⇒
      val allKeys = collectAllKeys(ip, keys)

      onComplete(rlp.check(action, allKeys)) {
        case Success(LimitExceeded) ⇒
          log.trace(s"Rate limited $action for ${allKeys.mkString(", ")}")
          throw new TooManyRequestError("Too many invalid requests. Rate limit exceeded!")

        case _ ⇒
          incrementCounter(rlp, action, options, allKeys, inner)
      }
    }

  override def extractClientIP: Directive1[RemoteAddress] =
    headerValuePF { case `X-Forwarded-For`(addresses: Seq[_]) => addresses.last } |
      headerValuePF { case `Remote-Address`(address) => address } |
      headerValuePF { case `X-Real-Ip`(address) => address } |
      provide(RemoteAddress.Unknown)

  private def incrementCounter(rlp: RateLimitProvider, action: String, options: RateLimitOptions, allKeys: Seq[(String, String)], inner: ⇒ Route): Route =
    extract(_.request.method) {
      case HttpMethods.OPTIONS ⇒
        if (options.passOptionsRequests) inner
        else complete(StatusCodes.OK, "")

      case _ ⇒
        (handleExceptions(ExceptionHandler { case e ⇒
          rlp.increment(
            success = options.isSuccessResult(Failure(e)),
            action,
            allKeys
          )
          complete(e)
        }) &
        mapResponse({ resp ⇒
          rlp.increment(
            success = options.isSuccessResult(Success(resp)),
            action,
            allKeys
          )
          resp
        }))(inner)
    }

  private def collectAllKeys(ip: RemoteAddress, keys: Seq[(String, Any)]) = {
    (Map("ip" → ip.value) ++ keys collect {
      case (k, v) if v != null && v != None ⇒ k → v.toString
    }).toSeq
  }
}
