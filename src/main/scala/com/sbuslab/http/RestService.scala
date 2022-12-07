package com.sbuslab.http

import java.io.StringWriter
import java.io.InputStream
import java.security.{KeyStore, SecureRandom}
import java.util.UUID
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.LoggingMagnet
import akka.stream.Materializer
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports
import org.slf4j.MDC

import com.sbuslab.http.directives._
import com.sbuslab.model.{ErrorMessage, NotFoundError}
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


class RestService(conf: Config)(implicit system: ActorSystem, ec: ExecutionContext, mat: Materializer) extends AllCustomDirectives {

  implicit val timeout = Timeout(10.seconds)
  
  private val corsSettings = CorsSettings(system)

  private val logBody               = conf.hasPath("log-body") && conf.getBoolean("log-body")
  private val ssoEnabled            = conf.hasPath("ssl.enabled") && conf.getBoolean("ssl.enabled")
  private val robotsTxt             = if (conf.hasPath("robots-txt")) conf.getString("robots-txt") else "User-agent: *\nDisallow: /"
  private val internalNetworkPrefix = if (conf.hasPath("internal-network-prefix")) conf.getString("internal-network-prefix") else "unknown"
  private val globalPathPrefix      = if (conf.hasPath("path-prefix")) pathPrefix(conf.getString("path-prefix")) else pass


  def start(initRoutes: ⇒ Route) {
    try {
      DefaultExports.initialize()
      
      val serverBuilder = Http().newServerAt(conf.getString("interface"), conf.getInt("port"))
      val serverBuilderWithProtocol = if (ssoEnabled) serverBuilder.enableHttps(createHttpsContext()) else serverBuilder
      
      serverBuilderWithProtocol.bindFlow(
        startWithDirectives {
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
        }
      ) onComplete { outcome ⇒
        val listening = s"${if (ssoEnabled) "https" else "http"}:${conf.getString("interface")}:${conf.getInt("port")}"
      
        outcome match {
          case Success(_) ⇒
            log.info(s"Server is listening on $listening")

          case Failure(e) ⇒
            log.error(s"Error on bind server to $listening", e)
            sys.exit(1)
        }
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

  private def createHttpsContext(): HttpsConnectionContext = {
    val password = conf.getString("ssl.keystore-pass").toCharArray

    val ks = KeyStore.getInstance("PKCS12")
    val keystore = getClass.getClassLoader.getResourceAsStream(conf.getString("ssl.keystore-path"))

    require(keystore != null, "Keystore required!")
    ks.load(keystore, password)

    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)

    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    ConnectionContext.httpsServer(sslContext)
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
          respondWithDefaultHeaders(RawHeader("Cache-Control", "no-cache, no-store, must-revalidate")) {
            withRequestTimeoutResponse(_ ⇒ {
              HttpResponse(status = StatusCodes.GatewayTimeout, entity = HttpEntity(ContentTypes.`application/json`, DefaultErrorFormatter.apply(new ErrorMessage(504, "Request timeout"))))
            }) {
              inner
            }
          }
        }
      }
    }

  private def commonRoutesWrap(inner: Route): Route =
    withJsonMediaTypeIfNotExists {
      mapRequest(_.withDefaultHeaders(RawHeader(Headers.CorrelationId, UUID.randomUUID().toString))) {
        mapResponseHeaders(_.filterNot(_.name == ErrorHandlerHeader)) {
          logRequestResult(LoggingMagnet(_ ⇒ accessLogger(System.currentTimeMillis)(_))) {
            handleErrors(DefaultErrorFormatter) {
              pathSuffix(Slash.?) {
                path("metrics") {
                  get {
                    extractClientIP { ip ⇒
                      if (ip.value.startsWith(internalNetworkPrefix)) {
                        completeWith(Marshaller.StringMarshaller) { complete ⇒
                          val writer = new StringWriter()
                          TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
                          complete(writer.toString)
                        }
                      } else {
                        failWith(new NotFoundError("The requested resource could not be found."))
                      }
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
    MDC.remove("http_x_forwarded_for")
  }

  private def withJsonMediaTypeIfNotExists: Directive0 =
    mapResponseEntity {
      case en if en.contentType == ContentTypes.`text/plain(UTF-8)` ⇒
        en.withContentType(ContentTypes.`application/json`)

      case en ⇒ en
    }
}
