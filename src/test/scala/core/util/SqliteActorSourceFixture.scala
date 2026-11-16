package org.interscity.htc
package core.util

import java.nio.file.{ Files, Path }
import java.sql.{ Connection, DriverManager }
import scala.util.Using

private[core] object SqliteActorSourceFixture {
  val schema: String =
    """CREATE TABLE actor_simulation (
      |  seq INTEGER PRIMARY KEY AUTOINCREMENT,
      |  id TEXT NOT NULL,
      |  name TEXT,
      |  type_actor TEXT NOT NULL,
      |  creation_type TEXT NOT NULL,
      |  start_tick INTEGER NOT NULL DEFAULT 0,
      |  pool_round_robin_pool INTEGER,
      |  pool_total_instances INTEGER,
      |  pool_max_instances_per_node INTEGER,
      |  pool_allow_local_routes INTEGER,
      |  pool_use_roles TEXT,
      |  data_type TEXT NOT NULL,
      |  data_content TEXT NOT NULL,
      |  relationships TEXT
      |)""".stripMargin

  def insertActor(
    connection: Connection,
    id: Long,
    seq: Int,
    content: String = "{}",
    name: String = "example"
  ): Unit =
    Using.resource(
      connection.prepareStatement(
        """INSERT INTO actor_simulation
        |(seq, id, name, type_actor, creation_type, data_type, data_content)
        |VALUES (?, ?, ?, 'example.Model', 'LoadBalancedDistributed', 'state', ?)""".stripMargin
      )
    ) {
      statement =>
        statement.setInt(1, seq)
        statement.setString(2, id.toString)
        statement.setString(3, name)
        statement.setString(4, content)
        statement.executeUpdate()
        ()
    }

  def withDatabase(initialize: Connection => Unit)(test: Path => Unit): Unit = {
    Class.forName("org.sqlite.JDBC")
    val path = Files.createTempFile("htc-actor-source", ".db")
    try {
      Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$path")) {
        connection =>
          Using.resource(connection.createStatement())(_.execute(schema))
          initialize(connection)
      }
      test(path)
    } finally Files.deleteIfExists(path)
  }
}
