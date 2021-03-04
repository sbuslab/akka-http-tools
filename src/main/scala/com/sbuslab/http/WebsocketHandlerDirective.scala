package com.sbuslab.http

import java.util.UUID
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage}
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.NotUsed
import akka.util.ByteString
import com.fasterxml.jackson.annotation.{JsonProperty, JsonRawValue}

import com.sbuslab.http.directives.HandleErrorsDirectives
import com.sbuslab.model.{BadRequestError, ErrorMessage}
import com.sbuslab.utils.{JsonFormatter, Logging}


case class WsRequestWithCorrelationId(headers: Option[WsCorrelationIdHeader])
case class WsCorrelationIdHeader(@JsonProperty("Correlation-Id") correlationId: Option[String])

case class WsResponse(
  status: Int,
  headers: Map[String, String] = Map.empty,
  @JsonRawValue body: String
)

case class RegisterTerminationCallback(f: () ⇒ Unit)


case class Subscription(connection: ActorRef, correlationId: Option[String], method: HttpMethod) {
  def send(status: Int, body: Any, headers: Map[String, String] = Map.empty) {
    connection ! WsResponse(status, headers + (Headers.CorrelationId → correlationId.getOrElse("")), JsonFormatter.serialize(body))
  }

  def onClose(f: ⇒ Unit) {
    connection ! RegisterTerminationCallback(() ⇒ f)
  }
}


trait WebsocketHandlerDirective extends Directives with JsonFormatter with Logging {

  def handleWebsocketRequest(initRequest: HttpRequest, routes: Route)(implicit system: ActorSystem, ec: ExecutionContext, mat: ActorMaterializer): Flow[Any, TextMessage.Strict, (NotUsed, Unit)] = {
    val wrappedRoutes = optionalHeaderValueByName(Headers.CorrelationId) { corrId ⇒
      respondWithHeaders(corrId.map(cid ⇒ RawHeader(Headers.CorrelationId, cid)).toList: _*) {
        routes
      }
    }

    val sinkActorRef = system.actorOf(Props(new WsRequestHandler(wrappedRoutes, initRequest)), "ws-handler-" + UUID.randomUUID().toString)
    val sink = Sink.actorRef(sinkActorRef, PoisonPill)

    val source = Source.actorRef[WsResponse](Int.MaxValue, OverflowStrategy.fail) mapMaterializedValue { clientConnection ⇒
      sinkActorRef ! clientConnection
    } map { response: WsResponse ⇒
      TextMessage(serialize(response))
    } recover {
      case NonFatal(e) ⇒
        TextMessage(serialize(Map("error" → e.getMessage)))
    }

    Flow.fromSinkAndSourceCoupledMat(sink, source)(Keep.both)
  }

  def subscribe(inner: Subscription ⇒ Route)(implicit system: ActorSystem): Route =
    (method(CustomMethods.SUBSCRIBE) | method(CustomMethods.UNSUBSCRIBE)) {
      extractMethod { method ⇒
        headerValueByName(Headers.ConnectionHandlerRef) { connRef ⇒
          optionalHeaderValueByName(Headers.CorrelationId) { corrId ⇒
            onSuccess(system.actorSelection(connRef).resolveOne(213.millis)) { connActorRef ⇒
              inner(Subscription(connActorRef, corrId, method))
            }
          }
        }
      }
    }
}


class WsRequestHandler(routes: Route, initRequest: HttpRequest)(implicit ec: ExecutionContext, mat: ActorMaterializer) extends Actor with HandleErrorsDirectives {

  private val defaultHeaders: List[akka.http.scaladsl.model.HttpHeader] = List(
    Seq(RawHeader(Headers.ConnectionHandlerRef, self.path.toString)),
    initRequest.headers[akka.http.javadsl.model.headers.Cookie],
    initRequest.headers[akka.http.javadsl.model.headers.UserAgent],
    initRequest.headers[akka.http.javadsl.model.headers.XForwardedFor]
  ).flatten

  override def receive: Receive = {
    case clientConnection: ActorRef ⇒
      context.become(handleRequests(clientConnection))
  }

  override def postStop(): Unit = {
    log.debug(s"Connection is closed, stop WsRequestHandler ${self.path.toString}...")
    terminationCallbacks foreach { f ⇒ f() }
  }

