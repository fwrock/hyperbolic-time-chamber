package org.interscity.htc
package system.broker.kafka.examples

import system.broker.kafka.abstraction._
import system.broker.kafka.abstraction.avro.HTCAvroConverters._

import org.apache.pekko.actor.{ActorSystem, Props}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import java.time.Instant

/**
 * Comparação de performance entre JSON (Jackson) e Avro para serialização Kafka
 * 
 * RESULTADOS ESPERADOS:
 * - Avro: 3-5x mais rápido na serialização/deserialização
 * - Avro: 30-50% menor tamanho de payload
 * - Avro: Melhor para dados estruturados com schema fixo
 * - JSON: Melhor para flexibilidade e debugging
 */
object KafkaPerformanceComparison extends App {
  
  implicit val system: ActorSystem = ActorSystem("performance-test")
  implicit val ec: ExecutionContext = system.dispatcher
  
  println("🚀 Comparando performance JSON vs Avro para Kafka...")
  
  // Dados de teste
  val vehiclePositions = generateTestVehiclePositions(10000)
  val trafficReports = generateTestTrafficReports(5000)
  
  println(s"📊 Dados de teste gerados:")
  println(s"   - ${vehiclePositions.size} posições de veículos")
  println(s"   - ${trafficReports.size} relatórios de tráfego")
  println()
  
  // Testar serialização de posições de veículos
  println("🚗 === TESTE: POSIÇÕES DE VEÍCULOS ===")
  testVehiclePositionSerialization(vehiclePositions)
  println()
  
  // Testar serialização de relatórios de tráfego
  println("📈 === TESTE: RELATÓRIOS DE TRÁFEGO ===")
  testTrafficReportSerialization(trafficReports)
  println()
  
  // Teste de throughput Kafka
  println("🔥 === TESTE: THROUGHPUT KAFKA ===")
  testKafkaThroughput(vehiclePositions).foreach { _ =>
    println("✅ Teste de throughput concluído!")
    system.terminate()
  }
  
  def generateTestVehiclePositions(count: Int): List[VehiclePosition] = {
    (1 to count).map { i =>
      VehiclePosition(
        vehicleId = s"vehicle-$i",
        latitude = -23.5489 + (scala.util.Random.nextDouble() - 0.5) * 0.1,
        longitude = -46.6388 + (scala.util.Random.nextDouble() - 0.5) * 0.1,
        speed = scala.util.Random.nextDouble() * 80,
        timestamp = Instant.now().toEpochMilli,
        linkId = Some(s"link-${scala.util.Random.nextInt(1000)}"),
        simulationId = Some("perf-test-sim")
      )
    }.toList
  }
  
  def generateTestTrafficReports(count: Int): List[TrafficReport] = {
    (1 to count).map { i =>
      TrafficReport(
        linkId = s"link-$i",
        congestionLevel = scala.util.Random.nextDouble(),
        avgSpeed = scala.util.Random.nextDouble() * 80,
        vehicleCount = scala.util.Random.nextInt(100),
        timestamp = Instant.now().toEpochMilli,
        simulationId = "perf-test-sim",
        density = Some(scala.util.Random.nextDouble() * 0.8),
        flow = Some(scala.util.Random.nextDouble() * 2000)
      )
    }.toList
  }
  
