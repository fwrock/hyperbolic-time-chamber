package org.interscity.htc
package core.modifications

import core.entity.actor.ActorSimulation
import core.entity.configuration.{ ActorDataSource, DataSource }
import core.enumeration.{ CreationTypeEnum, DataSourceTypeEnum }
import core.util.JsonStreamingUtil

import com.fasterxml.jackson.databind.{ JsonNode, ObjectMapper }
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ BufferedInputStream, File, FileInputStream }
import java.nio.file.{ Files, Path }

class ScenarioModificationsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
  override def afterEach(): Unit = ScenarioModifications.clear()

  private def write(dir: Path, name: String, json: String): String = { val f = dir.resolve(name); Files.writeString(f, json); f.toString }
  private def source(id: String, path: String) =
    ActorDataSource(id, "x", CreationTypeEnum.LoadBalancedDistributed, DataSource(DataSourceTypeEnum.json, Map("path" -> path)))
  private def link(id: Int, speed: Int) =
    s"""{"id":$id,"typeActor":"hybrid.actor.Link","data":{"dataType":"d","content":{"speedLimit":$speed,"lanes":2,"extra":{"gap":1.5}}},"dependencies":{"from_node":{"id":1,"classType":"hybrid.actor.Node"},"to_node":{"id":2,"classType":"hybrid.actor.Node"}}}"""
  private def node(id: Int) = s"""{"id":$id,"typeActor":"hybrid.actor.Node","data":{"dataType":"d","content":{}}}"""
  private def scenario(dir: Path) = List(
    source("nodes", write(dir, "nodes.json", s"[${node(1)},${node(2)}]")),
    source("links", write(dir, "links.json", s"[${link(6, 40)},${link(7, 60)}]")))
  private def mods(dir: Path, name: String, ops: String) =
    write(dir, name, s"""{"schema":"htc.modifications.v1","operations":[$ops]}""")
  private def actors(dir: Path, file: String): List[ActorSimulation] = {
    val is = new BufferedInputStream(new FileInputStream(new File(dir.resolve(file).toString)))
    try JsonStreamingUtil.createParser(is)._2.toList finally is.close()
  }
  private def tree(a: ActorSimulation): JsonNode = mapper.valueToTree[JsonNode](a.data.content)

  "without modifications" should "leave the actors untouched" in {
    val all = actors({ val d = Files.createTempDirectory("m"); scenario(d); d }, "links.json")
    ScenarioModifications.enabled shouldBe false
    ScenarioModifications(all) shouldBe all
  }

  "edit by ids" should "scale, set and add on content paths, including nested ones" in {
    val dir = Files.createTempDirectory("m")
    val srcs = scenario(dir)
    val f = mods(dir, "a.json", """{"op":"edit","ids":[6],"scale":{"data.content.speedLimit":0.5},"set":{"data.content.lanes":3,"data.content.extra.gap":2.5},"add":{"data.content.lanes":1}}""")
    ScenarioModifications.prepare(srcs, ScenarioModifications.Settings(List(f))).isRight shouldBe true
    val out = ScenarioModifications(actors(dir, "links.json"))
    val l6 = tree(out.find(_.id == 6).get)
    l6.get("speedLimit").asDouble() shouldBe 20.0
    l6.get("lanes").asInt() shouldBe 4                    // set 3, then add 1 (scale/add run after set)
    l6.get("extra").get("gap").asDouble() shouldBe 2.5
    tree(out.find(_.id == 7).get).get("speedLimit").asInt() shouldBe 60      // untouched
  }

  "remove" should "drop the actor, and refuse to leave dangling references" in {
    val dir = Files.createTempDirectory("m")
    val srcs = scenario(dir)
    val bad = ScenarioModifications.prepare(srcs, ScenarioModifications.Settings(List(mods(dir, "b.json", """{"op":"remove","ids":[1]}"""))))
    bad.left.toOption.get should include("sem destino")
    ScenarioModifications.enabled shouldBe false
    val ok = ScenarioModifications.prepare(srcs, ScenarioModifications.Settings(List(mods(dir, "c.json", """{"op":"remove","ids":[6]}"""))))
    ok.isRight shouldBe true
    ScenarioModifications(actors(dir, "links.json")).map(_.id) shouldBe List(7)
  }

  "add" should "load the new actor from a generated source and always keep it for the partial filter" in {
    val dir = Files.createTempDirectory("m")
    val srcs = scenario(dir)
    val newLink = link(100, 30)
    val r = ScenarioModifications.prepare(srcs, ScenarioModifications.Settings(List(mods(dir, "d.json", s"""{"op":"add","actor":$newLink}"""))))
    val (summary, extra) = r.toOption.get
    summary.added shouldBe 1
    extra should have size 1
    extra.head.classType shouldBe "hybrid.actor.Link"
    val loaded = { val is = new BufferedInputStream(new FileInputStream(new File(extra.head.dataSource.info("path").asInstanceOf[String]))); try JsonStreamingUtil.createParser(is)._2.toList finally is.close() }
    loaded.map(_.id) shouldBe List(100)
    ScenarioModifications.addedIds shouldBe Set(100L)
  }

  "remove + add of the same id" should "replace the actor in place (same class)" in {
    val dir = Files.createTempDirectory("m")
    val srcs = scenario(dir)
    val replacement = link(7, 5)
    val r = ScenarioModifications.prepare(srcs, ScenarioModifications.Settings(List(mods(dir, "e.json", s"""{"op":"remove","ids":[7]},{"op":"add","actor":$replacement}"""))))
    r.toOption.get._2 shouldBe empty
    tree(ScenarioModifications(actors(dir, "links.json")).find(_.id == 7).get).get("speedLimit").asInt() shouldBe 5
  }

  "add" should "accept a new class declared by an empty source" in {
    val dir = Files.createTempDirectory("m")
    val empty = source("signals", write(dir, "signals.json", "[]")).copy(classType = "hybrid.actor.TrafficSignal")
    val actor = """{"id":100,"typeActor":"hybrid.actor.TrafficSignal","data":{"dataType":"d","content":{}}}"""
    val r = ScenarioModifications.prepare(scenario(dir) :+ empty,
      ScenarioModifications.Settings(List(mods(dir, "new-class.json", s"""{"op":"add","actor":$actor}"""))))
    r.toOption.get._1.added shouldBe 1
    r.toOption.get._2.head.classType shouldBe "hybrid.actor.TrafficSignal"
  }

  "files" should "apply in order, so a later file sees the earlier one" in {
    val dir = Files.createTempDirectory("m")
    val srcs = scenario(dir)
    val f1 = mods(dir, "f1.json", """{"op":"edit","ids":[6],"scale":{"data.content.speedLimit":0.5}}""")
    val f2 = mods(dir, "f2.json", """{"op":"edit","ids":[6],"scale":{"data.content.speedLimit":0.5}}""")
    ScenarioModifications.prepare(srcs, ScenarioModifications.Settings(List(f1, f2))).isRight shouldBe true
    tree(ScenarioModifications(actors(dir, "links.json")).find(_.id == 6).get).get("speedLimit").asDouble() shouldBe 10.0
  }

  "invalid files" should "fail the load with a readable reason" in {
    val dir = Files.createTempDirectory("m")
    val srcs = scenario(dir)
    def err(ops: String) = ScenarioModifications.prepare(srcs, ScenarioModifications.Settings(List(mods(dir, "x.json", ops)))).left.toOption.getOrElse("")
    err("""{"op":"edit","ids":[999],"set":{"data.content.a":1}}""") should include("999")
    err("""{"op":"edit","select":{"class":"Link"},"scale":{"data.content.speedLimit":0.5}}""") should include("seletores")
    err("""{"op":"edit","ids":[6],"set":{"id":9}}""") should include("não pode ser alterado")
    err("""{"op":"frobnicate"}""") should include("desconhecida")
    err(s"""{"op":"add","actor":${link(6, 1)}}""") should include("já existe")
    err("""{"op":"add","actor":{"id":50,"typeActor":"hybrid.actor.Bus","data":{"dataType":"d","content":{}}}}""") should include("nenhuma fonte")
    ScenarioModifications.prepare(srcs, ScenarioModifications.Settings(List(dir.resolve("nope.json").toString))).isLeft shouldBe true
    ScenarioModifications.prepare(srcs, ScenarioModifications.Settings(List(write(dir, "wrong.json", """{"schema":"other","operations":[]}""")))).isLeft shouldBe true
    ScenarioModifications.enabled shouldBe false
  }

  "map operations" should "be skipped with a warning" in {
    val dir = Files.createTempDirectory("m")
    val r = ScenarioModifications.prepare(scenario(dir), ScenarioModifications.Settings(List(mods(dir, "g.json", """{"op":"map.add_edge","from":1,"to":2}"""))))
    r.toOption.get._1.warnings.exists(_.contains("map.add_edge")) shouldBe true
  }
}
