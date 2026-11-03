package org.interscity.htc
package core.cluster

import core.metrics.core.ActorMetrics

import io.prometheus.client.Gauge
import org.apache.pekko.actor.{ Actor, ActorSystem, Address, Props }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.cluster.ClusterEvent.*
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

/** Identity of this JVM inside a distributed run: a rank and a human-readable label.
  *
  * Why: to compare distributed executions (different allocation approaches, different cluster sizes)
  * the platform needs to know, for every message, which node sent it and which node received it.
  * This object is the single static source of "who am I" for the whole JVM: actors read [[rank]] /
  * [[stamp]] and the receiving side compares it with the sender's stamp carried in the event.
  *
  * Nothing is configured per node. Every node derives the same rank table from the cluster
  * membership (members sorted by host:port, so the table is identical everywhere) and the rank
  * indexes a fixed list of labels chosen by `htc.node.label-strategy`:
  *   - rank: `node-0`, `node-1`, ...
  *   - animals: Brazilian animals, `Onça-pintada`, `Capivara`, ...
  *   - cities: Brazilian cities, `Pereiro`, `São Paulo`, ...
  * The table is computed while the cluster forms and FROZEN once `pekko.cluster.min-nr-of-members`
  * members are Up (the simulation does not start earlier), so it cannot change during a run.
  * `HTC_NODE_RANK` forces the rank (e.g. a StatefulSet ordinal) when run-to-run stable names matter.
  *
  * Wire value ([[stamp]]): `rank + 1`, with 0 meaning "unknown / not stamped" -- proto3 writes a
  * zero field as zero bytes, so unstamped messages cost nothing. Only the number travels; the label
  * is looked up locally, which keeps the per-message overhead to a few bytes.
  */
object NodeIdentity {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Largest rank that fits the one-byte varint of the wire stamp; larger clusters still work, the stamp just grows. */
  val Unknown = 0

  val Animals: Vector[String] = Vector(
    "Onça-pintada", "Capivara", "Tamanduá-bandeira", "Arara-azul", "Boto-cor-de-rosa", "Lobo-guará",
    "Tatu-bola", "Mico-leão-dourado", "Jacaré-açu", "Preguiça-de-coleira", "Tucano-toco", "Sucuri",
    "Quati", "Anta", "Ariranha", "Peixe-boi", "Jaguatirica", "Gavião-real", "Seriema", "Bem-te-vi",
    "Curió", "Sabiá-laranjeira", "Beija-flor", "Muriqui", "Macaco-prego", "Cachorro-do-mato", "Gambá",
    "Veado-campeiro", "Pirarucu", "Tambaqui", "Jabuti", "Tartaruga-da-amazônia"
  )

  val Cities: Vector[String] = Vector(
    "Pereiro", "São Paulo", "Recife", "Salvador", "Fortaleza", "Belém", "Manaus", "Curitiba",
    "Porto Alegre", "Belo Horizonte", "Brasília", "Goiânia", "Natal", "João Pessoa", "Maceió", "Aracaju",
    "São Luís", "Teresina", "Cuiabá", "Campo Grande", "Florianópolis", "Vitória", "Palmas", "Porto Velho",
    "Rio Branco", "Macapá", "Boa Vista", "Campina Grande", "Juazeiro do Norte", "Mossoró", "Petrolina",
    "Ribeirão Preto"
  )

  /** Label of `rank` for a strategy name (`rank`, `animals`, `cities`). Unique even past the end of a list:
    * the wrap-around gets a numeric suffix (`Pereiro-2`).
    */
  def labelFor(strategy: String, rank: Int): String = {
    require(rank >= 0, s"rank must be non-negative, got $rank")
    def fromList(list: Vector[String]): String = {
      val round = rank / list.size
      val base = list(rank % list.size)
      if (round == 0) base else s"$base-${round + 1}"
    }
    strategy.trim.toLowerCase match {
      case "animals" => fromList(Animals)
      case "cities"  => fromList(Cities)
      case _         => s"node-$rank"
    }
  }

