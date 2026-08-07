package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import core.util.{ IdUtil, SqliteActorSimulationUtil }

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.actor.{ ActorSimulation, ActorSimulationCreation }
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.*
import org.interscity.htc.core.enumeration.CreationTypeEnum.PoolDistributed

import java.sql.{ Connection, DriverManager, ResultSet, Statement }
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

/** EAGER `LoadDataStrategy` reading `ActorSimulation` records from a SQLite `.db` produced by
  * `tools/scenario-db-converter/convert.py`, in place of `JsonLoadData`'s JSON array.
  *
  * Mirrors `JsonLoadData`'s exact chunk-and-batch protocol (`CHUNK_SIZE`, `CREATE_EVENT_MAX_ACTORS`,
  * back-pressure via `activeBatches`/`FinishCreationEvent`) so the rest of the load pipeline
  * (`LoadDataManager`, `CreatorLoadData`/`CreatorPoolLoadData`) needs no changes — only the source
  * of `ActorSimulation` records differs.
  *
  * Per the "no blocking in actors" rule, every JDBC call (open connection, execute query, read a
  * chunk from the open `ResultSet`) runs inside a `Future` on the `io-dispatcher`, never directly
  * in `receive`. One JDBC connection is opened once for the lifetime of this actor and closed in
  * `postStop` — never opened/closed per chunk, since that would contend on SQLite's lock more than
  * a single long-lived connection would.
  *
  * Opened with `PRAGMA query_only=ON` and the `immutable=1` connection URI parameter: the `.db` is
  * produced once by the converter and never mutated afterward, so SQLite can skip its
  * lock/journal machinery entirely. This also assumes a LOCAL copy of the file — see the
  * architecture discussion: SQLite's file locking is not safe over a shared network filesystem, so
  * each cluster node must have its own copy of the `.db`, the same way each node already gets its
  * own copy of the JSON file today.
  */
class SqliteLoadData(private val properties: Properties)
    extends LoadDataStrategy(properties = properties) {

  private implicit def ec: ExecutionContext =
    context.system.dispatchers.lookup("pekko.actor.io-dispatcher")

  private var managerRef: ActorRef = _
  private var creatorRef: ActorRef = _
  private var creatorPoolRef: ActorRef = _

  private var connection: Connection = _
  private var statement: Statement = _
  private var resultSet: ResultSet = _
  private var sourceDbPath: String = _

  private val CHUNK_SIZE = 100
  private val CREATE_EVENT_MAX_ACTORS = 25
  private val activeBatches = mutable.Set[String]()
  private var totalLoadedActors = 0L
  private var sourceClassType: String = _
  private var sourceId: String = _
  private val creators = mutable.Set[ActorRef]()

  override def handleEvent: Receive = {
    case event: LoadDataSourceEvent => load(event)
    case StartLoadingFile           => openDatabaseAndStart()
    case ProcessNextChunk           => readNextChunkAsync()
    case ChunkLoaded(data)          => sendBatch(data)
    case CloseAndFinish             => finishLoading()
    case event: FinishCreationEvent => handleFinishCreation(event)
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

    self ! StartLoadingFile
  }

  private def openDatabaseAndStart(): Unit = {
    logInfo(s"Opening SQLite database: $sourceDbPath")

    Future {
      Class.forName("org.sqlite.JDBC")

      val conn = DriverManager.getConnection(s"jdbc:sqlite:file:$sourceDbPath?immutable=1")
      val pragma = conn.createStatement()
      pragma.execute("PRAGMA query_only = ON")
      pragma.close()

      val stmt = conn.createStatement()
      val rs = stmt.executeQuery("SELECT * FROM actor_simulation ORDER BY seq")
      (conn, stmt, rs)
    }.onComplete {
      case Success((conn, stmt, rs)) =>
        connection = conn
        statement = stmt
        resultSet = rs
        self ! ProcessNextChunk

      case Failure(e) =>
        logError(s"Fatal error opening $sourceDbPath", e)
        self ! CloseAndFinish
    }
  }

  private def readNextChunkAsync(): Unit =
    Future {
      readChunk(resultSet, CHUNK_SIZE)
    }.onComplete {
      case Success(actors) =>
        if (actors.nonEmpty) {
          self ! ChunkLoaded(actors)
        } else {
          self ! CloseAndFinish
        }
      case Failure(e) =>
        logError(s"Error reading from SQLite database $sourceDbPath", e)
        self ! CloseAndFinish
    }

  private def readChunk(rs: ResultSet, maxCount: Int): List[ActorSimulation] = {
    if (rs == null) return List.empty
    val buffer = mutable.ListBuffer[ActorSimulation]()
    while (buffer.size < maxCount && rs.next())
      buffer += SqliteActorSimulationUtil.fromResultSet(rs)
    buffer.toList
  }

  private def sendBatch(actors: List[ActorSimulation]): Unit = {
    totalLoadedActors += actors.size

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
      self ! ProcessNextChunk
    }
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
    activeBatches.remove(event.batchId)

    if (activeBatches.isEmpty) {
      self ! ProcessNextChunk
    }
  }

  private def finishLoading(): Unit = {
    logInfo(s"End of database, size: $totalLoadedActors")
    closeResources()

    managerRef ! FinishLoadDataEvent(
      actorRef = self,
      amount = totalLoadedActors,
      actorClassType = sourceClassType,
      creators = creators
    )
  }

  private def closeResources(): Unit = {
    try if (resultSet != null) resultSet.close() catch { case _: Exception => }
    try if (statement != null) statement.close() catch { case _: Exception => }
    try if (connection != null) connection.close() catch { case _: Exception => }
    resultSet = null
    statement = null
    connection = null
  }

  override def postStop(): Unit = {
    closeResources()
    super.postStop()
  }
}
