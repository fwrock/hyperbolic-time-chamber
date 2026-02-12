package org.interscity.htc
package system.broker.kafka.abstraction

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import java.time.Instant

class KafkaAbstractionSpec extends AnyFlatSpec with Matchers with ScalaFutures {
  
  implicit val patience: PatienceConfig = PatienceConfig(timeout = 10.seconds)
  
  case class TestMessage(id: String, content: String, timestamp: Instant) extends KafkaMessage {
    override def messageId: String = id
    override def source: String = "test"
    override def messageType: String = "test-message"
  }
  
  "KafkaSerializerFactory" should "create Jackson serializer correctly" in {
    val serializer = KafkaSerializerFactory.jackson[TestMessage]
    
    val message = TestMessage("123", "test content", Instant.now())
    val serialized = serializer.serialize(message)
    
    serialized should not be empty
    
    val deserialized = serializer.deserialize(serialized)
    deserialized.isSuccess shouldBe true
    deserialized.get.id shouldBe "123"
    deserialized.get.content shouldBe "test content"
  }
  
  "KafkaSerializerFactory" should "create String serializer correctly" in {
    val serializer = KafkaSerializerFactory.string
    
    val message = "test string"
    val serialized = serializer.serialize(message)
    val deserialized = serializer.deserialize(serialized)
    
    deserialized.isSuccess shouldBe true
    deserialized.get shouldBe message
  }
  
  "KafkaSerializerFactory" should "create envelope serializer correctly" in {
    val serializer = KafkaSerializerFactory.envelope[TestMessage]
    
    val message = TestMessage("123", "test", Instant.now())
    val envelope = KafkaMessageEnvelope(
      messageId = "env-123",
      timestamp = Instant.now(),
      source = "test-source",
      messageType = "test",
      payload = message
    )
    
    val serialized = serializer.serialize(envelope)
    val deserialized = serializer.deserialize(serialized)
    
    deserialized.isSuccess shouldBe true
    deserialized.get.messageId shouldBe "env-123"
    deserialized.get.payload.id shouldBe "123"
  }
  
  "KafkaAbstractionFactory" should "create publishers and consumers" in {
    implicit val system: ActorSystem = ActorSystem("test-system")
    implicit val ec: ExecutionContext = system.dispatcher
    
    try {
      val publisher = KafkaAbstractionFactory.createPublisher[TestMessage]()
      publisher should not be null
      
      val consumer = KafkaAbstractionFactory.createConsumer[TestMessage](
        topic = "test-topic",
        groupId = "test-group"
      )
      consumer should not be null
      
      val broker = KafkaAbstractionFactory.createBroker[TestMessage, TestMessage](
        publishTopic = "publish-topic",
        consumeTopic = "consume-topic", 
        groupId = "test-group"
      )
      broker should not be null
    } finally {
      TestKit.shutdownActorSystem(system)
    }
  }
  
  "KafkaAbstractionFactory.Publishers" should "create specialized publishers" in {
    implicit val system: ActorSystem = ActorSystem("test-system")
    implicit val ec: ExecutionContext = system.dispatcher
    
    try {
      val eventPublisher = KafkaAbstractionFactory.Publishers.forEvents
      eventPublisher should not be null
      
      val stringPublisher = KafkaAbstractionFactory.Publishers.forStrings
      stringPublisher should not be null
      
      val reportPublisher = KafkaAbstractionFactory.Publishers.forReports[TestMessage]
      reportPublisher should not be null
    } finally {
      TestKit.shutdownActorSystem(system)
    }
  }
  
  "KafkaAbstractionFactory.Consumers" should "create specialized consumers" in {
    implicit val system: ActorSystem = ActorSystem("test-system")  
    implicit val ec: ExecutionContext = system.dispatcher
    
    try {
      val eventConsumer = KafkaAbstractionFactory.Consumers.forEvents("test-topic", "test-group")
      eventConsumer should not be null
      
      val stringConsumer = KafkaAbstractionFactory.Consumers.forStrings("test-topic", "test-group")
      stringConsumer should not be null
      
      val reportConsumer = KafkaAbstractionFactory.Consumers.forReports[TestMessage]("test-topic", "test-group")
      reportConsumer should not be null
    } finally {
      TestKit.shutdownActorSystem(system)
    }
  }
  
  "KafkaAbstractionFactory.Brokers" should "create specialized brokers" in {
    implicit val system: ActorSystem = ActorSystem("test-system")
    implicit val ec: ExecutionContext = system.dispatcher
    
    try {
      val requestResponseBroker = KafkaAbstractionFactory.Brokers.forRequestResponse[TestMessage, TestMessage](
        requestTopic = "requests",
        responseTopic = "responses",
        groupId = "test-group"
      )
      requestResponseBroker should not be null
      
      val commandEventBroker = KafkaAbstractionFactory.Brokers.forCommandEvent[TestMessage, TestMessage](
        commandTopic = "commands",
        eventTopic = "events",
        groupId = "test-group"
      )
      commandEventBroker should not be null
    } finally {
      TestKit.shutdownActorSystem(system)
    }
  }
}