  /** Position of `self` among `members`, ordered by host then port. Identical on every node that sees the same member set. */
  def rankOf(self: Address, members: Iterable[Address]): Option[Int] = {
    val ordered = (members.toSet + self).toVector.sortBy(a => (a.host.getOrElse(""), a.port.getOrElse(0)))
    Some(ordered.indexOf(self)).filter(_ >= 0)
  }

  // --- JVM-wide state (a static global on purpose: every actor on this node reads it) ---

  @volatile private var currentRank: Int = 0
  @volatile private var currentStrategy: String = "rank"
  @volatile private var frozen: Boolean = false
  @volatile private var originEnabled: Boolean = true
  @volatile private var selfAddress: String = ""
  @volatile private var frozenMembers: Int = 0

  def rank: Int = currentRank
  def strategy: String = currentStrategy
  def isFrozen: Boolean = frozen

  /** This node's label. */
  def name: String = labelFor(currentStrategy, currentRank)

  /** This node as a stamp (`rank + 1`), regardless of whether message stamping is enabled. */
  def selfStamp: Int = currentRank + 1

  /** Value written into outgoing events (`rank + 1`; 0 when stamping is disabled). */
  def stamp: Int = if (originEnabled) selfStamp else Unknown

  /** Label of a wire value; `unknown` for 0. */
  def nameOfStamp(stamp: Int): String = if (stamp <= 0) "unknown" else labelFor(currentStrategy, stamp - 1)

  /** Label for a report column: empty when there is no origin node (a spontaneous event, not a message). */
  def originLabel(stamp: Int): String = if (stamp <= 0) "" else labelFor(currentStrategy, stamp - 1)

  // --- per-message accounting (hot path: one map lookup + two counter increments) ---

  private final case class RecvKey(stamp: Int, eventType: String, fromClass: String, toClass: String)
  private final class RecvCounters(val byNode: io.prometheus.client.Counter.Child, val byClass: io.prometheus.client.Counter.Child)

  private val receiveCounters = new ConcurrentHashMap[RecvKey, RecvCounters]()

  // Event types come from a closed set in practice, but the wire has an escape hatch for free-form strings, so the
  // label is capped: past MaxEventTypes distinct values everything new is counted as "other" (bounded cardinality).
  private val MaxEventTypes = 64
  private val seenEventTypes = ConcurrentHashMap.newKeySet[String]()
  private def boundedEventType(eventType: String): String =
    if (seenEventTypes.contains(eventType)) eventType
    else if (seenEventTypes.size < MaxEventTypes) { seenEventTypes.add(eventType); eventType }
    else "other"

  private val shortClassNames = new ConcurrentHashMap[String, String]()
  /** `core.x.Car` / `model.hybrid.actor.Car` -> `Car`. Cached: this runs once per message. */
  def shortClass(fqcn: String): String =
    if (fqcn == null || fqcn.isEmpty) "unknown"
    else shortClassNames.computeIfAbsent(fqcn, f => f.substring(f.lastIndexOf('.') + 1))

  /** Counts one interaction received by this node: sender node (`senderStamp`), message type, sender and receiver actor class. */
  def recordReceive(senderStamp: Int, eventType: String, fromClass: String, toClass: String): Unit = {
    val bounded = boundedEventType(eventType)               // before the key, so the cache itself stays bounded too
    val key = RecvKey(senderStamp, bounded, fromClass, toClass)
    var counters = receiveCounters.get(key)
    if (counters == null) {
      val from = nameOfStamp(senderStamp)
      val locality = if (senderStamp <= 0) "unknown" else if (senderStamp - 1 == currentRank) "local" else "remote"
      val created = new RecvCounters(
        ActorMetrics.messagesByNode.labels(from, name, locality, bounded),
        ActorMetrics.messagesByClass.labels(shortClass(fromClass), shortClass(toClass), locality)
      )
      counters = Option(receiveCounters.putIfAbsent(key, created)).getOrElse(created)
    }
    counters.byNode.inc()
    counters.byClass.inc()
  }

  // --- address -> label (for events that name nodes by cluster address, e.g. shard migrations) ---

  @volatile private var addressRanks: Map[(String, Int), Int] = Map.empty

