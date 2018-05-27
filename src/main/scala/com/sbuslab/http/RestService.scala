package com.sbuslab.http

import java.io.StringWriter
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.event.Logging.{InfoLevel, WarningLevel}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.model.{ContentTypes, HttpRequest, MediaTypes}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.{LogEntry, LoggingMagnet}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives
import com.typesafe.config.Config
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports
import org.slf4j.MDC

import com.sbuslab.http.directives._
import com.sbuslab.utils.Logging


trait AllCustomDirectives
  extends Directives
    with WebsocketHandlerDirective
    with HandleErrorsDirectives
    with MetricsDirectives
    with JsonMarshallers
    with ValidationDirectives
    with AuthDirectives
    with SbusDirectives
    with CorsDirectives
    with Logging {
}


trait RestRoutes extends AllCustomDirectives {
  implicit val defaultTimeout = Timeout(10 seconds)
}


class RestService(conf: Config)(implicit system: ActorSystem, ec: ExecutionContext, mat: ActorMaterializer) extends AllCustomDirectives {

  implicit val timeout = Timeout(10 seconds)

  def start(initRoutes: ⇒ Route) {
    try {
      DefaultExports.initialize()

      Http().bindAndHandle(startWithDirectives {
        pathEndOrSingleSlash {
          method(CustomMethods.PING) {
            complete(Map("value" → "pong"))
          }
        } ~
        path("metrics") {
          get {
            completeWith(Marshaller.StringMarshaller) { complete ⇒
              val writer = new StringWriter()
              TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
              complete(writer.toString)
            }
          }
        } ~
        path("version") {
          get {
            complete(Map(
              "service" → System.getenv("SERVICE_NAME"),
              "version" → System.getenv("SERVICE_VERSION"),
              "deployed" → System.getenv("SERVICE_DEPLOY_TIME"),
            ))
          }
        } ~
        initRoutes
      }, conf.getString("interface"), conf.getInt("port")) onComplete {
        case Success(_) ⇒
          log.info(s"Server is listening on ${conf.getString("interface")}:${conf.getInt("port")}")

        case Failure(e) ⇒
          log.error(s"Error on bind server to ${conf.getString("interface")}:${conf.getInt("port")}", e)
          sys.exit(1)
      }
    } catch {
      case e: Throwable ⇒
        log.error(s"Error on initialize http server!", e)
        sys.exit(1)
    }

    scala.sys.addShutdownHook {
      log.info("Terminating...")
      system.terminate()

      Await.result(system.whenTerminated, 30.seconds)
      log.info("Terminated... Bye")
    }
  }

  private def startWithDirectives(initRoutes: Route): Route =
    cors() {
      withJsonMediaTypeIfNotExists {
        mapResponseHeaders(_.filterNot(_.name == ErrorHandlerHeader)) {
          logRequestResult(LoggingMagnet(log ⇒ accessLogger(log, System.currentTimeMillis)(_))) {
            handleErrors(DefaultErrorFormatter) {
              pathSuffix(Slash.?) {
                pathEndOrSingleSlash {
                  get {
                    handleWebSocketMessages {
                      handleWebsocketRequest {
                        startWithDirectives(initRoutes)
                      }
                    }
                  }
                } ~
                initRoutes
              }
            }
          }
        }
      }
    }

  private def accessLogger(log: LoggingAdapter, start: Long)(req: HttpRequest)(res: Any): Unit = {
    val entry = res match {
      case RouteResult.Complete(resp) ⇒
        LogEntry(
          s"${req.method.value} ${req.uri.toRelative} "
            + s"""${req.entity.contentType.mediaType match {
              case MediaTypes.`application/json` ⇒ ""
              case mt if mt.mainType == "none" ⇒ ""
              case mt ⇒ s"$mt "
            }}"""
            + s"<--- ${resp.status} ${System.currentTimeMillis - start} ms",
          if (resp.status.isSuccess || resp.status.intValue == 404) InfoLevel else WarningLevel
        )

      case RouteResult.Rejected(reason) ⇒
        LogEntry(
          s"${req.method.value} ${req.uri.toRelative} ${req.entity} <--- rejected: ${reason.mkString(",")} ${System.currentTimeMillis - start} ms",
          WarningLevel
        )
    }

    req.getHeader(Headers.CorrelationId) ifPresent { corrId ⇒
      MDC.put("correlation_id", corrId.value())
    }

    entry.logTo(log)
  }

  private def withJsonMediaTypeIfNotExists: Directive0 =
    mapResponseEntity {
      case en if en.contentType == ContentTypes.`text/plain(UTF-8)` ⇒
        en.withContentType(ContentTypes.`application/json`)

      case en ⇒ en
    }
}
