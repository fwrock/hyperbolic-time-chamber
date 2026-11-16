package org.interscity.htc
package core.actor.manager.load

import core.entity.actor.ActorSimulation

/** A model-owned transformation of a loaded record, run before dependency validation.
  *
  * Implementations must preserve actor id and class and avoid mutating the original record or its
  * payload. Configuration-based loading requires a public constructor accepting Config. Instances
  * may be reused across records and source scans; transformations must not depend on scan count.
  */
trait ActorLoadTransform {

  /** Returns the transformed record, preserving identity/class; failures abort the caller's load.
    */
  def transform(actor: ActorSimulation): ActorSimulation
}
