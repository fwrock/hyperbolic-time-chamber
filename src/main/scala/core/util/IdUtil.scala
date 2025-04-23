package org.interscity.htc
package core.util

object IdUtil {

  def format(id: String): String =
    id.replace(":", "_").replace(";", "_")
}
