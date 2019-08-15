package com.sbuslab.http.directives

import scala.concurrent.Future

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.{Directives, Route}

import com.sbuslab.sbus.Context


trait AuthProvider[T] {
  def auth(request: HttpRequest)(implicit context: Context): Future[T]
}


trait AuthDirectives extends Directives {

  protected def auth[T](f: ⇒ Route)(implicit authProvider: AuthProvider[T], context: Context = Context.empty): Route =
    auth { _: T ⇒ f }

  protected def auth[T](inner: T ⇒ Route)(implicit authProvider: AuthProvider[T], context: Context = Context.empty): Route =
    extractRequest { req ⇒
      onSuccess(authProvider.auth(req))(inner)
    }
}
