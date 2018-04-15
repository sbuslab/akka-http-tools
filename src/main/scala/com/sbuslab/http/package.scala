package com.sbuslab

import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.RequestEntityAcceptance.Tolerated


package object http {

  object Headers {
    val CorrelationId = "Correlation-Id"
    val ConnectionHandlerRef = "X-Client-Connection-Ref"
  }

  object CustomMethods {
    val PING      = HttpMethod.custom(name = "PING", safe = true, idempotent = true, requestEntityAcceptance = Tolerated)
    val SUBSCRIBE = HttpMethod.custom(name = "SUBSCRIBE", safe = true, idempotent = true, requestEntityAcceptance = Tolerated)
    val GENERATE  = HttpMethod.custom(name = "GENERATE", safe = false, idempotent = false, requestEntityAcceptance = Tolerated)

    private val all = Set(SUBSCRIBE, PING, GENERATE).map(m ⇒ m.value → m).toMap

    def getForKey(m: String) = all.get(m)
  }
}
