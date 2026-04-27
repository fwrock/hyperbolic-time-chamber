package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

import org.apache.pekko.actor.ActorRef

/** Event sent by LoadBalanceManager to request the TimeManager to pause at a safe tick boundary so
  * that shard migration can proceed safely.
  *
  * Protocol flow:
  *   1. LoadBalanceManager → TimeManager: RequestMigrationPauseEvent (this event) 2. TimeManager
  *      finishes current tick's spontaneous events, pauses before advancing 3. TimeManager →
  *      LoadBalanceManager: MigrationSafeEvent (confirms safe to migrate) 4. LoadBalanceManager
  *      executes migration 5. LoadBalanceManager → TimeManager: MigrationCompleteNotifyEvent
  *      (migration done) 6. TimeManager resumes advancing ticks
  *
  * @param shardIds
  *   The shards that will be migrated
  * @param requester
  *   Reference to the LoadBalanceManager for the response
  */
case class RequestMigrationPauseEvent(
  shardIds: Set[String],
  requester: ActorRef
) extends BaseEvent[DefaultBaseEventData]

/** Event sent by TimeManager to LoadBalanceManager confirming that the simulation is paused at a
  * safe tick boundary and migration can proceed.
  *
  * @param currentTick
  *   The tick at which the TimeManager paused
  */
case class MigrationSafeEvent(
  currentTick: Long
) extends BaseEvent[DefaultBaseEventData]

/** Event sent by LoadBalanceManager to TimeManager after all pending migrations have completed,
  * indicating that the TimeManager can resume advancing ticks.
  */
case class MigrationCompleteNotifyEvent(
) extends BaseEvent[DefaultBaseEventData]
