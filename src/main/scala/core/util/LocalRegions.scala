package org.interscity.htc
package core.util

import core.entity.event.EntityEnvelopeEvent
import org.apache.pekko.actor.{ Actor, ActorRef, ActorSystem, Props }
import org.apache.pekko.cluster.sharding.ShardRegion

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Single-node stand-in for a cluster shard region: one parent actor per model type that spawns
  * entities on first contact and speaks the same protocol (StartEntity + ack, envelope unwrapping),
  * so every caller holding a region ActorRef works unchanged.
  */
object LocalRegions {
  private val regions = new ConcurrentHashMap[(ActorSystem, String), ActorRef]()

  def getOrCreate(system: ActorSystem, typeName: String, entityProps: => Props): ActorRef =
    regions.computeIfAbsent(
      (system, typeName),
      _ => system.actorOf(Props(new LocalRegion(entityProps)), s"local-region-${encode(typeName)}")
    )

  def get(system: ActorSystem, typeName: String): ActorRef =
    Option(regions.get((system, typeName))).getOrElse(
      throw new IllegalStateException(s"No local region started for type '$typeName'")
    )

  private[util] def encode(id: String): String = URLEncoder.encode(id, StandardCharsets.UTF_8)

  private val LocalShardId = "local"

  private class LocalRegion(entityProps: Props) extends Actor {
    private def entity(id: String): ActorRef = {
      val name = encode(id)
      context.child(name).getOrElse(context.actorOf(entityProps, name))
    }

    override def receive: Receive = {
      case start: ShardRegion.StartEntity =>
        entity(start.entityId).tell(start, sender())
        sender() ! ShardRegion.StartEntityAck(start.entityId, LocalShardId)
      case EntityEnvelopeEvent(id, payload) =>
        entity(id).forward(payload)
    }
  }
}
