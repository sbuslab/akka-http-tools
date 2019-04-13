package com.sbuslab.http.directives

import scala.reflect.ClassTag

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller, ToResponseMarshaller}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, FromRequestUnmarshaller, Unmarshaller}
import akka.util.ByteString
import com.fasterxml.jackson.databind.ObjectMapper

import com.sbuslab.model.BadRequestError
import com.sbuslab.utils.JsonFormatter
import com.sbuslab.utils.json.FacadeAnnotationIntrospector


object JsonMarshallers {
  private lazy val writerMapper: ObjectMapper = {
    val m = JsonFormatter.mapper.copy()
    m.setConfig(m.getSerializationConfig.withAppendedAnnotationIntrospector(new FacadeAnnotationIntrospector))
  }
}


trait JsonMarshallers extends Directives {

  import JsonMarshallers._

  private val jsonStringUnmarshaller =
    Unmarshaller.byteStringUnmarshaller
      .mapWithCharset {
        case (ByteString.empty, _) ⇒ throw Unmarshaller.NoContentException
        case (data, charset)       ⇒ data.decodeString(charset.nioCharset.name)
      }

  implicit protected def unmarshaller[A](implicit ct: ClassTag[A]): FromEntityUnmarshaller[A] =
    jsonStringUnmarshaller.map(data ⇒ JsonFormatter.mapper.readValue(data, ct.runtimeClass).asInstanceOf[A])

  implicit protected val JsonMarshaller: ToEntityMarshaller[Any] =
    Marshaller.opaque[Any, MessageEntity] { m ⇒
      HttpEntity.Strict(ContentTypes.`application/json`, ByteString(writerMapper.writeValueAsBytes(m)))
    }

  implicit protected val StatusCodeMarshaller: ToResponseMarshaller[StatusCode] =
    Marshaller.opaque[StatusCode, HttpResponse] { status ⇒
      HttpResponse(status = status, entity = if (status.allowsEntity()) HttpEntity(ContentTypes.`application/json`, "{}") else HttpEntity.Empty)
    }

  implicit protected val UnitMarshaller: ToEntityMarshaller[Unit] =
    Marshaller.opaque[Unit, MessageEntity](_ ⇒ HttpEntity.Empty)

  implicit protected val VoidMarshaller: ToEntityMarshaller[Void] =
    Marshaller.opaque[Void, MessageEntity](_ ⇒ HttpEntity.Empty)


  /**
   * Filter by Content-Type header
   */
  def contentType[T](contentType: String): Directive0 =
    extract(_.request.entity) flatMap {
      case e if e.contentType.value equalsIgnoreCase contentType ⇒ pass
      case _ ⇒ reject(UnsupportedRequestContentTypeRejection(Set(MediaType.custom(contentType, binary = false))))
    }

  override def entity[T](um: FromRequestUnmarshaller[T]): Directive1[T] =
    mapRejections(rs ⇒ if (rs.nonEmpty) throw new BadRequestError(rs.mkString("; ")) else rs).tflatMap { _ ⇒
      super.entity(um)
    }
}
