package org.interscity.htc
package core.partial

import core.entity.actor.{ ActorDataSimulation, ActorSimulation, ShardActorId }

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class PartialClosureSpec extends AnyFlatSpec with Matchers {
  // 1,2 = persons (wholes); 11,12 = cars of 1; 21 = car of 2; 30 = a car shared by persons 1 and 2; 99 = node
  private val all = Set(1L, 2L, 11L, 12L, 21L, 30L, 99L)
  private val links = List((11L, 1L), (12L, 1L), (21L, 2L), (30L, 1L), (30L, 2L))

  private def kept(selected: Set[Long]) = PartialClosure.compute(all, links, selected).kept

  "a selected whole" should "keep all its parts but not unrelated actors" in {
    val ids = Set(1L, 2L, 11L, 21L, 99L)
    PartialClosure.compute(ids, List((11L, 1L), (21L, 2L)), Set(2L)).kept shouldBe Set(2L, 21L)
  }

  "a selected whole with a shared part" should "also keep the other owner of that part (rule 3), not its private parts" in {
    kept(Set(2L)) shouldBe Set(2L, 21L, 30L, 1L)       // car 30 is shared with person 1; person 1's cars 11, 12 stay out
  }

  "a selected part" should "keep its whole, without the whole's other parts" in {
    kept(Set(11L)) shouldBe Set(11L, 1L)
  }

  "a part with several wholes" should "keep all of them when it is kept" in {
    kept(Set(30L)) shouldBe Set(30L, 1L, 2L)
  }

  "a whole selected together with a shared part" should "bring that part's other whole too (but not that whole's other parts)" in {
    kept(Set(1L)) shouldBe Set(1L, 11L, 12L, 30L, 2L)   // person 2 comes from rule 3; its car 21 does not
  }

  "reasons" should "say why each actor is kept" in {
    val r = PartialClosure.compute(all, links, Set(1L))
    r.reasons(1L) shouldBe PartialClosure.Selected
    r.reasons(11L) shouldBe PartialClosure.PartOfSelectedWhole
    r.reasons(2L) shouldBe PartialClosure.WholeOfKeptPart
    r.reasons.get(21L) shouldBe None
  }

  "hierarchies" should "work across several levels in both directions" in {
    val ids = Set(1L, 2L, 3L, 4L)
    val chain = List((2L, 1L), (3L, 2L), (4L, 3L))                      // 4 part of 3 part of 2 part of 1
    PartialClosure.compute(ids, chain, Set(1L)).kept shouldBe Set(1L, 2L, 3L, 4L)
    PartialClosure.compute(ids, chain, Set(3L)).kept shouldBe Set(3L, 4L, 2L, 1L)
    PartialClosure.compute(ids, chain, Set(4L)).kept shouldBe Set(4L, 3L, 2L, 1L)
  }

  "no relations" should "keep exactly the selected actors that exist" in {
    val r = PartialClosure.compute(all, Nil, Set(1L, 500L))
    r.kept shouldBe Set(1L)
    r.selectedNotFound shouldBe Set(500L)
  }

  "problems" should "be reported for dangling relations and cycles" in {
    val dangling = PartialClosure.compute(all, List((11L, 777L), (888L, 1L)), Set(1L))
    dangling.problems should have size 2
    val cyc = PartialClosure.compute(Set(1L, 2L, 3L), List((1L, 2L), (2L, 3L), (3L, 1L)), Set(1L))
    cyc.problems.exists(_.contains("cycle")) shouldBe true
    PartialClosure.compute(Set(5L), List((5L, 5L)), Set(5L)).problems should have size 1
    PartialClosure.compute(all, links, Set(1L)).problems shouldBe Nil
  }

  "linksOf" should "read part_of on the part and contains on the whole, and ignore references" in {
    def actor(id: Long, rel: Map[String, ShardActorId]) =
      ActorSimulation(id = id, name = null, typeActor = "x", data = null.asInstanceOf[ActorDataSimulation], relationships = rel)
    val car = actor(11, Map("owner" -> ShardActorId(1, "P", relation = ShardActorId.PartOf), "from_node" -> ShardActorId(99, "N")))
    PartialClosure.linksOf(car) shouldBe Seq((11L, 1L))
    val person = actor(2, Map("v" -> ShardActorId(21, "C", relation = ShardActorId.Contains)))
    PartialClosure.linksOf(person) shouldBe Seq((21L, 2L))
    PartialClosure.linksOf(actor(5, null)) shouldBe Nil
  }

  "the relation field" should "default to reference and be read from the scenario JSON" in {
    val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
    val plain = mapper.readValue("""{"id": 1, "classType": "N"}""", classOf[ShardActorId])
    plain.relation shouldBe ShardActorId.Reference
    val owner = mapper.readValue("""{"id": 40, "classType": "P", "relation": "part_of"}""", classOf[ShardActorId])
    owner.relation shouldBe ShardActorId.PartOf
  }

  "the manifest reader" should "return numeric unit ids and flag the others" in {
    val f = Files.createTempFile("selection", ".json")
    Files.writeString(f, """{"schema":"htc.simedape.selection.v1","scenario_id":"s","selected":[{"unit":"10"},{"unit":"12"},{"unit":"abc"}]}""")
    val s = PartialManifest.read(f.toString)
    s.units shouldBe Set(10L, 12L)
    s.nonNumeric shouldBe List("abc")
    s.scenarioId shouldBe Some("s")
    Files.writeString(f, """{"schema":"other","selected":[]}""")
    an[IllegalArgumentException] should be thrownBy PartialManifest.read(f.toString)
    Files.delete(f)
  }
}
