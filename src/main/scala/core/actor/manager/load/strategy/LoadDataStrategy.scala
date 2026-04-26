package org.interscity.htc
package core.actor.manager.load.strategy

import core.actor.BaseActor

import core.entity.state.DefaultState
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.LoadDataSourceEvent

/** Base abstraction for data-loading strategies used by the load manager.
 *
 * A concrete implementation encapsulates how actor data is fetched and/or materialized
 * from a configured [[ActorDataSource]] and triggered by a [[LoadDataSourceEvent]].
 *
 * This class extends [[BaseActor]] and participates in the actor lifecycle, so strategy
 * implementations should keep actor semantics in mind:
 *   - process requests via message handling (single-threaded mailbox execution),
 *   - avoid blocking operations when possible,
 *   - delegate heavy I/O to async flows or dedicated actors/execution contexts.
 *
 * Typical responsibilities of implementations include:
 *   - validating the data source configuration,
 *   - loading/parsing raw source content,
 *   - triggering downstream actor creation/initialization through manager/creator actors,
 *   - handling source-specific failures with appropriate logging/reporting.
 *
 * @param properties
 *   actor runtime properties (identity, manager references, reporters, and contextual
 *   configuration) used by this strategy actor.
 */
abstract class LoadDataStrategy(
  private val properties: Properties
) extends BaseActor[DefaultState](
      properties = properties
    ) {

  /** Executes the loading process using the full load request event.
   *
   * Use this entry point when the strategy needs access to orchestration references
   * embedded in the event (e.g., manager, creator, creator pool), in addition to
   * the [[ActorDataSource]] itself.
   *
   * @param event
   * load request containing the target data source and runtime actor references
   * required to coordinate creation/loading flow.
   */
  protected def load(event: LoadDataSourceEvent): Unit

  /** Executes the loading process using only a data source descriptor.
   *
   * Use this overload for source-focused loading where orchestration details are
   * provided elsewhere or not required by the strategy implementation.
   *
   * @param actorDataSource
   * configuration that describes what to load (source id, class type, creation
   * mode, backing source details, and loading strategy metadata).
   */
  protected def load(actorDataSource: ActorDataSource): Unit
}
