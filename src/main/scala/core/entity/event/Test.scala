package org.interscity.htc
package core.entity.event

import org.apache.pekko.protobufv3.internal.{GeneratedMessageV3, Message}

case class Test () extends SerializableEvent {
  override def internalGetFieldAccessorTable(): GeneratedMessageV3.FieldAccessorTable = ???

  override def newBuilderForType(parent: GeneratedMessageV3.BuilderParent): Message.Builder = ???

  override def newBuilderForType(): Message.Builder = ???

  override def toBuilder: Message.Builder = ???

  override def getDefaultInstanceForType: Message = ???
}