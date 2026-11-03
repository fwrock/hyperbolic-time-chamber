package org.interscity.htc
package core.modifications

import core.entity.actor.{ ActorSimulation, ShardActorId }
import core.entity.configuration.{ ActorDataSource, DataSource, ModificationsSimulation }
import core.enumeration.{ CreationTypeEnum, DataSourceTypeEnum, EntityLifecycleEnum, LoadingStrategyEnum }
import core.util.{ JsonStreamingUtil, SqliteActorSimulationUtil }

import com.fasterxml.jackson.databind.node.{ ArrayNode, DoubleNode, LongNode, ObjectNode }
import com.fasterxml.jackson.databind.{ JsonNode, ObjectMapper }
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.Config

import java.io.{ BufferedInputStream, File, FileInputStream }
import java.nio.file.Files
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Scenario modifications (`htc.modifications.v1`) applied while the scenario loads.
  *
  * Off unless `simulation.json` has a `modifications` block (or `htc.modifications.files` is set). `LoadDataManager` calls [[prepare]]
  * once, before any actor is created: it reads the files, validates them against the actors of every source, and stores what the
  * loaders need. The loaders then pass every chunk of actors through [[apply]]: removed actors disappear, edited ones are changed,
  * and the actors the files add are loaded from a generated EAGER source (returned by [[prepare]]).
  *
  * Supported in this version: `add`, and `remove` / `edit` by `ids`. Selectors (`select`) and the reserved `map.*` operations are not
  * applied: a selector makes the load fail (the result would silently differ from what the file says), `map.*` are skipped with a warning.
  * The state is per JVM (the loaders run beside the load manager).
  */
object ScenarioModifications {
  val Schema = "htc.modifications.v1"
  private val Protected = Set("id", "typeActor")

  final case class Settings(files: List[String]) { def enabled: Boolean = files.nonEmpty }

  def settings(config: Config): Settings = {
    val files = Try(config.getStringList("htc.modifications.files").asScala.toList)
      .getOrElse(Try(config.getString("htc.modifications.files").split(",").toList).getOrElse(Nil))
    Settings(files.map(_.trim).filter(_.nonEmpty))
  }
  def settings(block: ModificationsSimulation): Settings = Settings(block.files.map(_.trim).filter(_.nonEmpty))

  private final case class Edit(set: Map[String, JsonNode], scale: Map[String, Double], add: Map[String, Double])
  private final class Active(val removed: Set[Long], val edits: Map[Long, List[Edit]], val replaced: Map[Long, ActorSimulation])

  final case class Summary(files: Int, operations: Int, added: Int, removed: Int, edited: Int, warnings: List[String])

  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
  @volatile private var active: Option[Active] = None
  @volatile private var added: Set[Long] = Set.empty

  def enabled: Boolean = active.isDefined
  /** Ids of the actors the modifications added (the partial filter always keeps them). */
  def addedIds: Set[Long] = added

  def clear(): Unit = { active = None; added = Set.empty }

  /** The actor after the modifications, or none when it was removed. */
  def transform(actor: ActorSimulation): Option[ActorSimulation] = active match {
    case None => Some(actor)
    case Some(a) =>
      if (a.removed.contains(actor.id)) None
      else {
        val base = a.replaced.getOrElse(actor.id, actor)       // remove + add of the same id = replace (same class, same source)
        a.edits.get(actor.id) match {
          case None      => Some(base)
          case Some(ops) => Some(ops.foldLeft(base)(applyEdit))
        }
      }
  }

  def apply(actors: List[ActorSimulation]): List[ActorSimulation] =
    if (active.isEmpty) actors else actors.flatMap(a => transform(a))

  // ---- editing -------------------------------------------------------------------------------------------------------------

