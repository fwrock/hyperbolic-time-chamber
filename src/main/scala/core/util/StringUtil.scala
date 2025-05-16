package org.interscity.htc
package core.util

object StringUtil {

  private val MODEL_PACKAGE_PREFIX = "org.interscity.htc.model."

  def getModelClassName(className: String): String = {
    val classNameWithoutPackage = className.replace(MODEL_PACKAGE_PREFIX, "")
    s"$MODEL_PACKAGE_PREFIX$classNameWithoutPackage"
  }

  def getModelClassNameWithoutPackage(className: String): String = {
    val classNameWithoutPackage = className.replace(MODEL_PACKAGE_PREFIX, "")
    classNameWithoutPackage
  }
}
