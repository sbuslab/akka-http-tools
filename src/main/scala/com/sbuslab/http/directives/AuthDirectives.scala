package com.sbuslab.http.directives

import scala.concurrent.Future

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.server.{Directives, Route}

import com.sbuslab.sbus.Context


trait AuthProvider[T] {
  def auth(request: HttpRequest)(implicit context: Context): Future[T]
}


trait AuthDirectives extends Directives {

  protected def auth[T](f: ⇒ Route)(implicit authProvider: AuthProvider[T], context: Context): Route =
    auth { _: T ⇒ f }

  protected def auth[T](inner: T ⇒ Route)(implicit authProvider: AuthProvider[T], context: Context): Route =
    extractRequest { req ⇒
      onSuccess(authProvider.auth(req))(inner)
    }

  protected def optionalAuth[T](inner: Option[T] ⇒ Route)(implicit authProvider: AuthProvider[T], context: Context): Route =
    optionalHeaderValueByType[Authorization]() {
      case Some(_) ⇒
        auth { user: T ⇒
          inner(Option(user))
        }

      case _ ⇒
        inner(None)
    }
}
