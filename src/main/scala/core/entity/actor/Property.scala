package org.interscity.htc
package core.entity.actor

case class Property(
  id: String = null,
  name: String,
  var value: Any = null,
  schema: String,
  comment: String = null,
  description: String = null,
  displayName: String = null,
  writeable: Boolean = false
)
