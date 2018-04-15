package com.sbuslab.http.directives

import scala.util.control.NonFatal

import akka.http.scaladsl.server.{Directive, Directives, ExceptionHandler}
import io.prometheus.client.Histogram


object MetricsDirectives {
  private val responseTimes = Histogram.build()
    .name("http_request_processing_seconds")
    .help("Time spent processing request")
    .labelNames("endpoint")
    .register()
}


trait MetricsDirectives extends Directives {

  def metrics(endpoint: String): Directive[Unit] =
    extractRequestContext.flatMap { _ ⇒
      val timer = MetricsDirectives.responseTimes.labels(endpoint).startTimer()

      mapResponse { resp ⇒
        timer.observeDuration()
        resp
      } & handleExceptions(ExceptionHandler {
        case NonFatal(e) ⇒
          timer.observeDuration()
          throw e
      })
    }
}
