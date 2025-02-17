package org.interscity.htc
package system.database.cassandra.connection

import com.datastax.oss.driver.api.core.CqlSession
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.connectors.cassandra.javadsl.CassandraSessionRegistry

import java.net.InetSocketAddress

object CassandraConnection {

  def createSessions(): Map[String, CqlSession] = {
    val config = ConfigFactory.load()
    val hosts = config.getStringList("databases.cassandra.hosts")
    val port = config.getInt("databases.cassandra.port")
    val keyspace = config.getString("databases.cassandra.keyspace")
    val username = config.getString("databases.cassandra.username")
    val password = config.getString("databases.cassandra.password")

    hosts.toArray
      .map(_.toString)
      .map {
        host =>
          val session = CqlSession
            .builder()
            .addContactPoint(new InetSocketAddress(host, port))
            .withLocalDatacenter("datacenter1")
            .withAuthCredentials(username, password)
            .withKeyspace(keyspace)
            .build()
          (host, session)
      }
      .toMap
  }

  def createSession(connectionName: String, system: ActorSystem) =
    CassandraSessionRegistry.get(system).sessionFor(s"databases.cassandra.$connectionName")
}
