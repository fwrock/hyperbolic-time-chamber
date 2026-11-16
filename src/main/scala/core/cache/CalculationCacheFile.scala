package org.interscity.htc.core.cache

import java.io.{ BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream }
import java.nio.file.{ AtomicMoveNotSupportedException, Files, Path, StandardCopyOption }
import java.util.zip.{ GZIPInputStream, GZIPOutputStream }
import scala.util.Using
import scala.util.control.NonFatal

/** Owns the validated gzip envelope and atomic replacement of one calculation entry.
  *
  * Temporary files stay beside their destination so atomic moves can be used on supported file
  * systems. The envelope and payload format remain independent of the facade's selection policy.
  */
private[cache] final class CalculationCacheFile(
  val path: Path,
  id: String,
  version: Int,
  key: String
) {

  /** Returns a validated payload, treating missing, corrupt or bypassed entries as cache misses. */
  def read[A](codec: CalculationCodec[A], refresh: Boolean): Option[A] = {
    if (refresh || !Files.isRegularFile(path)) return None
    try {
      val value = readPayload(codec)
      println(s"[CalculationCache] hit $id: $path (${Files.size(path)} bytes)")
      Some(value)
    } catch {
      case NonFatal(error) =>
        System.err.println(s"[CalculationCache] ignoring invalid $path: ${error.getMessage}")
        None
    }
  }

  /** Validates identity before decoding and consumes EOF to validate gzip CRC and truncation. */
  private def readPayload[A](codec: CalculationCodec[A]): A =
    Using.resource(Files.newInputStream(path)) {
      file =>
        Using.resource(new DataInputStream(new BufferedInputStream(new GZIPInputStream(file)))) {
          in =>
            require(
              in.readUTF() == "HTC-CALC" && in.readInt() == version && in.readUTF() == id && in
                .readUTF() == key,
              "cache header mismatch"
            )
            val value = codec.read(in)
            require(in.read() == -1, "unexpected cache payload")
            value
        }
    }

  /** Attempts replacement without allowing storage or temporary-file cleanup failures to escape. */
  def store[A](value: A, codec: CalculationCodec[A]): Unit = {
    var temporary: Option[Path] = None
    try {
      Files.createDirectories(path.getParent)
      val staging = Files.createTempFile(path.getParent, s".$id-", ".tmp")
      temporary = Some(staging)
      writePayload(staging, value, codec)
      replace(staging)
      println(s"[CalculationCache] stored $id: $path (${Files.size(path)} bytes)")
    } catch {
      case NonFatal(error) =>
        System.err.println(s"[CalculationCache] cannot store $path: ${error.getMessage}")
    } finally temporary.foreach(removeTemporary)
  }

  /** Closes the compressed stream before its temporary file can replace the destination. */
  private def writePayload[A](temporary: Path, value: A, codec: CalculationCodec[A]): Unit =
    Using.resource(Files.newOutputStream(temporary)) {
      file =>
        Using.resource(new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(file)))) {
          out =>
            out.writeUTF("HTC-CALC")
            out.writeInt(version)
            out.writeUTF(id)
            out.writeUTF(key)
            codec.write(value, out)
        }
    }

  /** Falls back to a regular replacement only when the file system rejects atomic moves. */
  private def replace(temporary: Path): Unit =
    try
      Files.move(
        temporary,
        path,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    catch {
      case _: AtomicMoveNotSupportedException =>
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }

  /** Makes cleanup best-effort even when an earlier write or move failed. */
  private def removeTemporary(temporary: Path): Unit =
    try { Files.deleteIfExists(temporary); () }
    catch { case NonFatal(_) => () }
}
