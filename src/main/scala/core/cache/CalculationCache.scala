package org.interscity.htc.core.cache

import org.interscity.htc.core.entity.configuration.CalculationCacheConfig

import java.io.{ BufferedOutputStream, DataOutputStream, OutputStream }
import java.nio.file.Path
import java.security.{ DigestOutputStream, MessageDigest }
import scala.util.Using

/** Selects reusable calculation results without making disk availability a model dependency.
  *
  * Only immutable, deterministic calculations belong here. Callers supply all input identity
  * through the fingerprint and advance the version whenever the codec schema changes.
  */
object CalculationCache {
  @volatile private var configuration: Option[CalculationCacheConfig] = None

  /** Publishes process-wide settings for subsequent callers; existing operations retain theirs. */
  def configure(value: Option[CalculationCacheConfig]): Unit = {
    configuration = value
    value.foreach(
      settings =>
        println(
          s"[CalculationCache] configured directory=${Path.of(settings.directory).toAbsolutePath.normalize()} " +
            s"entries=${settings.entries.mkString(",")} refresh=${settings.refresh}"
        )
    )
  }

  /** Returns the current process-wide selection, or `None` when caching is disabled. */
  def configured: Option[CalculationCacheConfig] = configuration

  /** Hashes the caller's deterministic binary input encoding with SHA-256.
    *
    * The callback must encode every input affecting the calculation. Encoding failures propagate.
    */
  def fingerprint(write: DataOutputStream => Unit): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    Using.resource(
      new DataOutputStream(
        new BufferedOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))
      )
    )(write)
    digest
      .digest()
      .map(
        b => f"${b & 0xff}%02x"
      )
      .mkString
  }

  /** Reuses a valid selected entry, otherwise computes and attempts to store its replacement.
    *
    * Disabled entries evaluate `compute` directly. Refresh bypasses reads. Read and storage
    * failures are diagnostic only; calculation failures propagate and are never cached.
    *
    * @param id
    *   selected cache name, restricted to a safe filename component when enabled
    * @param version
    *   codec schema version, included in both the filename and envelope
    * @param key
    *   lowercase SHA-256 input fingerprint
    */
  def getOrCompute[A](
    config: Option[CalculationCacheConfig],
    id: String,
    version: Int,
    key: String,
    codec: CalculationCodec[A]
  )(compute: => A): A = config.filter(_.entries.contains(id)) match {
    case None => compute
    case Some(settings) =>
      require(id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}") && key.matches("[a-f0-9]{64}"))
      val file = CalculationCacheFile(
        Path.of(settings.directory).resolve(s"$id-v$version-$key.htcc.gz"),
        id,
        version,
        key
      )
      file.read(codec, settings.refresh).getOrElse {
        val reason = if (settings.refresh) "refresh requested" else "file not found or invalid"
        println(s"[CalculationCache] miss $id: ${file.path} ($reason); computing")
        val value = compute
        file.store(value, codec)
        value
      }
  }
}
