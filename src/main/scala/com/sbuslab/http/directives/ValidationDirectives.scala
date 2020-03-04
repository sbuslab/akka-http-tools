package com.sbuslab.http.directives

import scala.collection.JavaConverters._

import akka.http.scaladsl.server.{Directive0, Directives}
import javax.validation.Validation

import com.sbuslab.model.BadRequestError
import com.sbuslab.utils.Logging


object ValidationDirectives {
  private val validator = Validation.buildDefaultValidatorFactory.getValidator
}


trait ValidationDirectives extends Directives with Logging {
  import ValidationDirectives._

  def validate[T](entity: T): Directive0 =
    doValidate(entity) match {
      case null ⇒ pass
      case e    ⇒ failWith(e)
    }

  def doValidate[T](entity: T): Exception = {
    val errors = validator.validate(entity)

    if (errors.size() != 0) {
      val msg = errors.asScala.map(e ⇒
        s"${e.getPropertyPath} in ${e.getRootBeanClass.getSimpleName} ${e.getMessage}"
      ).mkString("; \n")

      log.warn(s"BadRequestError: $msg for ${entity.toString.take(1024)}")

      new BadRequestError(msg, null, "validation-error")
    } else null
  }
}
