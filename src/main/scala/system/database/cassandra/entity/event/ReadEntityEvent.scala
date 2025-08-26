package org.interscity.htc
package system.database.cassandra.entity.event

import org.interscity.htc.system.entity.event.BaseEvent

case class ReadEntityEvent(
                            table: String,
                            projection: String,
                            selection: String, // A seleção ainda pode ser uma string, mas os valores serão passados separadamente
                            selectionArgs: List[Any],
                          ) extends BaseEvent