  private val jsonSymbols = Set("{", "[", "\"")

  private var terminationCallbacks = List.empty[() ⇒ Unit]

  val queue = Source.queue(256, OverflowStrategy.backpressure)
    .via(routes)
    .mapAsyncUnordered(parallelism = 32)({ resp ⇒
      resp.entity.dataBytes.runWith(Sink.head).map(_.utf8String) map { body ⇒
        WsResponse(
          headers = resp.headers.map(h ⇒ h.name() → h.value()).toMap,
          status  = resp.status.intValue(),
          body    = if (jsonSymbols.contains(body.take(1))) body else serialize(body)
        )
      }
    })
    .to(Sink.actorRef(self, PoisonPill))
    .run()

  def handleRequests(clientConnection: ActorRef): Receive = {
    case TextMessage.Streamed(stream) ⇒
      stream.limit(1000).completionTimeout(5.seconds).runFold("")(_ + _) onComplete {
        case Success(message) ⇒
          handleMessage(message)

        case Failure(e) ⇒
          log.error("Error on handle streamed ws message: " + e.getMessage, e)
          self ! WsResponse(status = 400, body = serialize(Map("error" → "bad-request", "message" → e.getMessage)))
      }

    case TextMessage.Strict(message) ⇒
      handleMessage(message)

    case BinaryMessage.Streamed(stream) ⇒
      stream.limit(1000).completionTimeout(5.seconds).runFold(ByteString.empty)(_ ++ _) onComplete {
        case Success(message) ⇒
          handleMessage(message.utf8String)

        case Failure(e) ⇒
          log.error("Error on handle streamed ws message: " + e.getMessage, e)
          self ! WsResponse(status = 400, body = serialize(Map("error" → "bad-request", "message" → e.getMessage)))
      }

    case BinaryMessage.Strict(message) ⇒
      handleMessage(message.utf8String)

    case response: WsResponse ⇒
      log.trace("<--- {}", response.toString.take(1024))
      clientConnection ! response

    case RegisterTerminationCallback(f) ⇒
      terminationCallbacks ::= f

    case Failure(e) ⇒
      log.info(s"Received: $e")
      self ! PoisonPill

    case other ⇒
      log.error(s"Receive unexpected message: ${other.getClass.getName} $other")
  }

  private def handleMessage(message: String) {
    try {
      val request = JsonFormatter.mapper.readTree(message)
      val method = request.path("method").asText("get").toUpperCase

      val headers = request.path("headers").fields().asScala.toList flatMap { entry ⇒
        HttpHeader.parse(entry.getKey, entry.getValue.asText()) match {
          case HttpHeader.ParsingResult.Ok(h, _) ⇒ Some(h)

          case HttpHeader.ParsingResult.Error(err) ⇒
            log.warn(s"Error parse response header: $err")
            None
        }
      }

      queue.offer(HttpRequest(
        method = HttpMethods.getForKey(method).orElse(CustomMethods.getForKey(method))
          .getOrElse(throw new ErrorMessage(StatusCodes.MethodNotAllowed.intValue, s"Unsupported method: $method")),

        uri = Uri(request.path("uri").asText("/")),
        headers = defaultHeaders ::: headers,

        entity = HttpEntity(
          headers.find(_.is("content-type"))
            .map(h ⇒ ContentType(MediaType.applicationWithFixedCharset(h.value().stripPrefix("application/"), HttpCharsets.`UTF-8`)))
            .getOrElse(ContentTypes.`application/json`),
          request.path("body").toString
        )
      ))
    } catch {
      case NonFatal(e) ⇒
        log.warn(s"Error on deserialize WS request ${message.take(1024)}: " + e.getMessage, e)
        val corrId = Try(deserialize[WsRequestWithCorrelationId](message).headers.flatMap(_.correlationId)).toOption.flatten

        self ! WsResponse(
          status  = 400,
          headers = corrId.map(cid ⇒ Map(Headers.CorrelationId → cid)).getOrElse(Map.empty),
          body    = DefaultErrorFormatter(new BadRequestError("WS deserialization error: " + e.getMessage, e))
        )
    }
  }
}
