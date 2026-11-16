package org.interscity.htc.core.cache

import java.io.{ DataInputStream, DataOutputStream }

/** Defines the schema and bounds of an immutable, deterministic cached calculation.
  *
  * Implementations must reject malformed or oversized input and must not close the supplied
  * streams. The caller owns stream lifetime and validates the cache envelope separately.
  */
trait CalculationCodec[A] {

  /** Decodes one value, throwing when the payload does not satisfy the codec's schema or bounds. */
  def read(input: DataInputStream): A

  /** Encodes one value using the schema associated with the caller's cache version. */
  def write(value: A, output: DataOutputStream): Unit
}
