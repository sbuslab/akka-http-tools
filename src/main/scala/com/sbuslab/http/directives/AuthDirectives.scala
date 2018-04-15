package com.sbuslab.http.directives

import scala.concurrent.Future

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.{Directives, Route}


trait AuthProvider[T] {
  def auth(request: HttpRequest): Future[T]
}


trait AuthDirectives extends Directives {

  protected def auth[T](f: ⇒ Route)(implicit authProvider: AuthProvider[T]): Route =
    auth { _: T ⇒ f }

  protected def auth[T](inner: T ⇒ Route)(implicit authProvider: AuthProvider[T]): Route =
    extractRequest { req ⇒
      onSuccess(authProvider.auth(req))(inner)
    }
}
