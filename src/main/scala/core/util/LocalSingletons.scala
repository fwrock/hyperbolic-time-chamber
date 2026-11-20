package org.interscity.htc
package core.util

import org.apache.pekko.actor.{
  Actor,
  ActorIdentity,
  ActorRef,
  ActorRefFactory,
  ActorSystem,
  Identify as PekkoIdentify,
  Props,
  Terminated,
  Timers
}

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

/** Single-node stand-in for ClusterSingletonManager/Proxy: the "singleton" is an ordinary
  * top-level actor, and a proxy is either that actor's own ref or, when it does not exist yet, a
  * buffering forwarder that resolves it once it appears (the guarantee ClusterSingletonProxy gave).
  */
object LocalSingletons {
  private val registry = new ConcurrentHashMap[(ActorSystem, String), ActorRef]()

  def register(system: ActorSystem, name: String, ref: ActorRef): ActorRef = {
    registry.put((system, name), ref)
    ref
  }

  def proxy(
    factory: ActorRefFactory,
    system: ActorSystem,
    name: String,
    proxyName: Option[String] = None
  ): ActorRef =
    Option(registry.get((system, name))).getOrElse {
      val props = Props(new LocalSingletonProxy(name))
      proxyName.fold(factory.actorOf(props))(factory.actorOf(props, _))
    }

  private object Retry

  private class LocalSingletonProxy(name: String) extends Actor with Timers {
    private val bufferLimit = 10000
    private val buffer = ArrayBuffer.empty[(Any, ActorRef)]

    override def preStart(): Unit = resolve()

    private def resolve(): Unit = context.actorSelection(s"/user/$name") ! PekkoIdentify(name)

    override def receive: Receive = resolving

    private def resolving: Receive = {
      case ActorIdentity(`name`, Some(ref)) =>
        context.watch(ref)
        buffer.foreach { case (message, sender) => ref.tell(message, sender) }
        buffer.clear()
        context.become(forwarding(ref))
      case ActorIdentity(`name`, None) => timers.startSingleTimer(Retry, Retry, 50.millis)
      case Retry                       => resolve()
      case message =>
        if (buffer.size >= bufferLimit)
          throw new IllegalStateException(s"Local singleton proxy for $name buffered $bufferLimit messages before the target appeared")
        buffer += ((message, sender()))
    }

    private def forwarding(ref: ActorRef): Receive = {
      case Terminated(`ref`) =>
        context.become(resolving)
        resolve()
      case message => ref.forward(message)
    }
  }
}
