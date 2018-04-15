package com.sbuslab.http.directives

import javax.validation.Validation
import scala.collection.JavaConverters._

import akka.http.scaladsl.server.{Directive0, Directives}

import com.sbuslab.model.BadRequestError


trait ValidationDirectives extends Directives {

  private val validator = Validation.buildDefaultValidatorFactory.getValidator

  def validate[T](entity: T): Directive0 = {
    val errors = validator.validate(entity)

    if (errors.size() != 0) {
      failWith(new BadRequestError(errors.asScala.map(e ⇒
        s"${e.getPropertyPath} in ${e.getRootBeanClass.getSimpleName} ${e.getMessage}"
      ).mkString("; \n"), null, "validation-error"))
    } else pass
  }
}