  private def applyEdit(actor: ActorSimulation, edit: Edit): ActorSimulation = {
    var current = actor
    val contentTree: ObjectNode = mapper.valueToTree[JsonNode](actor.data.content) match {
      case o: ObjectNode => o
      case _             => mapper.createObjectNode()
    }
    var contentChanged = false
    def numeric(path: String, f: Double => Double): Unit = {
      val rel = path.stripPrefix("data.content.")
      val parts = rel.split('.')
      val parent = parts.dropRight(1).foldLeft[JsonNode](contentTree)((n, k) => if (n == null) null else n.get(k))
      parent match {
        case o: ObjectNode =>
          val key = parts.last
          Option(o.get(key)).filter(_.isNumber).foreach { n =>
            val v = f(n.asDouble())
            o.set[JsonNode](key, if (n.isIntegralNumber && v == math.rint(v)) LongNode.valueOf(v.toLong) else DoubleNode.valueOf(v))
            contentChanged = true
          }
        case _ =>
      }
    }
    def setPath(path: String, value: JsonNode): Unit = {
      val parts = path.stripPrefix("data.content.").split('.')
      var node: ObjectNode = contentTree
      parts.dropRight(1).foreach { k =>
        node = node.get(k) match {
          case o: ObjectNode => o
          case _             => node.putObject(k)
        }
      }
      node.set[JsonNode](parts.last, value)
      contentChanged = true
    }
    edit.set.foreach {
      case (p, v) if p.startsWith("data.content.") => setPath(p, v)
      case (p, v) if p.startsWith("dependencies.") =>
        val parts = p.stripPrefix("dependencies.").split('.')
        val deps = Option(current.relationships).getOrElse(Map.empty[String, ShardActorId])
        val updated =
          if (parts.length == 1) mapper.treeToValue(v, classOf[ShardActorId])
          else deps.get(parts(0)).map(d => d.copy(entityId = v.asLong())).getOrElse(d0(parts(0), v))
        current = current.copy(relationships = deps + (parts(0) -> updated))
      case _ =>
    }
    edit.scale.foreach { case (p, f) => numeric(p, _ * f) }
    edit.add.foreach { case (p, d) => numeric(p, _ + d) }
    if (contentChanged)
      current = current.copy(data = current.data.copy(content = mapper.treeToValue(contentTree, classOf[Object])))
    current
  }
  private def d0(name: String, v: JsonNode): ShardActorId = ShardActorId(v.asLong(), "")

  // ---- preparation / validation --------------------------------------------------------------------------------------------

  private def readSources(sources: List[ActorDataSource])(f: ActorSimulation => Unit): Unit =
    sources.foreach { source =>
      val path = source.dataSource.info("path").asInstanceOf[String]
      val (closeable, iterator): (AutoCloseable, Iterator[ActorSimulation]) = source.dataSource.sourceType match {
        case DataSourceTypeEnum.json =>
          val is = new BufferedInputStream(new FileInputStream(new File(path)))
          (is, JsonStreamingUtil.createParser(is)._2)
        case DataSourceTypeEnum.sqlite => SqliteActorSimulationUtil.openIterator(path)
      }
      try iterator.foreach(f) finally closeable.close()
    }

