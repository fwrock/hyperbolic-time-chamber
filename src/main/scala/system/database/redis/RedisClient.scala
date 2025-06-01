package org.interscity.htc
package system.database.redis

object RedisClient {
  lazy val instance: RedisClientManager = new RedisClientManager()
}
