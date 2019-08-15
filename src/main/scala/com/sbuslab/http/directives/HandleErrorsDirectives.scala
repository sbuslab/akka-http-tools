package com.sbuslab.http.directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server._
import ch.megard.akka.http.cors.scaladsl.CorsRejection
import com.fasterxml.jackson.core.JsonProcessingException

import com.sbuslab.model._
import com.sbuslab.utils.{JsonFormatter, Logging}


trait HandleErrorsDirectives extends Directives with JsonFormatter with Logging {

  protected val ErrorHandlerHeader = "X-Errors-Handled"
  protected val RateLimitRemainingHeader = "X-Rate-Limit-Remaining"

  object ErrorFormatter {
    def apply(f: ErrorFormatter) = f
  }

  type ErrorFormatter = PartialFunction[Any, String]

  def DefaultErrorFormatter: ErrorFormatter = {
    case e: ErrorMessage ⇒
      serialize(Map(
        "error"     → dasherize(Option(e.error).getOrElse(StatusCode.int2StatusCode(e.code).reason)),
        "message"   → e.getMessage,
        "_links"    → e._links,
        "_embedded" → e._embedded,
      ))

    case e: Throwable ⇒
      serialize(Map(
        "error"   → "internal-error",
        "message" → e.getMessage,
      ))

    case HttpResponse(status, headers, ent: HttpEntity.Strict, _) ⇒
      if (!headers.exists(_.name == ErrorHandlerHeader)) {
        serialize(Map("error" → dasherize(status.reason), "message" → ent.data.utf8String))
      } else {
        ent.data.utf8String
      }
  }

  def handleErrors(formatter: ErrorFormatter): Directive0 =
    handleExceptions(customExceptionHandler(formatter)) &
    handleRejections(customRejectionHandler) &
    handleRejections({
      case Seq(r: CorsRejection) ⇒ Some(complete(StatusCodes.Forbidden, formatter.applyOrElse(new ForbiddenError(r.cause.toString), DefaultErrorFormatter)))
      case _                     ⇒ None
    }) &
    respondWithDefaultHeader(RawHeader(ErrorHandlerHeader, "1"))

  private def customExceptionHandler(formatter: ErrorFormatter) =
    ExceptionHandler {
      case e: ErrorMessage ⇒
        log.debug(e.getMessage, e)
        complete(StatusCodes.getForKey(e.code).getOrElse(StatusCodes.custom(e.code, e.getMessage)), formatter.applyOrElse(e, DefaultErrorFormatter))

      case e: scala.concurrent.TimeoutException ⇒
        log.debug(e.getMessage, e)
        complete(StatusCodes.GatewayTimeout, formatter.applyOrElse(new ErrorMessage(504, "Request timed out, please try again later.", error = "timeout", cause = e), DefaultErrorFormatter))

      case e: akka.pattern.CircuitBreakerOpenException ⇒
        log.debug(e.getMessage, e)
        complete(StatusCodes.GatewayTimeout, formatter.applyOrElse(new ErrorMessage(504, "Request timed out, please try again later.", error = "timeout", cause = e), DefaultErrorFormatter))

      case e: Throwable if e.getCause != null && e.getCause.isInstanceOf[IllegalArgumentException] ⇒
        log.debug(e.getMessage, e)
        complete(StatusCodes.BadRequest, formatter.applyOrElse(new ErrorMessage(400, e.getCause.getMessage, e), DefaultErrorFormatter))

      case e @ (_: IllegalArgumentException | _: JsonProcessingException | _: IllegalUriException) ⇒
        log.debug(e.getMessage, e)
        complete(StatusCodes.BadRequest, formatter.applyOrElse(new BadRequestError(e.getMessage, e), DefaultErrorFormatter))

      case e: IllegalStateException ⇒
        log.debug(e.getMessage, e)
        complete(StatusCodes.Conflict, formatter.applyOrElse(new ConflictError(e.getMessage, e), DefaultErrorFormatter))

      case e: java.io.FileNotFoundException ⇒
        complete(StatusCodes.NotFound, formatter.applyOrElse(new NotFoundError(e.getMessage, e), DefaultErrorFormatter))

      case e: java.io.IOException ⇒
        log.error("Service unavailable: " + e, e)
        complete(StatusCodes.ServiceUnavailable, formatter.applyOrElse(new ServiceUnavailableError(e.getMessage, e), DefaultErrorFormatter))

      case e: Throwable ⇒
        log.error("Internal error: " + e, e)
        complete(StatusCodes.InternalServerError, formatter.applyOrElse(e, DefaultErrorFormatter))
    }

  private def customRejectionHandler =
    RejectionHandler.default
      .mapRejectionResponse {
        case res @ HttpResponse(_, _, _: HttpEntity.Strict, _) ⇒
          res.copy(entity = HttpEntity(ContentTypes.`application/json`, DefaultErrorFormatter.apply(res)))

        case x ⇒ x
      }

  private def dasherize(s: String) = s.replaceAll("\\W+", "-").toLowerCase
}
