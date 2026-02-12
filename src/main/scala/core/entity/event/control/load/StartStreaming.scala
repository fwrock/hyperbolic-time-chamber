package org.interscity.htc
package core.entity.event.control.load

import core.entity.actor.ActorSimulation

import java.io.InputStream

case class StartStreaming(is: InputStream, iter: Iterator[ActorSimulation])