  def testVehiclePositionSerialization(positions: List[VehiclePosition]): Unit = {
    
    // JSON Serializer
    val jsonSerializer = KafkaSerializerFactory.jackson[VehiclePosition]
    
    // Avro Serializer  
    val avroSerializer = KafkaSerializerFactory.avroVehiclePosition
    
    println("🧪 Testando serialização de posições de veículos...")
    
    // Benchmark JSON
    val jsonStart = System.nanoTime()
    val jsonSerialized = positions.map(jsonSerializer.serialize)
    val jsonSerializeTime = (System.nanoTime() - jsonStart) / 1000000 // ms
    
    val jsonDeserializeStart = System.nanoTime()
    val jsonDeserialized = jsonSerialized.map(data => jsonSerializer.deserialize(data).get)
    val jsonDeserializeTime = (System.nanoTime() - jsonDeserializeStart) / 1000000 // ms
    
    val jsonTotalSize = jsonSerialized.map(_.length).sum
    
    // Benchmark Avro
    val avroStart = System.nanoTime()
    val avroSerialized = positions.map(avroSerializer.serialize)
    val avroSerializeTime = (System.nanoTime() - avroStart) / 1000000 // ms
    
    val avroDeserializeStart = System.nanoTime()
    val avroDeserialized = avroSerialized.map(data => avroSerializer.deserialize(data).get)
    val avroDeserializeTime = (System.nanoTime() - avroDeserializeStart) / 1000000 // ms
    
    val avroTotalSize = avroSerialized.map(_.length).sum
    
    // Resultados
    println("📊 RESULTADOS - Posições de Veículos:")
    println(f"   JSON Serialização:   ${jsonSerializeTime}%,d ms")
    println(f"   JSON Deserialização: ${jsonDeserializeTime}%,d ms")
    println(f"   JSON Total:          ${jsonSerializeTime + jsonDeserializeTime}%,d ms")
    println(f"   JSON Tamanho:        ${jsonTotalSize}%,d bytes (${jsonTotalSize / 1024}%,d KB)")
    println()
    println(f"   Avro Serialização:   ${avroSerializeTime}%,d ms")
    println(f"   Avro Deserialização: ${avroDeserializeTime}%,d ms") 
    println(f"   Avro Total:          ${avroSerializeTime + avroDeserializeTime}%,d ms")
    println(f"   Avro Tamanho:        ${avroTotalSize}%,d bytes (${avroTotalSize / 1024}%,d KB)")
    println()
    
    val speedupSerialization = jsonSerializeTime.toDouble / avroSerializeTime.toDouble
    val speedupDeserialization = jsonDeserializeTime.toDouble / avroDeserializeTime.toDouble
    val speedupTotal = (jsonSerializeTime + jsonDeserializeTime).toDouble / (avroSerializeTime + avroDeserializeTime).toDouble
    val sizeReduction = ((jsonTotalSize - avroTotalSize).toDouble / jsonTotalSize.toDouble) * 100
    
    println(f"🏆 GANHOS AVRO vs JSON:")
    println(f"   Serialização:   ${speedupSerialization}%.2fx mais rápido")
    println(f"   Deserialização: ${speedupDeserialization}%.2fx mais rápido")
    println(f"   Total:          ${speedupTotal}%.2fx mais rápido")
    println(f"   Tamanho:        ${sizeReduction}%.1f%% menor")
  }
  
  def testTrafficReportSerialization(reports: List[TrafficReport]): Unit = {
    
    // JSON Serializer
    val jsonSerializer = KafkaSerializerFactory.jackson[TrafficReport]
    
    // Avro Serializer
    val avroSerializer = KafkaSerializerFactory.avroTrafficReport
    
    println("🧪 Testando serialização de relatórios de tráfego...")
    
    // Benchmark JSON
    val jsonStart = System.nanoTime()
    val jsonSerialized = reports.map(jsonSerializer.serialize)
    val jsonSerializeTime = (System.nanoTime() - jsonStart) / 1000000 // ms
    
    val jsonDeserializeStart = System.nanoTime()
    val jsonDeserialized = jsonSerialized.map(data => jsonSerializer.deserialize(data).get)
    val jsonDeserializeTime = (System.nanoTime() - jsonDeserializeStart) / 1000000 // ms
    
    val jsonTotalSize = jsonSerialized.map(_.length).sum
    
    // Benchmark Avro
    val avroStart = System.nanoTime()
    val avroSerialized = reports.map(avroSerializer.serialize)
    val avroSerializeTime = (System.nanoTime() - avroStart) / 1000000 // ms
    
    val avroDeserializeStart = System.nanoTime()
    val avroDeserialized = avroSerialized.map(data => avroSerializer.deserialize(data).get)
    val avroDeserializeTime = (System.nanoTime() - avroDeserializeStart) / 1000000 // ms
    
    val avroTotalSize = avroSerialized.map(_.length).sum
    
    // Resultados
    println("📊 RESULTADOS - Relatórios de Tráfego:")
    println(f"   JSON Serialização:   ${jsonSerializeTime}%,d ms")
    println(f"   JSON Deserialização: ${jsonDeserializeTime}%,d ms")
    println(f"   JSON Total:          ${jsonSerializeTime + jsonDeserializeTime}%,d ms")
    println(f"   JSON Tamanho:        ${jsonTotalSize}%,d bytes (${jsonTotalSize / 1024}%,d KB)")
    println()
    println(f"   Avro Serialização:   ${avroSerializeTime}%,d ms")
    println(f"   Avro Deserialização: ${avroDeserializeTime}%,d ms")
    println(f"   Avro Total:          ${avroSerializeTime + avroDeserializeTime}%,d ms")
    println(f"   Avro Tamanho:        ${avroTotalSize}%,d bytes (${avroTotalSize / 1024}%,d KB)")
    println()
    
    val speedupSerialization = jsonSerializeTime.toDouble / avroSerializeTime.toDouble
    val speedupDeserialization = jsonDeserializeTime.toDouble / avroDeserializeTime.toDouble
    val speedupTotal = (jsonSerializeTime + jsonDeserializeTime).toDouble / (avroSerializeTime + avroDeserializeTime).toDouble
    val sizeReduction = ((jsonTotalSize - avroTotalSize).toDouble / jsonTotalSize.toDouble) * 100
    
    println(f"🏆 GANHOS AVRO vs JSON:")
    println(f"   Serialização:   ${speedupSerialization}%.2fx mais rápido")
    println(f"   Deserialização: ${speedupDeserialization}%.2fx mais rápido")
    println(f"   Total:          ${speedupTotal}%.2fx mais rápido")
    println(f"   Tamanho:        ${sizeReduction}%.1f%% menor")
  }
  
