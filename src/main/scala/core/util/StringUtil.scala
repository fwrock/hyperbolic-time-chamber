package org.interscity.htc
package core.util

object StringUtil {

  private val MODEL_PACKAGE_PREFIX = "org.interscity.htc.model."

  /** These run on every message/dispatch (shard-region name, sender class label) with a small, fixed
    * set of class names, so the result is memoized. Bounded so an unexpected stream of distinct
    * names (e.g. ids passed by mistake) cannot grow the cache without limit.
    */
  private val MaxCachedNames = 4096
  private val modelNameCache = new java.util.concurrent.ConcurrentHashMap[String, String]()
  private val bareNameCache = new java.util.concurrent.ConcurrentHashMap[String, String]()

  private def memo(cache: java.util.concurrent.ConcurrentHashMap[String, String], key: String)(
    compute: String => String
  ): String = {
    val hit = cache.get(key)
    if (hit != null) hit
    else {
      val value = compute(key)
      if (cache.size < MaxCachedNames) cache.put(key, value)
      value
    }
  }

  def getModelClassName(className: String): String =
    memo(modelNameCache, className) { name =>
      val classNameWithoutPackage = name.replace(MODEL_PACKAGE_PREFIX, "")
      s"$MODEL_PACKAGE_PREFIX$classNameWithoutPackage"
    }

  def getModelClassNameWithoutPackage(className: String): String =
    memo(bareNameCache, className)(_.replace(MODEL_PACKAGE_PREFIX, ""))
}
