package com.sbuslab.http.directives

import scala.reflect.ClassTag

import akka.http.javadsl.marshallers.jackson.Jackson
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Directive0, Directives, UnsupportedRequestContentTypeRejection}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.util.ByteString

import com.sbuslab.utils.JsonFormatter


trait JsonMarshallers extends Directives {

  private val jsonStringUnmarshaller =
    Unmarshaller.byteStringUnmarshaller
      .mapWithCharset {
        case (ByteString.empty, _) ⇒ throw Unmarshaller.NoContentException
        case (data, charset)       ⇒ data.decodeString(charset.nioCharset.name)
      }

  implicit def unmarshaller[A](implicit ct: ClassTag[A]): FromEntityUnmarshaller[A] =
    jsonStringUnmarshaller.map(data ⇒ JsonFormatter.mapper.readValue(data, ct.runtimeClass).asInstanceOf[A])

  implicit def marshaller[Any]: ToEntityMarshaller[Any] =
    Jackson.marshaller[Any](JsonFormatter.mapper)

  implicit protected val UnitMarshaller: ToEntityMarshaller[Unit] =
    Marshaller.opaque[Unit, MessageEntity](_ ⇒ HttpEntity.Empty)

  implicit protected val VoidMarshaller: ToEntityMarshaller[Void] =
    Marshaller.opaque[Void, MessageEntity](_ ⇒ HttpEntity.Empty)

  def contentType[T](contentType: String): Directive0 =
    extract(_.request.entity) flatMap {
      case e if e.contentType.value equalsIgnoreCase contentType ⇒ pass
      case _ ⇒ reject(UnsupportedRequestContentTypeRejection(Set(MediaType.custom(contentType, binary = false))))
    }
}
