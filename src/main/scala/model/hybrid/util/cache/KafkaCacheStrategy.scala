package org.interscity.htc.model.hybrid.util.cache

import org.apache.kafka.clients.consumer.{ ConsumerConfig, KafkaConsumer }
import org.apache.kafka.clients.producer.{ KafkaProducer, ProducerConfig, ProducerRecord }
import org.apache.kafka.common.serialization.{ StringDeserializer, StringSerializer }
import org.interscity.htc.core.util.JsonUtil
import org.interscity.htc.core.kafka.KafkaTopicNaming
import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost

import java.util
import java.util.concurrent.ConcurrentHashMap
import java.util.{ Properties => JavaProperties }
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }

/** Kafka-based cache strategy for dynamic link costs.
  *
  * Architecture:
  *   1. LinkActors publish cost updates to Kafka topic "dynamic-link-costs" 2. Each cluster node
  *      consumes from topic asynchronously 3. Local ConcurrentHashMap updated with latest costs 4.
  *      A* algorithm uses ultra-fast local memory access 5. Accepts eventual consistency (realistic
  *      like real traffic)
  *
  * Benefits:
  *   - Sub-microsecond local reads during route calculation
  *   - Automatic replay and fault tolerance via Kafka
  *   - Scales to millions of links with minimal overhead
  *   - Realistic lag simulation (traffic info is never 100% current)
  */
class KafkaCacheStrategy(implicit ec: ExecutionContext) extends WeightCacheStrategy {

  private val localCache = new ConcurrentHashMap[String, DynamicLinkCost]()

  private val TOPIC_NAME = KafkaTopicNaming.Routing.DynamicCosts

  private lazy val producer: KafkaProducer[String, String] = {
    val props = new JavaProperties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.ACKS_CONFIG, "1")
    props.put(ProducerConfig.RETRIES_CONFIG, "3")
    props.put(ProducerConfig.LINGER_MS_CONFIG, "5")
    props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy")

    new KafkaProducer[String, String](props)
  }

  // Kafka consumer for receiving cost updates
  private lazy val consumer: KafkaConsumer[String, String] = {
    val props = new JavaProperties()
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers)

    val consumerGroupId =
      try {
        val hostname = sys.env.getOrElse(
          "CLUSTER_NAME",
          sys.env.getOrElse("HOSTNAME", java.util.UUID.randomUUID().toString.take(8))
        )
        s"htc-dynamic-costs-$hostname"
      } catch {
        case _: Exception =>
          s"htc-dynamic-costs-${java.util.UUID.randomUUID().toString.take(8)}"
      }

    props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId)
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
    props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000")
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")

    val kafkaConsumer = new KafkaConsumer[String, String](props)
    kafkaConsumer.subscribe(util.Arrays.asList(TOPIC_NAME))
    kafkaConsumer
  }

  private def kafkaBootstrapServers: String =
    try
      com.typesafe.config.ConfigFactory.load().getString("htc.kafka.bootstrap.servers")
    catch {
      case _: Exception => "localhost:9092"
    }

  @volatile private var publishCount = 0
  @volatile private var consumeCount = 0
  @volatile private var lastUpdateTime = System.currentTimeMillis()

  startBackgroundConsumer()

  override def publishCost(cost: DynamicLinkCost, ttlSeconds: Int): Try[Unit] =
    Try {
      val costJson = JsonUtil.toJson(cost)
      val record = new ProducerRecord[String, String](TOPIC_NAME, cost.linkId, costJson)

      producer.send(
        record,
        (metadata, exception) =>
          if (exception != null) {
            System.err.println(
              s"Failed to publish cost for link ${cost.linkId}: ${exception.getMessage}"
            )
          } else {
            publishCount += 1
          }
      )

      localCache.put(cost.linkId, cost)
    }

  override def getCost(linkId: String): Option[DynamicLinkCost] =
    Option(localCache.get(linkId))

  override def getWeight(linkId: String, staticWeight: Double): Double =
    getCost(linkId) match {
      case Some(cost) => cost.totalCost
      case None       => staticWeight
    }

  override def getBatchWeights(linkWeights: Map[String, Double]): Map[String, Double] =
    linkWeights.map {
      case (linkId, staticWeight) =>
        val dynamicWeight = Option(localCache.get(linkId)) match {
          case Some(cost) => cost.totalCost
          case None       => staticWeight
        }
        linkId -> dynamicWeight
    }

  override def clearCost(linkId: String): Try[Unit] =
    Try {
      localCache.remove(linkId)
    }

  override def clearAllCosts(): Try[Unit] =
    Try {
      localCache.clear()
    }

  override def getStatistics(): (Int, Double, Int) = {
    val cacheSize = localCache.size()
    val avgAge = if (cacheSize > 0) {
      (System.currentTimeMillis() - lastUpdateTime) / 1000.0
    } else 0.0
    (cacheSize, avgAge, publishCount)
  }

  /** Start background thread to consume Kafka messages.
    */
  private def startBackgroundConsumer(): Unit =
    Future {
      try {
        println(s"Starting Kafka consumer for topic: $TOPIC_NAME")
        while (!Thread.currentThread().isInterrupted) {
          val records = consumer.poll(java.time.Duration.ofMillis(100))

          records.asScala.foreach {
            record =>
              Try {
                val cost = JsonUtil.fromJson[DynamicLinkCost](record.value())
                localCache.put(cost.linkId, cost)
                consumeCount += 1
                lastUpdateTime = System.currentTimeMillis()
              } match {
                case Success(_) =>
                case Failure(e) =>
                  System.err.println(s"Failed to parse dynamic cost from Kafka: ${e.getMessage}")
              }
          }
        }
      } catch {
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          println("Kafka consumer thread interrupted")
        case e: Exception =>
          System.err.println(s"Kafka consumer error: ${e.getMessage}")
          e.printStackTrace()
      } finally
        try {
          consumer.close()
          producer.close()
        } catch {
          case e: Exception =>
            System.err.println(s"Error closing Kafka clients: ${e.getMessage}")
        }
    }

  /** Get consumer statistics.
    */
  def getConsumerStatistics(): (Int, Int, Long) =
    (publishCount, consumeCount, lastUpdateTime)
}
