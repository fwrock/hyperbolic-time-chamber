package org.interscity.htc
package core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

class JsonStreamingUtilSpec extends AnyFlatSpec with Matchers {
  private def record(id: Int): String =
    s"""{"id":$id,"name":"é-person-$id","typeActor":"example.Model","data":{"dataType":"state","content":{"startTick":$id,"padding":"${"x" * 137}"}}}"""
  private def input(json: String): ByteArrayInputStream = new ByteArrayInputStream(
    json.getBytes(UTF_8)
  ) {
    override def readAllBytes(): Array[Byte] = throw new AssertionError(
      "Whole-file buffering is forbidden"
    )
  }
  "actor JSON streaming" should "accept an empty source and retain normal iterator end semantics" in {
    val (parser, iter) = JsonStreamingUtil.createParser(input("[]"))
    try {
      iter.hasNext shouldBe false
      iter.hasNext shouldBe false
      intercept[NoSuchElementException](iter.next())
    } finally parser.close()
  }
  it should "decode UTF-8 records across many buffer refills without reading the whole source" in {
    val stream = input((1 to 1000).map(record).mkString("[", ",", "]"))
    val size = stream.available()
    val (parser, iter) = JsonStreamingUtil.createParser(stream)
    try {
      stream.available() should be > (size / 2)
      val actors = iter.toVector
      actors.map(_.id) shouldBe (1L to 1000L).toVector
      actors.last.name shouldBe "é-person-1000"
      iter.hasNext shouldBe false
    } finally parser.close()
  }
  for (
    (broken, index) <- List(
      "[",
      "[" + record(1),
      "[" + record(1) + ",",
      "[1]",
      "[] []"
    ).zipWithIndex
  )
    it should s"reject incomplete or invalid array input case $index" in {
      intercept[Exception] {
        val (parser, iter) = JsonStreamingUtil.createParser(input(broken))
        try iter.toList
        finally parser.close()
      }
    }

  it should "keep repeated availability checks from consuming the next actor" in {
    val (parser, iter) = JsonStreamingUtil.createParser(input(s"[${record(1)},${record(2)}]"))
    try {
      iter.hasNext shouldBe true
      iter.hasNext shouldBe true
      iter.next().id shouldBe 1L
      iter.hasNext shouldBe true
      iter.hasNext shouldBe true
      iter.next().id shouldBe 2L
      iter.hasNext shouldBe false
      iter.hasNext shouldBe false
    } finally parser.close()
  }

  it should "validate trailing content only when iteration reaches the array end" in {
    val (parser, iter) = JsonStreamingUtil.createParser(input(s"[${record(1)}] []"))
    try {
      iter.next().id shouldBe 1L
      intercept[IllegalArgumentException](iter.hasNext)
    } finally parser.close()
  }

  it should "leave a partially consumed parser and stream available until the caller closes them" in {
    var closed = false
    val stream = new ByteArrayInputStream(s"[${record(1)},${record(2)}]".getBytes(UTF_8)) {
      override def close(): Unit = {
        closed = true
        super.close()
      }
    }
    val (parser, iter) = JsonStreamingUtil.createParser(stream)
    try {
      iter.next().id shouldBe 1L
      closed shouldBe false
      parser.isClosed shouldBe false
    } finally parser.close()
    closed shouldBe true
  }

}
