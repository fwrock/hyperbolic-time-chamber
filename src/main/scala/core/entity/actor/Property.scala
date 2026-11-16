package org.interscity.htc
package core.entity.actor

/** Carries a schema-described runtime value and its imported descriptive metadata.
  *
  * Only value is mutable. The schema and writeable flag are metadata; constructing this record or
  * changing value does not itself validate the value or enforce write permissions.
  */
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
