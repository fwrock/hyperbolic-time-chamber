package org.interscity.htc
package core.util

import core.entity.event.EntityEnvelopeEvent
import org.apache.pekko.actor.{ Actor, ActorRef, ActorSystem, Props, Terminated }
import org.apache.pekko.cluster.sharding.ShardRegion

import java.net.{ URLDecoder, URLEncoder }
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Single-node stand-in for a cluster shard region: one parent actor per model type that spawns
  * entities on first contact and speaks the same protocol (StartEntity + ack, envelope unwrapping),
  * so every caller holding a region ActorRef works unchanged.
  */
object LocalRegions {
  private val regions = new ConcurrentHashMap[(ActorSystem, String), ActorRef]()
  private val indexes = new ConcurrentHashMap[(ActorSystem, String), ConcurrentHashMap[String, ActorRef]]()
  private val modes = new ConcurrentHashMap[ActorSystem, java.lang.Boolean]()

  def enabled(system: ActorSystem): Boolean =
    modes.computeIfAbsent(system, s => RuntimeMode.from(s.settings.config) == RuntimeMode.SingleNode)

  def getOrCreate(system: ActorSystem, typeName: String, entityProps: => Props): ActorRef =
    regions.computeIfAbsent(
      (system, typeName),
      _ => {
        val index = indexes.computeIfAbsent((system, typeName), _ => new ConcurrentHashMap[String, ActorRef]())
        system.actorOf(Props(new LocalRegion(entityProps, index)), s"local-region-${encode(typeName)}")
      }
    )

  /** Live entities of a region by raw entity id, or null before the region exists. A sender that
    * finds its target here may tell it directly: an entity is published only after the region has
    * forwarded the message that spawned it, so a sender's later direct message cannot overtake an
    * earlier one it routed through the region.
    */
  def entityIndex(system: ActorSystem, typeName: String): ConcurrentHashMap[String, ActorRef] =
    indexes.get((system, typeName))

  def get(system: ActorSystem, typeName: String): ActorRef =
    Option(regions.get((system, typeName))).getOrElse(
      throw new IllegalStateException(s"No local region started for type '$typeName'")
    )

  private[util] def encode(id: String): String = URLEncoder.encode(id, StandardCharsets.UTF_8)

  private val LocalShardId = "local"

  private class LocalRegion(entityProps: Props, index: ConcurrentHashMap[String, ActorRef]) extends Actor {
    private def entity(id: String): ActorRef = {
      val name = encode(id)
      context.child(name).getOrElse(context.watch(context.actorOf(entityProps, name)))
    }

    override def receive: Receive = {
      case start: ShardRegion.StartEntity =>
        val target = entity(start.entityId)
        target.tell(start, sender())
        index.putIfAbsent(start.entityId, target)
        sender() ! ShardRegion.StartEntityAck(start.entityId, LocalShardId)
      case EntityEnvelopeEvent(id, payload) =>
        val target = entity(id)
        target.forward(payload)
        index.putIfAbsent(id, target)
      case Terminated(ref) =>
        index.remove(URLDecoder.decode(ref.path.name, StandardCharsets.UTF_8), ref)
      case ShardRegion.Passivate(stopMessage) =>
        sender() ! stopMessage
    }
  }
}
