package org.interscity.htc
package core.util

import com.fasterxml.jackson.core.io.JsonStringEncoder

/** Fast path for the per-row report payload: a flat map of strings, numbers, booleans and nulls.
  *
  * The output is byte-identical to `JsonUtil.toJson`: same key order (the map's own iteration
  * order), Jackson's string escaping, `Double.toString`/`Float.toString` numbers, and non-finite
  * doubles quoted. Anything else (nested values, options, non-String keys, other number types)
  * returns null and the caller falls back to Jackson, so the fast path never changes meaning.
  */
private[util] object ReportJson {
  private val encoder = JsonStringEncoder.getInstance()

  def flatMapToJson(data: Any): String = data match {
    case map: scala.collection.Map[?, ?] =>
      val out = new java.lang.StringBuilder(32 + map.size * 24)
      out.append('{')
      val it = map.iterator
      var first = true
      while (it.hasNext) {
        val (key, value) = it.next()
        key match {
          case k: String =>
            if (!first) out.append(',')
            first = false
            appendString(out, k)
            out.append(':')
            if (!appendValue(out, value)) return null
          case _ => return null
        }
      }
      out.append('}').toString
    case _ => null
  }

  private def appendValue(out: java.lang.StringBuilder, value: Any): Boolean = value match {
    case null                => out.append("null"); true
    case s: String           => appendString(out, s); true
    case l: java.lang.Long   => out.append(l.longValue); true
    case i: java.lang.Integer => out.append(i.intValue); true
    case b: java.lang.Boolean => out.append(if (b.booleanValue) "true" else "false"); true
    case d: java.lang.Double =>
      val v = d.doubleValue
      if (java.lang.Double.isNaN(v) || java.lang.Double.isInfinite(v)) appendString(out, java.lang.Double.toString(v))
      else out.append(java.lang.Double.toString(v))
      true
    case f: java.lang.Float =>
      val v = f.floatValue
      if (java.lang.Float.isNaN(v) || java.lang.Float.isInfinite(v)) appendString(out, java.lang.Float.toString(v))
      else out.append(java.lang.Float.toString(v))
      true
    case s: java.lang.Short => out.append(s.shortValue.toInt); true
    case b: java.lang.Byte  => out.append(b.byteValue.toInt); true
    case _                  => false
  }

  private def appendString(out: java.lang.StringBuilder, s: String): Unit = {
    out.append('"')
    var i = 0
    val n = s.length
    var plain = true
    while (plain && i < n) {
      val c = s.charAt(i)
      if (c < 0x20 || c == '"' || c == '\\') plain = false else i += 1
    }
    if (plain) out.append(s) else out.append(encoder.quoteAsString(s))
    out.append('"')
  }
}
