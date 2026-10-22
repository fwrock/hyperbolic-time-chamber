package org.interscity.htc
package model.hybrid.util

import core.actor.rollback.{ EventReadJournal, RecordedRead }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class GPSUtilReplaySpec extends AnyFlatSpec with Matchers {
  "route observations" should "replay a cache miss without repeating its nested weight reads or sharing its mutable queue" in {
    val reads = mutable.ArrayBuffer.empty[RecordedRead]
    var cacheMisses = 0
    def route() = GPSUtil.observeRoute("test.route:1:2") {
      cacheMisses += 1
      val cost = EventReadJournal.read("test.weight")(42.0)
      Some((cost, mutable.Queue((10L, 2L), (11L, 3L))))
    }

    val original = EventReadJournal.withRecording(reads)(route()).get
    original._2.dequeue()
    reads.map(_.key).toSeq shouldBe Seq("test.route:1:2")

    val replayed = EventReadJournal.withReplay(reads.toVector)(route()).get
    replayed._1 shouldBe 42.0
    replayed._2.toList shouldBe List((10L, 2L), (11L, 3L))
    replayed._2.clear()
    EventReadJournal.withReplay(reads.toVector)(route()).get._2.size shouldBe 2
    cacheMisses shouldBe 1
  }
}
