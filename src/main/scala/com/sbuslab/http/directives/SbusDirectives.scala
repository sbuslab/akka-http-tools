package com.sbuslab.http.directives

import akka.http.scaladsl.server.{Directive1, Directives}

import com.sbuslab.http.Headers
import com.sbuslab.model.Context


trait SbusDirectives extends Directives {

  def sbusContext: Directive1[Context] = {
    optionalHeaderValueByName(Headers.CorrelationId).flatMap {
      case Some(corrId) ⇒ provide(Context.withCorrelationId(corrId))
      case _            ⇒ provide(Context.empty)
    }
  }
}
