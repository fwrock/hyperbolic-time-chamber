package org.interscity.htc
package core.actor.manager.loadbalance.migration

import scala.collection.mutable

/** Transactional message buffer for shards in transit.
  *
  * During shard migration (hand-off from Node A to Node B), messages targeted at the migrating
  * shard are held in this buffer. Once the shard is re-hydrated on the new node, buffered messages
  * are replayed in order.
  *
  * IMPORTANT: This buffer stores INTER-ACTOR MESSAGES (not actor state). Actor state is persisted
  * separately by Pekko's shard persistence mechanism. With the proper coordination protocol
  * (TimeManager pauses at tick boundary → migration executes → TimeManager resumes), the buffer
  * should rarely contain messages. It serves as a safety net for edge cases where in-flight
  * messages arrive during the brief migration window (e.g., async responses from other actors
  * that were already dispatched before the pause took effect).
  *
  * Thread-safe: synchronized access to internal structures.
  */
class MessageBuffer(
  val maxBufferSize: Int = 10000
) {

  /** Buffered messages per shard, ordered by arrival time */
  private val buffers: mutable.Map[String, mutable.Queue[BufferedMessage]] = mutable.Map.empty

  /** Set of shards currently being buffered (in transit) */
  private val activeBuffers: mutable.Set[String] = mutable.Set.empty

  /** Starts buffering messages for a shard (shard is entering migration). */
  def startBuffering(shardId: String): Unit = synchronized {
    activeBuffers.add(shardId)
    buffers.getOrElseUpdate(shardId, mutable.Queue.empty)
  }

  /** Buffers a message for a shard in transit.
    *
    * @return
    *   true if the message was buffered, false if shard is not in transit or buffer is full
    */
  def buffer(shardId: String, message: Any, senderId: String = ""): Boolean = synchronized {
    if (!activeBuffers.contains(shardId)) return false

    val queue = buffers.getOrElseUpdate(shardId, mutable.Queue.empty)
    if (queue.size >= maxBufferSize) {
      false
    } else {
      queue.enqueue(BufferedMessage(message, senderId, System.nanoTime()))
      true
    }
  }

  /** Stops buffering and returns all buffered messages for replay.
    *
    * @return
    *   All buffered messages in arrival order, or empty list if not buffering
    */
  def drainBuffer(shardId: String): List[BufferedMessage] = synchronized {
    activeBuffers.remove(shardId)
    buffers.remove(shardId).map(_.toList).getOrElse(Nil)
  }

  /** Checks if a shard is currently being buffered. */
  def isBuffering(shardId: String): Boolean = synchronized {
    activeBuffers.contains(shardId)
  }

  /** Gets the number of buffered messages for a shard. */
  def bufferedCount(shardId: String): Int = synchronized {
    buffers.get(shardId).map(_.size).getOrElse(0)
  }

  /** Gets total buffered messages across all shards. */
  def totalBufferedCount: Int = synchronized {
    buffers.values.map(_.size).sum
  }

  /** Aborts buffering for a shard (migration failed), returns messages for retry. */
  def abortBuffering(shardId: String): List[BufferedMessage] = synchronized {
    drainBuffer(shardId)
  }

  /** Clears all buffers. */
  def clearAll(): Unit = synchronized {
    activeBuffers.clear()
    buffers.clear()
  }
}

/** A message held in the buffer during shard migration.
  *
  * @param message
  *   The original message
  * @param senderId
  *   ID of the sender (for replay routing)
  * @param timestamp
  *   When the message was buffered
  */
case class BufferedMessage(
  message: Any,
  senderId: String,
  timestamp: Long
)
