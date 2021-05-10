package com.sbuslab.http.directives

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, _}

import akka.http.scaladsl.server.{Directive1, Route}

import com.sbuslab.http.Headers
import com.sbuslab.sbus.Context


trait SbusDirectives extends RateLimitDirectives {
  this: HandleErrorsDirectives ⇒

  def sbusContext: Directive1[Context] = {
    (extractClientIP & optionalHeaderValueByName("User-Agent")).tflatMap { case (ip, userAgent) ⇒
      val sbusCtx = Context(Map(
        "ip"        → ip.value,
        "userAgent" → userAgent.orNull
      ).filter(_._2 != null))

      optionalHeaderValueByName(Headers.CorrelationId).flatMap {
        case Some(corrId) ⇒ provide(sbusCtx.withCorrelationId(corrId))
        case _            ⇒ provide(sbusCtx)
      }
    }
  }

  def contextTimeout(timeout: Duration)(inner: Context ⇒ Route)(implicit context: Context) =
    withRequestTimeout(timeout.plus(1.second)) {
      inner(context.withTimeout(timeout))
    }

  @scala.annotation.tailrec
  final def wrap(field: String*)(f: Future[_])(implicit ec: ExecutionContext): Future[_] =
    if (field.nonEmpty) wrap(field.init: _*)(f.map(res ⇒ Map(field.last → res)))
    else f
}
