package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import core.util.{ IdUtil, SqliteActorSimulationUtil, TickIndexUtil }

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.actor.{ ActorSimulation, ActorSimulationCreation }
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.*
import org.interscity.htc.core.enumeration.CreationTypeEnum.PoolDistributed
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.util.TickIndexUtil.LightTickIndex

import java.sql.{ Connection, DriverManager, PreparedStatement, ResultSet }
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

/** PROGRESSIVE `LoadDataStrategy` reading `ActorSimulation` records for a tick window from a
  * SQLite `.db`, in place of `ProgressiveJsonLoadData`'s two-phase full-file-scan approach.
  *
  * `ProgressiveJsonLoadData` must (1) scan the whole file once to build a tick-density index, and
  * (2) re-open and re-scan the whole file, sequentially discarding non-matching records, for
  * EVERY `LoadActorsForTickRange` request — there is no random access into a JSON file. Here both
  * phases become indexed SQL queries against `start_tick` instead:
  *
  *   - index build: `SELECT start_tick, COUNT(*) FROM actor_simulation GROUP BY start_tick` — one
  *     aggregate query, no row materialization.
  *   - tick window: `SELECT * FROM actor_simulation WHERE start_tick BETWEEN ? AND ? ORDER BY
  *     start_tick` — an indexed range scan (see `idx_actor_simulation_start_tick`) that only
  *     touches matching rows, instead of re-reading and discarding the whole file per window.
  *
  * Uses the same `FileChunkReady` internal message and back-pressure protocol as
  * `ProgressiveJsonLoadData` (defined there, `private[strategy]`, shared across this package) so
  * `ProgressiveLoadDataManager` needs no changes beyond resolving this class via
  * `DataSourceTypeEnum.progressiveClazz`.
  *
  * One JDBC connection is opened once (lazily, on first use) and reused for both the index-build
  * query and every subsequent tick-window query; only the per-window `PreparedStatement`/
  * `ResultSet` are opened and closed per request. See `SqliteLoadData` for why (one connection,
  * not one per query) and the assumption that each cluster node reads a LOCAL copy of the `.db`.
  */
