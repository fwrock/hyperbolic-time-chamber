package org.interscity.htc
package core.actor

import org.apache.pekko.actor.Actor

/** Marker for HTC actors. Not a PersistentActor: nothing was ever persisted (the event journal was
  * in-memory and no snapshot store was configured), so every actor was only paying for recovery.
  */
trait ActorSerializable extends Actor
