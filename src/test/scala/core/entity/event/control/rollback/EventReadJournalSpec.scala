package org.interscity.htc
package core.entity.event.control.rollback

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class EventReadJournalSpec extends AnyFlatSpec with Matchers {
  "EventReadJournal" should "record repeated reads separately and replay without consulting live inputs" in {
    val reads = mutable.ArrayBuffer.empty[RecordedRead]
    var source = 0
    def read(): Int = EventReadJournal.read("test.counter") { source += 1; source }

    EventReadJournal.withRecording(reads) { (read(), read()) } shouldBe (1, 2)
    source = 100
    EventReadJournal.withReplay(reads.toVector) { (read(), read()) } shouldBe (1, 2)
    source shouldBe 100
    read() shouldBe 101
  }

  it should "reject missing, reordered, and unused observations" in {
    an[IllegalArgumentException] should be thrownBy EventReadJournal.withReplay(Vector.empty) {
      EventReadJournal.read("missing")(1)
    }
    an[IllegalArgumentException] should be thrownBy EventReadJournal.withReplay(Vector(RecordedRead("expected", 1))) {
      EventReadJournal.read("other")(2)
    }
    an[IllegalArgumentException] should be thrownBy EventReadJournal.withReplay(Vector(RecordedRead("unused", 1))) { () }
    EventReadJournal.read("after-failure")(3) shouldBe 3
  }

  it should "restore the enclosing scope after a nested observation scope" in {
    val outer = mutable.ArrayBuffer.empty[RecordedRead]
    val inner = mutable.ArrayBuffer.empty[RecordedRead]
    EventReadJournal.withRecording(outer) {
      EventReadJournal.read("outer.first")(1)
      EventReadJournal.withRecording(inner) { EventReadJournal.read("inner")(2) }
      EventReadJournal.read("outer.last")(3)
    }
    outer.toVector shouldBe Vector(RecordedRead("outer.first", 1), RecordedRead("outer.last", 3))
    inner.toVector shouldBe Vector(RecordedRead("inner", 2))
  }
}
