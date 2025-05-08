package org.interscity.htc
package core.util

object IdUtil {

  def format(id: String): String =
    if (id != null) id.replace(":", "_").replace(";", "_") else null
}