  def testKafkaThroughput(positions: List[VehiclePosition]): Future[Unit] = {
    
    println("🧪 Testando throughput Kafka (JSON vs Avro)...")
    
    // Publishers
    val jsonPublisher = KafkaAbstractionFactory.Publishers.forSimulationData[VehiclePosition]
    val avroPublisher = KafkaAbstractionFactory.Publishers.forVehiclePositionsAvro
    
    val testPositions = positions.take(1000) // Usar menos dados para o teste Kafka
    
    // Teste JSON
    val jsonStart = System.nanoTime()
    val jsonFutures = testPositions.map { pos =>
      jsonPublisher.publish(pos, KafkaConfig(topic = "test-json-positions"))
    }
    
    Future.sequence(jsonFutures).flatMap { _ =>
      val jsonTime = (System.nanoTime() - jsonStart) / 1000000
      println(f"   JSON Throughput: ${testPositions.size} mensagens em ${jsonTime}%,d ms (${testPositions.size * 1000 / jsonTime.toDouble}%.0f msg/s)")
      
      // Teste Avro
      val avroStart = System.nanoTime()
      val avroFutures = testPositions.map { pos =>
        avroPublisher.publish(pos, KafkaConfig(topic = "test-avro-positions"))
      }
      
      Future.sequence(avroFutures).map { _ =>
        val avroTime = (System.nanoTime() - avroStart) / 1000000
        println(f"   Avro Throughput: ${testPositions.size} mensagens em ${avroTime}%,d ms (${testPositions.size * 1000 / avroTime.toDouble}%.0f msg/s)")
        
        val throughputImprovement = jsonTime.toDouble / avroTime.toDouble
        println(f"🏆 Avro é ${throughputImprovement}%.2fx mais rápido no throughput Kafka!")
      }
    }
  }
}

/**
 * Exemplo de consumer Avro de alta performance
 */
object HighPerformanceAvroConsumer extends App {
  
  implicit val system: ActorSystem = ActorSystem("avro-consumer")
  implicit val ec: ExecutionContext = system.dispatcher
  
  println("🔥 Iniciando consumer Avro de alta performance...")
  
  // Consumer Avro para posições de veículos
  val avroConsumer = KafkaAbstractionFactory.Consumers.forVehiclePositionsAvro(
    topic = "vehicle-positions-avro",
    groupId = "high-performance-processors"
  )
  
  var messageCount = 0
  val startTime = System.currentTimeMillis()
  
  avroConsumer.startConsuming { (position, metadata) =>
    messageCount += 1
    
    if (messageCount % 1000 == 0) {
      val elapsed = System.currentTimeMillis() - startTime
      val throughput = messageCount * 1000.0 / elapsed
      println(f"📊 Processadas ${messageCount}%,d mensagens (${throughput}%.0f msg/s) - Veículo: ${position.vehicleId}")
    }
    
    // Processar posição em tempo real
    if (position.speed > 80.0) {
      println(s"⚠️  Velocidade alta detectada: ${position.vehicleId} a ${position.speed}%.1f km/h")
    }
    
    Future.successful(KafkaSuccess(()))
  }
  
  println("✅ Consumer Avro iniciado! Aguardando mensagens de alta velocidade...")
  
  scala.sys.addShutdownHook {
    println(s"🛑 Finalizando consumer. Total processadas: ${messageCount} mensagens")
    system.terminate()
  }
}