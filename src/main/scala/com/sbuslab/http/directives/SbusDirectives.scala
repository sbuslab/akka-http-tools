package com.sbuslab.http.directives

import scala.concurrent.{ExecutionContext, Future}

import akka.http.scaladsl.server.{Directive1, Directives}

import com.sbuslab.http.Headers
import com.sbuslab.sbus.Context


trait SbusDirectives extends Directives {

  def sbusContext: Directive1[Context] = {
    optionalHeaderValueByName(Headers.CorrelationId).flatMap {
      case Some(corrId) ⇒ provide(Context.withCorrelationId(corrId))
      case _            ⇒ provide(Context.empty)
    }
  }

  def wrap[T](name: String)(f: Future[T])(implicit ec: ExecutionContext) =
    f.map(result ⇒ Map(name → result))
}
