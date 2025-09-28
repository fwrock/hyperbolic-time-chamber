package org.interscity.htc
package core.actor.manager.report

import org.interscity.htc.core.entity.event.control.report.ReportEvent

import org.apache.pekko.actor.ActorRef
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.{PreparedStatement, SimpleStatement}
import com.typesafe.config.Config
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import java.time.LocalDateTime
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

class CassandraReportData(override val reportManager: ActorRef, override val startRealTime: LocalDateTime)
    extends ReportData(
      id = "cassandra-report-manager",
      reportManager = reportManager,
      startRealTime = startRealTime
    ) {

  private val keyspace = Some(config.getString("htc.report-manager.cassandra.keyspace"))
    .getOrElse("htc_reports")
  private val table = Some(config.getString("htc.report-manager.cassandra.table"))
    .getOrElse("simulation_reports")
  private val batchSize = Some(config.getInt("htc.report-manager.cassandra.batch-size"))
    .getOrElse(1000)
  private val hosts = Some(config.getString("htc.report-manager.cassandra.hosts"))
    .getOrElse("127.0.0.1:9042")
  private val datacenter = Some(config.getString("htc.report-manager.cassandra.datacenter"))
    .getOrElse("datacenter1")

  private val buffer = mutable.ListBuffer[ReportEvent]()
  private var session: Option[CqlSession] = None
  private var insertStatement: Option[PreparedStatement] = None
  
  // JSON mapper para converter dados para JSON
  private val objectMapper = {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
    mapper
  }

  override def preStart(): Unit = {
    super.preStart()
    initializeCassandra()
  }

  private def initializeCassandra(): Unit = {
    try {
      val sessionBuilder = CqlSession.builder()
        .addContactPoint(java.net.InetSocketAddress.createUnresolved(hosts.split(":")(0), hosts.split(":")(1).toInt))
        .withLocalDatacenter(datacenter)
        
      session = Some(sessionBuilder.build())
      
      // Certifica que a tabela existe
      createTableIfNotExists()
      
      // Prepara a statement de inserção
      val insertQuery = s"""
        INSERT INTO $keyspace.$table (
          id, created_at, data, node_id, report_type, simulation_id, timestamp
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
      """
      insertStatement = session.map(_.prepare(insertQuery))
      
      logInfo(s"Cassandra Report Data initialized - keyspace: $keyspace, table: $table")
    } catch {
      case e: Exception =>
        logError(s"Failed to initialize Cassandra connection: ${e.getMessage}", e)
    }
  }

  private def createTableIfNotExists(): Unit = {
    session.foreach { cqlSession =>
      try {
        val createTableQuery = s"""
          CREATE TABLE IF NOT EXISTS $keyspace.$table (
            id UUID PRIMARY KEY,
            created_at TIMESTAMP,
            data TEXT,
            node_id TEXT,
            report_type TEXT,
            simulation_id TEXT,
            timestamp TIMESTAMP
          )
        """
        // Tenta criar a tabela, mas não falha se já existir
        try {
          cqlSession.execute(createTableQuery)
        } catch {
          case _: Exception => // Tabela já existe, continua
        }
        
        // Criar índices se não existirem
        try {
          cqlSession.execute(s"CREATE INDEX IF NOT EXISTS idx_report_type ON $keyspace.$table (report_type)")
          cqlSession.execute(s"CREATE INDEX IF NOT EXISTS idx_simulation_id ON $keyspace.$table (simulation_id)")
        } catch {
          case _: Exception => // Índices já existem, continua
        }
        
        logInfo(s"Table $keyspace.$table verified/created successfully")
      } catch {
        case e: Exception =>
          logError(s"Failed to create table: ${e.getMessage}", e)
      }
    }
  }

  override def onReport(event: ReportEvent): Unit = {
    buffer += event
    if (buffer.size >= batchSize) {
      flushBuffer()
    }
  }

  private def flushBuffer(): Unit = {
    if (buffer.isEmpty) return
    
    (session, insertStatement) match {
      case (Some(cqlSession), Some(preparedStmt)) =>
        try {
          buffer.foreach { report =>
            val uuid = java.util.UUID.randomUUID()
            val createdAt = java.time.Instant.now()
            val timestamp = java.time.Instant.ofEpochMilli(report.timestamp)
            
            // Converter dados para JSON em vez de toString
            val dataJson = try {
              objectMapper.writeValueAsString(report.data)
            } catch {
              case e: Exception =>
                logError(s"Failed to convert data to JSON, falling back to toString: ${e.getMessage}", e)
                report.data.toString
            }
            
            cqlSession.execute(preparedStmt.bind(
              uuid,                                    // id
              createdAt,                              // created_at
              dataJson,                               // data (now as JSON)
              report.entityId,                        // node_id
              report.label,                           // report_type
              "simulation_" + System.currentTimeMillis(), // simulation_id
              timestamp                               // timestamp
            ))
          }
          logDebug(s"Flushed ${buffer.size} reports to Cassandra as JSON")
          buffer.clear()
        } catch {
          case e: Exception =>
            logError(s"Failed to flush reports to Cassandra: ${e.getMessage}", e)
            // Não limpa o buffer em caso de erro para tentar novamente
        }
      case _ =>
        logError("Cassandra session or prepared statement not available")
    }
  }

  override def postStop(): Unit = {
    if (buffer.nonEmpty) {
      flushBuffer()
    }
    session.foreach(_.close())
    super.postStop()
  }
}