class ProgressiveSqliteLoadData(private val properties: Properties)
    extends LoadDataStrategy(properties = properties) {

  private implicit def ec: ExecutionContext =
    context.system.dispatchers.lookup("pekko.actor.io-dispatcher")

  private var managerRef: ActorRef = _
  private var creatorRef: ActorRef = _
  private var creatorPoolRef: ActorRef = _

  private var sourceDbPath: String = _
  private var sourceClassType: String = _
  private var sourceId: String = _

  private var connection: Connection = _
  private var lightIndex: LightTickIndex = _

  private var fullyConsumed = false

  private var activeStatement: PreparedStatement = _
  private var activeResultSet: ResultSet = _
  private var streamExhausted: Boolean = false

  private val CHUNK_SIZE = 500
  private val CREATE_EVENT_MAX_ACTORS = 25
  private val activeBatches = mutable.Set[String]()
  private var totalLoadedActors = 0L
  private val creators = mutable.Set[ActorRef]()

  private var activeRequest: LoadActorsForTickRange = _
  private var pendingActorsSent = 0

  override def handleEvent: Receive = {
    case event: LoadDataSourceEvent    => load(event)
    case _: BuildTickIndex             => buildTickIndexAsync()
    case event: TickIndexBuiltEvent    => handleTickIndexBuilt(event)
    case event: LoadActorsForTickRange => handleLoadForTickRange(event)
    case FileChunkReady(actors, done)  => handleChunkReady(actors, done)
    case event: FinishCreationEvent    => handleFinishCreation(event)
    case CloseAndFinish()              => finishLoading()
  }

  override protected def load(event: LoadDataSourceEvent): Unit = {
    this.managerRef = event.managerRef
    this.creatorRef = event.creatorRef
    this.creatorPoolRef = event.creatorPoolRef
    load(event.actorDataSource)
  }

  override protected def load(source: ActorDataSource): Unit = {
    this.sourceClassType = source.classType
    this.sourceId = source.id
    this.sourceDbPath = source.dataSource.info("path").asInstanceOf[String]

    logInfo(s"ProgressiveSqliteLoadData: building tick index for $sourceDbPath")
    self ! BuildTickIndex()
  }

  /** Opens the shared connection on first use. Called only from within `Future` bodies running on
    * the io-dispatcher, never directly from `receive`.
    */
  private def openConnectionIfNeeded(): Connection = {
    if (connection == null) {
      Class.forName("org.sqlite.JDBC")

      connection = DriverManager.getConnection(s"jdbc:sqlite:file:$sourceDbPath?immutable=1")
      val pragma = connection.createStatement()
      pragma.execute("PRAGMA query_only = ON")
      pragma.close()
    }
    connection
  }

  private def buildTickIndexAsync(): Unit =
    Future {
      val conn = openConnectionIfNeeded()
      val stmt = conn.createStatement()
      try {
        val rs =
          stmt.executeQuery("SELECT start_tick, COUNT(*) AS cnt FROM actor_simulation GROUP BY start_tick")
        try {
          val tickCounts = mutable.Map[Tick, Int]()
          var totalActors = 0
          var maxTick: Tick = 0L
          var minTick: Tick = Long.MaxValue
          while (rs.next()) {
            val tick = rs.getLong("start_tick")
            val count = rs.getInt("cnt")
            tickCounts.put(tick, count)
            totalActors += count
            if (tick > maxTick) maxTick = tick
            if (tick < minTick) minTick = tick
          }
          if (minTick == Long.MaxValue) minTick = 0L
          LightTickIndex(
            tickCounts = tickCounts.toMap,
            totalActors = totalActors,
            maxTick = maxTick,
            minTick = minTick
          )
        } finally rs.close()
      } finally stmt.close()
    }.onComplete {
      case Success(result) =>
        logInfo(
          s"Tick index built for $sourceDbPath: " +
            s"${result.totalActors} actors, ticks ${result.minTick}-${result.maxTick}, " +
            s"${result.tickCounts.size} unique ticks"
        )
        this.lightIndex = result
        self ! TickIndexBuiltEvent(
          sourceId = sourceId,
          tickCounts = result.tickCounts,
          totalActors = result.totalActors,
          maxTick = result.maxTick
        )

      case Failure(e) =>
        logError(s"Failed to build tick index for $sourceDbPath", e)
        self ! CloseAndFinish()
    }

  private def handleTickIndexBuilt(event: TickIndexBuiltEvent): Unit =
    managerRef ! event

  private def handleLoadForTickRange(request: LoadActorsForTickRange): Unit = {
    if (lightIndex == null) {
      logWarn(
        s"Tick index not built yet, cannot load range [${request.fromTick}, ${request.toTick}]"
      )
      replyEmptyRange(request)
      return
    }

    if (fullyConsumed) {
      replyEmptyRange(request)
      return
    }

    val expectedCount =
      TickIndexUtil.countActorsInRange(lightIndex.tickCounts, request.fromTick, request.toTick)

    if (expectedCount == 0) {
      replyEmptyRange(request)
      return
    }

    logInfo(
      s"Querying ~$expectedCount actors for tick range " +
        s"[${request.fromTick}, ${request.toTick}] from $sourceId"
    )

    Future {
      val conn = openConnectionIfNeeded()
      val stmt = conn.prepareStatement(
        "SELECT * FROM actor_simulation WHERE start_tick BETWEEN ? AND ? ORDER BY start_tick"
      )
      stmt.setLong(1, request.fromTick)
      stmt.setLong(2, request.toTick)
      val rs = stmt.executeQuery()
      (stmt, rs)
    }.onComplete {
      case Success((stmt, rs)) =>
        activeStatement = stmt
        activeResultSet = rs
        activeRequest = request
        pendingActorsSent = 0
        streamExhausted = false
        startNextChunkRead()

      case Failure(e) =>
        logError(s"Error querying tick range from $sourceDbPath", e)
        replyEmptyRange(request)
    }
  }

  private def replyEmptyRange(request: LoadActorsForTickRange): Unit =
    managerRef ! TickRangeLoadedEvent(
      sourceId = sourceId,
      fromTick = request.fromTick,
      toTick = request.toTick,
      actorsLoaded = 0
    )

  private def startNextChunkRead(): Unit = {
    val rs = activeResultSet
    Future {
      val buffer = mutable.ListBuffer[ActorSimulation]()
      var exhausted = false
      while (buffer.size < CHUNK_SIZE && !exhausted)
        if (rs.next()) buffer += SqliteActorSimulationUtil.fromResultSet(rs)
        else exhausted = true
      (buffer.toList, exhausted)
    }.onComplete {
      case Success((actors, exhausted)) => self ! FileChunkReady(actors, exhausted)
      case Failure(e) =>
        logError(s"Error reading chunk from $sourceDbPath", e)
        self ! FileChunkReady(Nil, true)
    }
  }

  private def handleChunkReady(actors: List[ActorSimulation], isDone: Boolean): Unit = {
    streamExhausted = isDone

    if (actors.nonEmpty) {
      sendActorsToCreators(actors)
    } else {
      closeQueryAndReport()
    }
  }

  private def sendActorsToCreators(actors: List[ActorSimulation]): Unit = {
    totalLoadedActors += actors.size
    pendingActorsSent += actors.size

    val actorsToCreate = actors.map(
      actor =>
        ActorSimulationCreation(
          resourceId = IdUtil.format(sourceId),
          actor = actor.copy(id = IdUtil.format(actor.id))
        )
    )

    val (poolDistributed, loadBalanced) =
      actorsToCreate.partition(_.actor.creationType == PoolDistributed)

    if (loadBalanced.nonEmpty) {
      creators.add(creatorRef)
      loadBalanced.grouped(CREATE_EVENT_MAX_ACTORS).foreach {
        group =>
          val batchId = UUID.randomUUID().toString
          activeBatches.add(batchId)
          creatorRef ! CreateActorsEvent(id = batchId, actors = group, actorRef = self)
      }
    }

    if (poolDistributed.nonEmpty) {
      creators.add(creatorPoolRef)
      poolDistributed.grouped(CREATE_EVENT_MAX_ACTORS).foreach {
        group =>
          val batchId = UUID.randomUUID().toString
          activeBatches.add(batchId)
          creatorPoolRef ! CreateActorsEvent(id = batchId, actors = group, actorRef = self)
      }
    }

    if (activeBatches.isEmpty) {
      if (!streamExhausted) startNextChunkRead()
      else closeQueryAndReport()
    }
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
    activeBatches.remove(event.batchId)

    if (activeBatches.isEmpty) {
      if (!streamExhausted) {
        startNextChunkRead()
      } else {
        closeQueryAndReport()
      }
    }
  }

  private def closeQueryAndReport(): Unit = {
    try if (activeResultSet != null) activeResultSet.close() catch { case _: Exception => }
    try if (activeStatement != null) activeStatement.close() catch { case _: Exception => }
    activeResultSet = null
    activeStatement = null

    if (lightIndex != null && activeRequest != null && activeRequest.toTick >= lightIndex.maxTick) {
      fullyConsumed = true
    }

    if (activeRequest != null) {
      logInfo(
        s"Finished querying $pendingActorsSent actors for tick range " +
          s"[${activeRequest.fromTick}, ${activeRequest.toTick}] from $sourceId"
      )
      managerRef ! TickRangeLoadedEvent(
        sourceId = sourceId,
        fromTick = activeRequest.fromTick,
        toTick = activeRequest.toTick,
        actorsLoaded = pendingActorsSent
      )
      activeRequest = null
    }
  }

  private def finishLoading(): Unit = {
    logInfo(s"ProgressiveSqliteLoadData: finished, total actors loaded: $totalLoadedActors")

    try if (activeResultSet != null) activeResultSet.close() catch { case _: Exception => }
    try if (activeStatement != null) activeStatement.close() catch { case _: Exception => }
    try if (connection != null) connection.close() catch { case _: Exception => }
    activeResultSet = null
    activeStatement = null
    connection = null
    lightIndex = null

    managerRef ! FinishLoadDataEvent(
      actorRef = self,
      amount = totalLoadedActors,
      actorClassType = sourceClassType,
      creators = creators
    )
  }

  override def postStop(): Unit = {
    try if (activeResultSet != null) activeResultSet.close() catch { case _: Exception => }
    try if (activeStatement != null) activeStatement.close() catch { case _: Exception => }
    try if (connection != null) connection.close() catch { case _: Exception => }
    super.postStop()
  }
}