  /** Reads and validates the files and activates the modifications. `Left` = the load must fail. On success returns the summary and the
    * EAGER sources (one per class) that load the added actors.
    */
  def prepare(sources: List[ActorDataSource], s: Settings): Either[String, (Summary, List[ActorDataSource])] = {
    val errors = mutable.ListBuffer.empty[String]
    val warnings = mutable.ListBuffer.empty[String]
    val classes = mutable.HashMap.empty[Long, String]
    val refs = mutable.HashMap.empty[Long, Set[Long]]
    readSources(sources) { a =>
      classes(a.id) = a.typeActor
      refs(a.id) = Option(a.relationships).map(_.values.map(_.entityId).toSet).getOrElse(Set.empty)
    }
    // An empty source still declares a shard region (e.g. traffic lights added only by a what-if).
    val sourceClasses = classes.values.toSet ++ sources.map(_.classType)
    val removed = mutable.Set.empty[Long]
    val addedActors = mutable.LinkedHashMap.empty[Long, (ActorSimulation, JsonNode)]
    val replaced = mutable.HashMap.empty[Long, ActorSimulation]
    val edits = mutable.HashMap.empty[Long, mutable.ListBuffer[Edit]]
    val exists = (id: Long) => (classes.contains(id) || addedActors.contains(id)) && !removed.contains(id)   // replaced ids stay in `classes`
    var operations = 0

    s.files.foreach { file =>
      val root = Try(mapper.readTree(new File(file))).toEither.left.map(e => s"$file: não foi possível ler (${e.getMessage})")
      root match {
        case Left(m) => errors += m
        case Right(tree) =>
          if (Option(tree.get("schema")).map(_.asText()).getOrElse("") != Schema) errors += s"$file: schema deve ser $Schema"
          val ops = Option(tree.get("operations")).collect { case a: ArrayNode => a.elements().asScala.toList }.getOrElse { errors += s"$file: 'operations' deve ser uma lista"; Nil }
          ops.zipWithIndex.foreach { case (op, n) =>
            operations += 1
            val where = s"${new File(file).getName}#$n"
            val kind = Option(op.get("op")).map(_.asText()).getOrElse("")
            def ids: Option[List[Long]] = Option(op.get("ids")).collect { case a: ArrayNode => a.elements().asScala.map(_.asLong(-1)).toList }
            if (kind.startsWith("map.")) warnings += s"$where: $kind (mapa/infraestrutura) não é aplicada por este engine"
            else if (op.has("select")) errors += s"$where: seletores (select) ainda não são aplicados pelo engine; use ids"
            else kind match {
              case "add" =>
                val node = op.get("actor")
                Try(mapper.treeToValue(node, classOf[ActorSimulation])).toEither match {
                  case Left(e) => errors += s"$where: ator inválido (${e.getMessage.take(120)})"
                  case Right(actor) =>
                    val isReplacement = removed.contains(actor.id)
                    if (isReplacement && classes.get(actor.id).exists(_ != actor.typeActor)) errors += s"$where: substituir o id ${actor.id} exige a mesma classe (${classes(actor.id)})"
                    else if (!isReplacement && (classes.contains(actor.id) || replaced.contains(actor.id))) errors += s"$where: o id ${actor.id} já existe no cenário"
                    else if (addedActors.contains(actor.id)) errors += s"$where: o id ${actor.id} já foi adicionado"
                    if (!isReplacement && !sourceClasses.contains(actor.typeActor) && !addedActors.values.exists(_._1.typeActor == actor.typeActor))
                      errors += s"$where: o cenário não tem nenhuma fonte da classe ${actor.typeActor}; o engine não registra a região dela"
                    Option(actor.relationships).foreach(_.foreach { case (name, r) =>
                      if (r.entityId != actor.id && !exists(r.entityId)) errors += s"$where: dependência '$name' aponta para o id ${r.entityId}, que não existe"
                    })
                    if (isReplacement) { removed -= actor.id; replaced(actor.id) = actor }
                    else addedActors(actor.id) = (actor, node)
                    refs(actor.id) = Option(actor.relationships).map(_.values.map(_.entityId).toSet).getOrElse(Set.empty)
                }
              case "remove" =>
                ids match {
                  case None => errors += s"$where: remove precisa de ids"
                  case Some(l) =>
                    l.filterNot(exists).foreach(i => errors += s"$where: o id $i não existe")
                    removed ++= l.filter(exists)
                }
              case "edit" =>
                ids match {
                  case None => errors += s"$where: edit precisa de ids"
                  case Some(l) =>
                    def obj(k: String) = Option(op.get(k)).collect { case o: ObjectNode => o.fields().asScala.map(e => e.getKey -> e.getValue).toMap }.getOrElse(Map.empty[String, JsonNode])
                    val set = obj("set")
                    val scale = obj("scale").view.mapValues(_.asDouble()).toMap
                    val add = obj("add").view.mapValues(_.asDouble()).toMap
                    if (set.isEmpty && scale.isEmpty && add.isEmpty) errors += s"$where: edit precisa de set, scale e/ou add"
                    (set.keys ++ scale.keys ++ add.keys).foreach { p =>
                      if (Protected.contains(p)) errors += s"$where: '$p' não pode ser alterado"
                      else if (!p.startsWith("data.content.") && !p.startsWith("dependencies.")) errors += s"$where: caminho '$p' deve começar com data.content. ou dependencies."
                    }
                    l.filterNot(exists).foreach(i => errors += s"$where: o id $i não existe")
                    l.filter(exists).foreach(i => edits.getOrElseUpdate(i, mutable.ListBuffer.empty) += Edit(set, scale, add))
                }
              case other => errors += s"$where: op desconhecida '$other'"
            }
          }
      }
    }
    val dangling = refs.iterator.filterNot(r => removed.contains(r._1)).flatMap { case (a, rs) => rs.filter(removed.contains).map(r => (a, r)) }.take(6).toList
    if (dangling.nonEmpty)
      errors += s"a remoção deixa referências sem destino (ator→removido): ${dangling.take(5).map(d => s"${d._1}→${d._2}").mkString(", ")}${if (dangling.size > 5) " …" else ""}; remova também quem os referencia"

    if (errors.nonEmpty) Left(errors.take(10).mkString("; ") + (if (errors.size > 10) s" (+${errors.size - 10})" else ""))
    else {
      active = Some(new Active(removed.toSet, edits.view.mapValues(_.toList).toMap, replaced.toMap))
      added = addedActors.keySet.toSet ++ replaced.keySet
      val extra = addedActors.values.groupBy(_._1.typeActor).toList.map { case (cls, group) =>
        val file = Files.createTempFile(s"htc-added-${cls.split('.').last}-", ".json").toFile
        file.deleteOnExit()
        val array = mapper.createArrayNode()
        group.foreach(g => array.add(g._2))
        mapper.writeValue(file, array)
        ActorDataSource(
          id = s"modifications_added_${cls.split('.').last}", classType = cls, creationType = CreationTypeEnum.LoadBalancedDistributed,
          dataSource = DataSource(DataSourceTypeEnum.json, Map("path" -> file.getAbsolutePath)),
          loadingStrategy = LoadingStrategyEnum.EAGER, entityLifecycle = EntityLifecycleEnum.STATIC)
      }
      Right((Summary(s.files.size, operations, addedActors.size + replaced.size, active.get.removed.size, edits.size, warnings.toList), extra))
    }
  }
}
