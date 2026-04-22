package org.interscity.htc.core.kafka

import org.apache.kafka.clients.admin.{ AdminClient, AdminClientConfig, CreateTopicsResult, NewTopic }
import org.apache.kafka.common.errors.TopicExistsException
import com.typesafe.config.ConfigFactory
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Kafka topic auto-creation service for HTC simulation.
  *
  * Ensures all required topics exist with proper configuration before simulation starts. Uses
  * AdminClient to create topics if they don't exist.
  */
object KafkaTopicManager {

  private val config = ConfigFactory.load()

  private def bootstrapServers: String =
    try
      config.getString("htc.kafka.bootstrap.servers")
    catch {
      case _: Exception => "localhost:9092"
    }

  private lazy val adminClient: AdminClient = {
    val props = new Properties()
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(AdminClientConfig.CLIENT_ID_CONFIG, "htc-topic-manager")
    props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000")
    AdminClient.create(props)
  }

  /** Initialize all HTC topics. Call this during application startup before any Kafka
    * producers/consumers.
    */
  def initializeAllTopics(): Try[Unit] =
    Try {
      println("🏗️  Initializing Kafka topics for HTC simulation...")

      val allTopics = KafkaTopicNaming.getAllTopics
      val topicsToCreate = allTopics.map {
        topicName =>
          val config = KafkaTopicNaming.getTopicConfig(topicName)
          createNewTopic(config)
      }.toList

      val createResult: CreateTopicsResult = adminClient.createTopics(topicsToCreate.asJava)

      // Wait for all topics to be created
      createResult.all().get(30, TimeUnit.SECONDS)

      println(s"✅ Successfully initialized ${allTopics.size} Kafka topics")
      KafkaTopicNaming.printTopicConfiguration()

    }.recoverWith {
      case e: Exception =>
        // Check if failure is just because topics already exist
        if (e.getCause != null && e.getCause.isInstanceOf[TopicExistsException]) {
          println("ℹ️  Topics already exist, continuing...")
          Success(())
        } else {
          println(s"❌ Failed to initialize Kafka topics: ${e.getMessage}")
          Failure(e)
        }
    }

  /** Initialize a specific topic.
    */
  def initializeTopic(topicName: String): Try[Unit] =
    Try {
      val config = KafkaTopicNaming.getTopicConfig(topicName)
      val newTopic = createNewTopic(config)

      val createResult = adminClient.createTopics(List(newTopic).asJava)
      createResult.all().get(10, TimeUnit.SECONDS)

      println(s"✅ Topic created: ${config.name}")

    }.recoverWith {
      case e: Exception =>
        if (e.getCause != null && e.getCause.isInstanceOf[TopicExistsException]) {
          println(s"ℹ️  Topic already exists: $topicName")
          Success(())
        } else {
          println(s"❌ Failed to create topic $topicName: ${e.getMessage}")
          Failure(e)
        }
    }

  private def createNewTopic(config: KafkaTopicNaming.TopicConfig): NewTopic = {
    val topic = new NewTopic(config.name, config.partitions, config.replicationFactor.toShort)

    // Set topic-specific configurations
    val topicConfigs = Map(
      "retention.ms" -> config.retentionMs.toString,
      "cleanup.policy" -> config.cleanupPolicy,
      "compression.type" -> "snappy",
      "message.timestamp.type" -> "CreateTime"
    ).asJava

    topic.configs(topicConfigs)
    topic
  }

  /** List all existing topics (for debugging)
    */
  def listExistingTopics(): Try[Set[String]] =
    Try {
      val listResult = adminClient.listTopics()
      val topicNames = listResult.names().get(10, TimeUnit.SECONDS)
      topicNames.asScala.toSet
    }

  /** Check if all HTC topics exist
    */
  def checkTopicsExist(): Boolean =
    listExistingTopics() match {
      case Success(existingTopics) =>
        val requiredTopics = KafkaTopicNaming.getAllTopics
        val missingTopics = requiredTopics -- existingTopics

        if (missingTopics.nonEmpty) {
          println(s"⚠️  Missing topics: ${missingTopics.mkString(", ")}")
          false
        } else {
          println(s"✅ All ${requiredTopics.size} HTC topics exist")
          true
        }

      case Failure(e) =>
        println(s"❌ Failed to check topics: ${e.getMessage}")
        false
    }

  /** Cleanup - close admin client
    */
  def close(): Unit =
    try
      adminClient.close()
    catch {
      case e: Exception =>
        println(s"Warning: Error closing Kafka admin client: ${e.getMessage}")
    }
}

/** Command-line tool to manage topics
  */
object KafkaTopicManagerApp {
  def main(args: Array[String]): Unit =
    args.headOption match {
      case Some("init") =>
        KafkaTopicManager.initializeAllTopics() match {
          case Success(_) =>
            println("🎉 Topic initialization completed successfully!")
            sys.exit(0)
          case Failure(e) =>
            println(s"💥 Topic initialization failed: ${e.getMessage}")
            sys.exit(1)
        }

      case Some("list") =>
        KafkaTopicManager.listExistingTopics() match {
          case Success(topics) =>
            println(s"📋 Existing topics (${topics.size}):")
            topics.toSeq.sorted.foreach(
              topic => println(s"   - $topic")
            )
          case Failure(e) =>
            println(s"❌ Failed to list topics: ${e.getMessage}")
            sys.exit(1)
        }

      case Some("check") =>
        val allExist = KafkaTopicManager.checkTopicsExist()
        sys.exit(if (allExist) 0 else 1)

      case _ =>
        println("Usage: KafkaTopicManagerApp [init|list|check]")
        println("  init  - Create all HTC topics")
        println("  list  - List existing topics")
        println("  check - Check if all HTC topics exist")
        sys.exit(1)
    }
}
