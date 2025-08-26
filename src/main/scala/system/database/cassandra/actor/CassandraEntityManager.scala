package org.interscity.htc
package system.database.cassandra.actor

import org.apache.pekko.actor.{Actor, ActorLogging, Props, Status}
import org.apache.pekko.pattern.pipe
import org.apache.pekko.stream.connectors.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import com.datastax.oss.driver.api.core.cql.{PreparedStatement, Row}
import org.interscity.htc.system.database.cassandra.entity.event._

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class CassandraEntityManager(sessionName: String, keyspace: String) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext = context.dispatcher

  val session: CassandraSession = CassandraSessionRegistry.get(context.system).sessionFor(sessionName)

  private var preparedStatementCache = collection.mutable.Map.empty[String, Future[PreparedStatement]]

  override def receive: Receive = {
    case event: CreateEntityEvent =>
      val senderRef = sender()
      val cql = createInsertQuery(event)

      getOrPrepare(cql).flatMap { stmt =>
        val boundStatement = stmt.bind(event.values.map(_.asInstanceOf[AnyRef]): _*)
        session.executeWrite(boundStatement)
      }.map(_ => WriteSuccess).pipeTo(senderRef)

    case event: ReadEntityEvent =>
      val senderRef = sender()
      val cql = s"SELECT ${event.projection} FROM $keyspace.${event.table} WHERE ${event.selection}"

      getOrPrepare(cql).flatMap { stmt =>
        val boundStatement = stmt.bind(event.selectionArgs.map(_.asInstanceOf[AnyRef]): _*)
        session.selectAll(boundStatement)
      }.map(rows => QuerySuccess(rows.map(rowToMap))).pipeTo(senderRef)

    case event: UpdateEntityEvent =>
      val senderRef = sender()
      val setClause = event.setColumns.keys.map(col => s"$col = ?").mkString(", ")
      val cql = s"UPDATE $keyspace.${event.table} SET $setClause WHERE ${event.conditions}"

      // A ordem dos argumentos deve ser a mesma da query: primeiro os do SET, depois os do WHERE
      val allArgs = event.setColumns.values.toList ++ event.conditionArgs

      getOrPrepare(cql).flatMap { stmt =>
        val boundStatement = stmt.bind(allArgs.map(_.asInstanceOf[AnyRef]): _*)
        session.executeWrite(boundStatement)
      }.map(_ => WriteSuccess).pipeTo(senderRef)

    case event: DeleteEntityEvent =>
      val senderRef = sender()
      val cql = s"DELETE FROM $keyspace.${event.table} WHERE ${event.conditions}"

      getOrPrepare(cql).flatMap { stmt =>
        val boundStatement = stmt.bind(event.conditionArgs.map(_.asInstanceOf[AnyRef]): _*)
        session.executeWrite(boundStatement)
      }.map(_ => WriteSuccess).pipeTo(senderRef)

    case other =>
      log.warning("Recebida mensagem não tratada: {}", other)
      sender() ! Status.Failure(new IllegalArgumentException(s"Mensagem não suportada: $other"))
  }

  /**
   * Obtém um PreparedStatement do cache ou o prepara se for a primeira vez.
   * Isso evita preparar a mesma query repetidamente.
   */
  private def getOrPrepare(cql: String): Future[PreparedStatement] = {
    preparedStatementCache.getOrElseUpdate(cql, {
      log.debug("Preparando nova query CQL: {}", cql)
      session.prepare(cql)
    })
  }

  /**
   * Constrói a query de INSERT de forma segura, com placeholders.
   */
  private def createInsertQuery(event: CreateEntityEvent): String = {
    val columns = event.columns.mkString(", ")
    val placeholders = event.values.map(_ => "?").mkString(", ")
    s"INSERT INTO $keyspace.${event.table} ($columns) VALUES ($placeholders)"
  }

  /**
   * Converte um objeto Row do driver do Cassandra para um Map[String, AnyRef] genérico.
   * Isso torna o resultado mais fácil de usar pelo ator que fez a requisição.
   */
  private def rowToMap(row: Row): Map[String, AnyRef] = {
    val columnDefs = row.getColumnDefinitions.asScala
    columnDefs.map { colDef =>
      val colName = colDef.getName.asCql(true)
      colName -> row.getObject(colName)
    }.toMap
  }
}

object CassandraEntityManager {
  def props(sessionName: String, keyspace: String): Props =
    Props(new CassandraEntityManager(sessionName, keyspace))
}