  /** Label of the node at `address`, or its host:port when it is not in the rank table. */
  def labelOfAddress(address: Address): String =
    addressRanks.get((address.host.getOrElse(""), address.port.getOrElse(0))) match {
      case Some(r) => labelFor(currentStrategy, r)
      case None    => s"${address.host.getOrElse("?")}:${address.port.getOrElse(0)}"
    }

  // --- startup ---

  private val info: Gauge = Gauge.build()
    .name("htc_node_info")
    .help("This node: label, rank, cluster address and label strategy (value is always 1)")
    .labelNames("node", "rank", "address", "strategy")
    .register()

  private def publish(): Unit = {
    info.clear()
    info.labels(name, currentRank.toString, selfAddress, currentStrategy).set(1)
  }

  /** Reads `htc.node.*`, subscribes to cluster membership and freezes the table once the quorum is Up. */
  def start(system: ActorSystem): Unit = {
    val config = system.settings.config
    def str(path: String, default: String) = scala.util.Try(config.getString(path)).filter(_.nonEmpty).getOrElse(default)
    currentStrategy = str("htc.node.label-strategy", "rank").toLowerCase
    originEnabled = scala.util.Try(config.getBoolean("htc.node.message-origin")).getOrElse(true)
    val cluster = Cluster(system)
    selfAddress = cluster.selfAddress.toString
    val forced = sys.env.get("HTC_NODE_RANK").flatMap(_.trim.toIntOption).filter(_ >= 0)
    forced.foreach { r =>
      currentRank = r
      frozen = true
      publish()
      logger.info(s"Node identity (forced by HTC_NODE_RANK): rank=$r name=$name strategy=$currentStrategy")
    }
    if (forced.isEmpty) {
      val minMembers = scala.util.Try(config.getInt("pekko.cluster.min-nr-of-members")).getOrElse(1)
      system.actorOf(Props(new Listener(cluster, minMembers)), "node-identity")
    }
  }

  private class Listener(cluster: Cluster, minMembers: Int) extends Actor {
    override def preStart(): Unit = cluster.subscribe(self, InitialStateAsEvents, classOf[MemberEvent])
    override def postStop(): Unit = cluster.unsubscribe(self)

    private def refresh(): Unit =
      if (!frozen) {
        val up = cluster.state.members.filter(m => m.status == MemberStatus.Up || m.status == MemberStatus.WeaklyUp)
        rankOf(cluster.selfAddress, up.map(_.address)).foreach { r =>
          currentRank = r
          addressRanks = (up.map(_.address) + cluster.selfAddress).toVector
            .sortBy(a => (a.host.getOrElse(""), a.port.getOrElse(0)))
            .zipWithIndex.map { case (a, i) => (a.host.getOrElse(""), a.port.getOrElse(0)) -> i }.toMap
          if (up.size >= minMembers) {
            frozen = true
            frozenMembers = up.size
            logger.info(
              s"Node identity frozen: rank=$r name=$name strategy=$currentStrategy members=${up.size} " +
                s"table=${up.toVector.map(_.address).sortBy(a => (a.host.getOrElse(""), a.port.getOrElse(0))).zipWithIndex.map { case (a, i) => s"$i=${labelFor(currentStrategy, i)}@$a" }.mkString(", ")}"
            )
          }
          publish()
        }
      }

    private var warnedGrowth = false

    override def receive: Receive = {
      case _: MemberEvent =>
        refresh()
        // The table was frozen at `min-nr-of-members`. Nodes that join later were not part of it, so ranks could
        // collide between nodes that froze at different moments. Say so instead of silently mislabelling traffic.
        val up = cluster.state.members.count(m => m.status == MemberStatus.Up || m.status == MemberStatus.WeaklyUp)
        if (frozen && up > frozenMembers && !warnedGrowth) {
          warnedGrowth = true
          logger.warn(
            s"Node identity: the cluster has $up members but the rank table was frozen with $frozenMembers " +
              "(pekko.cluster.min-nr-of-members). Ranks may be inconsistent between nodes; set min-nr-of-members to the " +
              "real cluster size, or force each node's rank with HTC_NODE_RANK."
          )
        }
    }
  }
}
