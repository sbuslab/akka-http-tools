package com.sbuslab.http.directives

import scala.concurrent.{ExecutionContext, Future}

import akka.http.scaladsl.server.Directive1

import com.sbuslab.http.Headers
import com.sbuslab.sbus.Context


trait SbusDirectives extends RateLimitDirectives {

  def sbusContext: Directive1[Context] = {
    (extractClientIP & optionalHeaderValueByName("User-Agent")).tflatMap { case (ip, userAgent) ⇒
      val sbusCtx = Context(Map(
        "ip"        → ip.value,
        "userAgent" → userAgent.orNull
      ))

      optionalHeaderValueByName(Headers.CorrelationId).flatMap {
        case Some(corrId) ⇒ provide(sbusCtx.withCorrelationId(corrId))
        case _            ⇒ provide(sbusCtx)
      }
    }
  }

  @scala.annotation.tailrec
  final def wrap(field: String*)(f: Future[_])(implicit ec: ExecutionContext): Future[_] =
    if (field.nonEmpty) wrap(field.init: _*)(f.map(res ⇒ Map(field.last → res)))
    else f
}
