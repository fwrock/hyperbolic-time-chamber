package org.interscity.htc
package core.actor.manager.load

import core.entity.event.control.load.*
import core.runtime.accounting.RuntimeFailure
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class PostLoadRegistrationManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("PostLoadRegistrationManagerSpec",
    ConfigFactory.parseString("""
      pekko.actor.provider = local
      pekko.actor.fail-mixed-versions = off
      htc.post-load-registration.max-in-flight = 8
      htc.post-load-registration.retry-interval-seconds = 1
      htc.post-load-registration.max-retries = 3
    """).withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class Manager(parent: ActorRef, requests: ActorRef) extends PostLoadRegistrationManager(parent) {
    def recovered: Boolean = recoveryFinished
    override protected def dispatchRegistration(id: String, model: String): Unit = requests ! id
  }

  "post-load registration" should "bound in-flight requests and ignore duplicate acknowledgements" in {
    val parent = TestProbe(); val requests = TestProbe()
    val manager = TestActorRef(new Manager(parent.ref, requests.ref))
    parent.awaitAssert(manager.underlyingActor.recovered shouldBe true, 5.seconds, 10.millis)
    (1 to 10000).foreach(i => manager ! NeedsPostLoadRegistrationEvent(i.toString, "example.Link"))
    manager ! StartPostLoadRegistrationPhaseEvent
    val first = requests.receiveN(8).map(_.toString)
    val seen = scala.collection.mutable.Set.from(first)
    def nextFresh(): String = {
      val id = requests.fishForMessage(3.seconds) {
        case value: String => !seen.contains(value)
        case _ => false
      }.asInstanceOf[String]
      seen.add(id)
      id
    }
    requests.expectNoMessage(50.millis)
    manager ! PostLoadRegistrationAckEvent(first.head)
    val replacement = nextFresh()
    manager ! PostLoadRegistrationAckEvent(first.head)
    requests.expectNoMessage(50.millis)
    val queue = scala.collection.mutable.Queue.from(first.tail :+ replacement)
    var remaining = 9991
    while (queue.nonEmpty) {
      manager ! PostLoadRegistrationAckEvent(queue.dequeue())
      if (remaining > 0) {
        queue.enqueue(nextFresh())
        remaining -= 1
      }
    }
    parent.expectMsgType[PostLoadRegistrationDoneEvent]
  }

  it should "fail startup instead of opening a barrier with missing acknowledgements" in {
    val parent = TestProbe(); val requests = TestProbe()
    val manager = TestActorRef(new Manager(parent.ref, requests.ref))
    parent.awaitAssert(manager.underlyingActor.recovered shouldBe true, 5.seconds, 10.millis)
    manager ! NeedsPostLoadRegistrationEvent("1", "example.Link")
    manager ! StartPostLoadRegistrationPhaseEvent
    requests.expectMsg("1")
    parent.expectMsgType[RuntimeFailure](5.seconds)
    parent.expectNoMessage(50.millis)
  }
}
