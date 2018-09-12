package com.sbuslab.http.directives

import scala.collection.JavaConverters._

import akka.http.scaladsl.server.{Directive0, Directives}
import javax.validation.Validation

import com.sbuslab.model.BadRequestError
import com.sbuslab.utils.Logging


trait ValidationDirectives extends Directives with Logging {

  private val validator = Validation.buildDefaultValidatorFactory.getValidator

  def validate[T](entity: T): Directive0 = {
    val errors = validator.validate(entity)

    if (errors.size() != 0) {
      val msg = errors.asScala.map(e ⇒
        s"${e.getPropertyPath} in ${e.getRootBeanClass.getSimpleName} ${e.getMessage}"
      ).mkString("; \n")

      log.warn(s"BadRequestError: $msg for ${entity.toString.take(1024)}")

      failWith(new BadRequestError(msg, null, "validation-error"))
    } else pass
  }
}
