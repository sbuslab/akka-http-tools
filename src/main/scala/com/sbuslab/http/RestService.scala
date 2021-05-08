package com.sbuslab.http

import java.io.StringWriter
import java.util.UUID
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.LoggingMagnet
import akka.stream.ActorMaterializer
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports
import org.slf4j.MDC

import com.sbuslab.http.directives._
import com.sbuslab.model.ErrorMessage
import com.sbuslab.utils.Logging


trait AllCustomDirectives
  extends Directives
    with RateLimitDirectives
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
  implicit val defaultTimeout = Timeout(10.seconds)
}


class RestService(conf: Config)(implicit system: ActorSystem, ec: ExecutionContext, mat: ActorMaterializer) extends AllCustomDirectives {

  implicit val timeout = Timeout(10.seconds)

  private val logBody      = if (conf.hasPath("log-body")) conf.getBoolean("log-body") else false
  private val robotsTxt    = if (conf.hasPath("robots-txt")) conf.getString("robots-txt") else "User-agent: *\nDisallow: /"
  private val corsSettings = CorsSettings(system)


  def start(initRoutes: ⇒ Route) {
    try {
      DefaultExports.initialize()

      Http().bindAndHandle(startWithDirectives {
        pathEndOrSingleSlash {
          method(CustomMethods.PING) {
            complete(Map("value" → "pong"))
          }
        } ~
        path("version") {
          get {
            complete(Map(
              "service"  → System.getenv("SERVICE_NAME"),
              "version"  → System.getenv("SERVICE_VERSION"),
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

  private def startWithDirectives(inner: Route): Route =
    commonRoutesWrap {
      cors(corsSettings) {
        handleErrors(DefaultErrorFormatter) {
          pathEnd {
            extractRequest { initRequest ⇒
              handleWebSocketMessages {
                handleWebsocketRequest(initRequest, {
                  commonRoutesWrap(inner)
                })
              }
            }
          } ~
          withJsonMediaTypeIfNotExists {
            respondWithHeaders(RawHeader("Cache-Control", "no-cache, no-store, must-revalidate")) {
              withRequestTimeoutResponse(_ ⇒ {
                HttpResponse(status = StatusCodes.GatewayTimeout, entity = HttpEntity(ContentTypes.`application/json`, DefaultErrorFormatter.apply(new ErrorMessage(504, "Request timeout"))))
              }) {
                inner
              }
            }
          }
        }
      }
    }

  private def commonRoutesWrap(inner: Route): Route =
    mapRequest(_.withDefaultHeaders(RawHeader(Headers.CorrelationId, UUID.randomUUID().toString))) {
      mapResponseHeaders(_.filterNot(_.name == ErrorHandlerHeader)) {
        logRequestResult(LoggingMagnet(_ ⇒ accessLogger(System.currentTimeMillis)(_))) {
          handleErrors(DefaultErrorFormatter) {
            pathSuffix(Slash.?) {
              path("metrics") {
                get {
                  completeWith(Marshaller.StringMarshaller) { complete ⇒
                    val writer = new StringWriter()
                    TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
                    complete(writer.toString)
                  }
                }
              } ~
              path("robots.txt") {
                get {
                  completeWith(Marshaller.StringMarshaller) { complete ⇒
                    complete(robotsTxt)
                  }
                }
              } ~
              globalPathPrefix {
                inner
              }
            }
          }
        }
      }
    }

  private val globalPathPrefix =
    if (conf.hasPath("path-prefix")) {
      pathPrefix(conf.getString("path-prefix"))
    } else {
      pass
    }

  private def stringifySource(data: HttpEntity): String =
    if (data.isStrict()) {
      try Await.result(data.toStrict(1.second), 1.second).data.utf8String.trim.take(4096) catch {
        case e: Throwable ⇒ "Error on stringify body: " + e.getMessage
      }
    } else "Couldn't stringify body: non strict data"

  private def accessLogger(start: Long)(request: HttpRequest)(result: Any): Unit = {
    val requestBody =
      if (logBody) {
        "\n" + request.headers.mkString("\n") +
        "\n" + stringifySource(request.entity)
      } else ""

    request.getHeader(Headers.CorrelationId) ifPresent { corrId ⇒
      MDC.put("correlation_id", corrId.value())
    }

    request.getHeader("X-Forwarded-For") ifPresent { ip ⇒
      MDC.put("http_x_forwarded_for", ip.value())
    }

    result match {
      case RouteResult.Complete(response) ⇒
        def msg =
          s"${request.method.value} ${request.uri.toRelative} " +
          s"""${request.entity.contentType.mediaType match {
            case MediaTypes.`application/json` ⇒ ""
            case mt if mt.mainType == "none" ⇒ ""
            case mt ⇒ s"$mt "
          }}""" +
          s"<--- ${response.status} ${System.currentTimeMillis - start} ms" +
          (if (logBody) s"$requestBody \n\n${stringifySource(response.entity)}" else "")

        if (response.status.isSuccess || response.status.intValue == 404 || response.status.intValue == 429) {
          log.info(msg)
        } else {
          log.warn(msg)
        }

      case RouteResult.Rejected(reason) ⇒
        log.warn(s"${request.method.value} ${request.uri.toRelative} ${request.entity} <--- rejected: ${reason.mkString(",")} ${System.currentTimeMillis - start} ms$requestBody")
    }

    MDC.remove("correlation_id")
  }

  private def withJsonMediaTypeIfNotExists: Directive0 =
    mapResponseEntity {
      case en if en.contentType == ContentTypes.`text/plain(UTF-8)` ⇒
        en.withContentType(ContentTypes.`application/json`)

      case en ⇒ en
    }
}
