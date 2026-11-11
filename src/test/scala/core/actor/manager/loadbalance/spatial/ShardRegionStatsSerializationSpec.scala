package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.sharding.ShardRegion
import org.apache.pekko.serialization.{ SerializationExtension, Serializers }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ShardRegionStatsSerializationSpec extends AnyFlatSpec with Matchers {
  "Shard statistics" should "fit the unchanged frame limit when the equivalent entity inventory exceeds it" in {
    val config = ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
      .withFallback(ConfigFactory.load())
    val writer = ActorSystem("ShardStatsWriter", config)
    val reader = ActorSystem("ShardStatsReader", config)
    try {
      val random = new java.util.Random(17L)
      val ids = Iterator.fill(500000)(random.nextLong().toString).toSet
      val inventory = ShardRegion.CurrentShardRegionState(Set(ShardRegion.ShardState("links", ids)))
      val summary = ShardRegion.ShardRegionStats(Map("links" -> ids.size, "empty" -> 0))
      val encoding = SerializationExtension(writer)
      val decoding = SerializationExtension(reader)
      val frameLimit = writer.settings.config.getBytes("pekko.remote.artery.advanced.maximum-frame-size").longValue()
      val inventoryBytes = encoding.serialize(inventory).get
      val summaryBytes = encoding.serialize(summary).get
      inventoryBytes.length.toLong should be > frameLimit
      summaryBytes.length should be < 1024
      summaryBytes.length.toLong should be < frameLimit
      val serializer = encoding.findSerializerFor(summary)
      decoding.deserialize(summaryBytes, serializer.identifier, Serializers.manifestFor(serializer, summary)).get shouldBe summary
      info(s"500,000 entities: inventory=${inventoryBytes.length} bytes, summary=${summaryBytes.length} bytes, frame limit=$frameLimit bytes")
    } finally {
      writer.terminate()
      reader.terminate()
    }
  }
}
