package org.interscity.htc.core.cache

import org.interscity.htc.core.entity.configuration.CalculationCacheConfig
import java.io.*
import java.nio.file.{Files, Path, StandardCopyOption, AtomicMoveNotSupportedException}
import java.security.{DigestOutputStream, MessageDigest}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import scala.util.Using
import scala.util.control.NonFatal

/** Codecs own their schema and bounds. Only immutable, deterministic calculations belong here. */
trait CalculationCodec[A] {
  def read(input: DataInputStream): A
  def write(value: A, output: DataOutputStream): Unit
}

object CalculationCache {
  @volatile private var configuration: Option[CalculationCacheConfig] = None
  def configure(value: Option[CalculationCacheConfig]): Unit = {
    configuration = value
    value.foreach(settings => println(s"[CalculationCache] configured directory=${Path.of(settings.directory).toAbsolutePath.normalize()} " +
      s"entries=${settings.entries.mkString(",")} refresh=${settings.refresh}"))
  }
  def configured: Option[CalculationCacheConfig] = configuration

  def fingerprint(write: DataOutputStream => Unit): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    Using.resource(new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))))(write)
    digest.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  def getOrCompute[A](config: Option[CalculationCacheConfig], id: String, version: Int, key: String,
                      codec: CalculationCodec[A])(compute: => A): A = {
    val enabled = config.filter(_.entries.contains(id))
    if (enabled.isEmpty) return compute
    require(id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}") && key.matches("[a-f0-9]{64}"))
    val settings = enabled.get
    val path = Path.of(settings.directory).resolve(s"$id-v$version-$key.htcc.gz")
    if (!settings.refresh && Files.isRegularFile(path)) {
      try {
        val result = Using.resource(Files.newInputStream(path)) { file =>
          Using.resource(new DataInputStream(new BufferedInputStream(new GZIPInputStream(file)))) { in =>
            require(in.readUTF() == "HTC-CALC" && in.readInt() == version && in.readUTF() == id && in.readUTF() == key,
              "cache header mismatch")
            val value = codec.read(in)
            require(in.read() == -1, "unexpected cache payload") // also validates gzip CRC/truncation
            value
          }
        }
        println(s"[CalculationCache] hit $id: $path (${Files.size(path)} bytes)")
        return result
      } catch { case NonFatal(error) =>
        System.err.println(s"[CalculationCache] ignoring invalid $path: ${error.getMessage}")
      }
    }
    val reason = if (settings.refresh) "refresh requested" else "file not found or invalid"
    println(s"[CalculationCache] miss $id: $path ($reason); computing")
    val value = compute
    var temporary: Path = null
    try {
      Files.createDirectories(path.getParent)
      temporary = Files.createTempFile(path.getParent, s".$id-", ".tmp")
      Using.resource(Files.newOutputStream(temporary)) { file =>
        Using.resource(new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(file)))) { out =>
          out.writeUTF("HTC-CALC"); out.writeInt(version); out.writeUTF(id); out.writeUTF(key)
          codec.write(value, out)
        }
      }
      try Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      catch { case _: AtomicMoveNotSupportedException => Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
      println(s"[CalculationCache] stored $id: $path (${Files.size(path)} bytes)")
    } catch { case NonFatal(error) =>
      System.err.println(s"[CalculationCache] cannot store $path: ${error.getMessage}")
    } finally {
      if (temporary != null) try Files.deleteIfExists(temporary) catch { case NonFatal(_) => () }
    }
    value
  }
}
