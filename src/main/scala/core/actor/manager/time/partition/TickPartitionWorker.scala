package org.interscity.htc
package core.actor.manager.time.partition

import core.runtime.accounting.RuntimeFailure
import core.runtime.conservative.*
import core.runtime.conservative.partition.*
import org.apache.pekko.actor.{ Actor, ActorRef, Props }
import scala.collection.mutable
import scala.util.control.NonFatal

/** Owns one private schedule partition and its root/cascade ledger. No model code runs here. */
class TickPartitionWorker(dispatcher: ActorRef, maxInFlight: Int) extends Actor {
  require(maxInFlight > 0, "partition dispatch limit must be positive")
  private val scheduled = mutable.TreeMap.empty[Long, mutable.LinkedHashMap[Long, TickWake]]
  private val stored = mutable.Map.empty[(Long, Long), TickWake]
  private val waiting = mutable.Set.empty[(Long, Long)]
  private val slots = mutable.Set.empty[DirectWorkId]
  private val roots = mutable.Set.empty[DirectWorkId]
  private val ledger = new DirectWorkLedger
  private var epoch = ""
  private var nodes = Set.empty[ActorRef]
  private var tick: Option[Long] = None
  private var active: Option[Long] = None
  private var failed = false

  /** Unexpected restart loses obligations and must fail the enclosing run. */
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    dispatcher ! RuntimeFailure(s"partition restart is unsupported: ${self.path}")
    super.preRestart(reason, message)
  }

  /** Process bounded claims and immutable receipts under this worker's mailbox ownership. */
  override def receive: Receive = {
    case _ if failed => ()
    case message =>
      try handle(message)
      catch {
        case NonFatal(error) =>
          failed = true
          dispatcher ! RuntimeFailure(s"partition ${self.path}: ${error.getMessage}")
      }
  }

  private def check(run: String): Unit =
    require(epoch.nonEmpty && run == epoch, "wrong partition epoch")

  private def handle(message: Any): Unit = message match {
    case ConfigurePartition(run, members) =>
      require(
        sender() == dispatcher && epoch.isEmpty && members.contains(dispatcher),
        "invalid partition configuration"
      )
      epoch = run
      nodes = members.toSet
    case StoreWake(run, wake) =>
      check(run)
      require(sender() == dispatcher && tick.forall(_ <= wake.tick), "invalid partition schedule")
      val key = wake.identity.id -> wake.tick
      stored.get(key) match {
        case Some(previous) => require(previous == wake, "conflicting stored wake")
        case None =>
          stored(key) = wake
          scheduled.getOrElseUpdate(wake.tick, mutable.LinkedHashMap.empty)(wake.identity.id) = wake
      }
      dispatcher ! WakeStored(epoch, wake)
      claim()
    case DrainPartition(run, token, next) =>
      check(run)
      require(sender() == dispatcher && tick.forall(_ <= next), "invalid partition grant")
      if (!tick.contains(next)) {
        require(
          waiting.isEmpty && slots.isEmpty && roots.isEmpty && ledger.isClosed,
          "partition advance before settlement"
        )
        ledger.begin(next)
        tick = Some(next)
        stored.filterInPlace(
          (key, _) => key._2 >= next
        )
      }
      active = Some(next)
      claim()
      dispatcher ! PartitionDrained(epoch, token)
    case StartPartitionRoot(run, target, delivery, wake) =>
      check(run)
      require(
        sender() == dispatcher && delivery.accountingOwner == self && delivery.epoch == epoch,
        "invalid partition root authority"
      )
      if (!tick.contains(delivery.work.tick)) {
        require(
          ledger.isClosed && roots.isEmpty && waiting.isEmpty,
          "overlapping partition root ticks"
        )
        ledger.begin(delivery.work.tick)
        tick = Some(delivery.work.tick)
      }
      ledger.enroll(delivery.work)
      roots.add(delivery.work.id)
      slots.add(delivery.work.id)
      // Bootstrap roots are not schedule claims. Ordinary roots release their matching claim.
      wake.foreach(
        value =>
          require(waiting.remove(value.identity.id -> value.tick), "root without a claimed wake")
      )
      target.tell(delivery, dispatcher)
    case DirectAccounted(run, completion) =>
      check(run)
      require(
        nodes.contains(sender()) && tick.contains(completion.work.tick),
        "foreign partition receipt"
      )
      ledger.complete(completion)
      slots.remove(completion.work.id)
      claim()
      if (ledger.isClosed && roots.nonEmpty) {
        dispatcher ! PartitionRootsSettled(epoch, roots.toVector)
        roots.clear()
      }
    case failure: RuntimeFailure => failed = true
    case other =>
      throw new IllegalArgumentException(
        s"unsupported partition message: ${other.getClass.getName}"
      )
  }

  private def claim(): Unit = active.foreach {
    current =>
      scheduled.get(current).foreach {
        bucket =>
          while (bucket.nonEmpty && slots.size + waiting.size < maxInFlight) {
            val (id, wake) = bucket.head
            bucket.remove(id)
            waiting.add(id -> current)
            dispatcher ! WakeClaimed(epoch, wake)
          }
          if (bucket.isEmpty) scheduled.remove(current)
      }
  }
}

object TickPartitionWorker {

  /** Class-based construction keeps worker creation independent of captured actor closures. */
  def props(dispatcher: ActorRef, maxInFlight: Int): Props =
    Props(classOf[TickPartitionWorker], dispatcher, Int.box(maxInFlight))
}
