package org.interscity.htc
package core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

class JsonStreamingUtilSpec extends AnyFlatSpec with Matchers {
  private def record(id: Int): String =
    s"""{"id":$id,"name":"é-person-$id","typeActor":"example.Model","data":{"dataType":"state","content":{"startTick":$id,"padding":"${"x" * 137}"}}}"""
  private def input(json: String): ByteArrayInputStream = new ByteArrayInputStream(json.getBytes(UTF_8)) {
    override def readAllBytes(): Array[Byte] = throw new AssertionError("Whole-file buffering is forbidden")
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
  for ((broken, index) <- List("[", "[" + record(1), "[" + record(1) + ",", "[1]", "[] []").zipWithIndex) {
    it should s"reject incomplete or invalid array input case $index" in {
      intercept[Exception] {
        val (parser, iter) = JsonStreamingUtil.createParser(input(broken))
        try iter.toList finally parser.close()
      }
    }
  }
}
