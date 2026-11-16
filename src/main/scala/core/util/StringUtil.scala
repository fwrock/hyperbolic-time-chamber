package org.interscity.htc
package core.util

/** Normalizes model class labels using bounded, process-wide lookup caches. */
object StringUtil {

  private val MODEL_PACKAGE_PREFIX = "org.interscity.htc.model."

  /** These run on every message/dispatch (shard-region name, sender class label) with a small,
    * fixed set of class names, so the result is memoized. Bounded so an unexpected stream of
    * distinct names (e.g. ids passed by mistake) cannot grow the cache without limit.
    */
  private val MaxCachedNames = 4096
  private val modelNameCache = new java.util.concurrent.ConcurrentHashMap[String, String]()
  private val bareNameCache = new java.util.concurrent.ConcurrentHashMap[String, String]()

  /** Avoids repeated normalization while allowing uncached computation after the cache fills.
    *
    * The size guard is best-effort under concurrent insertion; callers must not rely on an exact
    * cap.
    */
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

  /** Adds one canonical model prefix after removing all occurrences of that prefix from the input.
    */
  def getModelClassName(className: String): String =
    memo(modelNameCache, className) {
      name =>
        val classNameWithoutPackage = name.replace(MODEL_PACKAGE_PREFIX, "")
        s"$MODEL_PACKAGE_PREFIX$classNameWithoutPackage"
    }

  /** Removes every model-package prefix occurrence, preserving other characters verbatim. */
  def getModelClassNameWithoutPackage(className: String): String =
    memo(bareNameCache, className)(_.replace(MODEL_PACKAGE_PREFIX, ""))
}
