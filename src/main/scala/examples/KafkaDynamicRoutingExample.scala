package org.interscity.htc.examples

import org.interscity.htc.model.hybrid.util.{DynamicWeightCache, GPSUtil}
import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost

import scala.concurrent.duration._
import scala.util.Random

/**
 * Example demonstrating Kafka-based dynamic routing.
 * 
 * This example shows:
 * 1. LinkActors publishing traffic conditions to Kafka
 * 2. Route calculation using dynamic weights from local cache
 * 3. Performance comparison with static routing
 */
object KafkaDynamicRoutingExample {

  def main(args: Array[String]): Unit = {
    println("🚦 Kafka Dynamic Routing Example")
    println("=" * 50)
    
    // Simulate LinkActors publishing dynamic costs
    simulateTrafficUpdates()
    
    // Give time for Kafka messages to propagate
    Thread.sleep(2000)
    
    // Calculate routes with dynamic weights
    demonstrateRoutingComparison()
    
    // Show cache statistics
    showCacheStatistics()
  }
  
  /**
   * Simulate multiple LinkActors publishing traffic conditions to Kafka.
   */
  def simulateTrafficUpdates(): Unit = {
    println("\n📡 Simulating LinkActors publishing traffic conditions...")
    
    val links = List(
      "htcaid:link;downtown_main_st",
      "htcaid:link;highway_101_north", 
      "htcaid:link;central_avenue",
      "htcaid:link;business_district_loop",
      "htcaid:link;residential_connector"
    )
    
    links.foreach { linkId =>
      // Simulate different traffic conditions
      val (congestion, incident) = Random.nextFloat() match {
        case x if x < 0.1  => (5.0, true)   // 10% chance of incident
        case x if x < 0.3  => (2.5, false)  // 20% chance of heavy traffic
        case x if x < 0.6  => (1.5, false)  // 30% chance of moderate traffic  
        case _             => (1.0, false)  // 40% chance of free flow
      }
      
      val dynamicCost = DynamicLinkCost(
        linkId = linkId,
        baseCost = 100.0, // Base distance cost
        congestionFactor = congestion,
        currentSpeed = 50.0 / congestion, // Speed inversely related to congestion
        freeFlowSpeed = 50.0,
        vehicleCount = (Random.nextInt(50) * congestion).toInt,
        capacity = 100,
        incidentFactor = if (incident) 500.0 else 0.0,
        lastUpdateTick = 1000,
        timestamp = System.currentTimeMillis()
      )
      
      DynamicWeightCache.publishCost(dynamicCost, ttlSeconds = 300) match {
        case scala.util.Success(_) =>
          val status = if (incident) "🚨 INCIDENT" 
                      else if (congestion > 2.0) "🔴 HEAVY" 
                      else if (congestion > 1.2) "🟡 MODERATE"
                      else "🟢 FREE"
          println(s"  Published $linkId: $status (congestion=${congestion}x, cost=${dynamicCost.totalCost})")
          
        case scala.util.Failure(e) =>
          println(s"  ❌ Failed to publish $linkId: ${e.getMessage}")
      }
    }
    
    println(s"✅ Published traffic conditions for ${links.size} links to Kafka")
  }
  
  /**
   * Demonstrate routing with static vs dynamic weights.
   */
  def demonstrateRoutingComparison(): Unit = {
    println("\n🗺️  Comparing static vs dynamic routing...")
    
    val origin = "htcaid:node;downtown_center"
    val destination = "htcaid:node;airport_terminal"
    
    // Calculate route with static weights
    val startStatic = System.nanoTime()
    val staticRoute = GPSUtil.calcRoute(origin, destination, useDynamicWeights = false)
    val staticTime = (System.nanoTime() - startStatic) / 1_000_000.0
    
    // Calculate route with dynamic weights (Kafka cache)
    val startDynamic = System.nanoTime()
    val dynamicRoute = GPSUtil.calcRoute(origin, destination, useDynamicWeights = true)
    val dynamicTime = (System.nanoTime() - startDynamic) / 1_000_000.0
    
    println(f"⏱️  Performance:")
    println(f"  Static routing:  ${staticTime}%.2f ms")
    println(f"  Dynamic routing: ${dynamicTime}%.2f ms (${dynamicTime/staticTime}%.2fx overhead)")
    
    (staticRoute, dynamicRoute) match {
      case (Some((staticCost, staticPath)), Some((dynamicCost, dynamicPath))) =>
        println(f"\n📊 Route Comparison:")
        println(f"  Static route:  cost=${staticCost}%.1f, segments=${staticPath.size}")
        println(f"  Dynamic route: cost=${dynamicCost}%.1f, segments=${dynamicPath.size}")
        println(f"  Cost difference: ${((dynamicCost - staticCost) / staticCost * 100)}%.1f%%")
        
        if (dynamicCost > staticCost * 1.2) {
          println(s"  🚨 Dynamic routing found significant traffic - avoiding congested areas!")
        } else if (dynamicCost < staticCost * 0.9) {
          println(s"  🟢 Dynamic routing found faster path - using real-time traffic!")
        } else {
          println(s"  ➡️  Routes are similar - traffic conditions normal")
        }
        
      case (None, _) =>
        println("  ❌ Static route calculation failed")
      case (_, None) =>
        println("  ❌ Dynamic route calculation failed") 
      case _ =>
        println("  ❌ Both route calculations failed")
    }
  }
  
  /**
   * Show cache statistics and Kafka consumer health.
   */
  def showCacheStatistics(): Unit = {
    println("\n📈 Cache Statistics:")
    
    val (cacheSize, avgAge, publishCount) = DynamicWeightCache.getStatistics()
    println(f"  Cache size: $cacheSize links")
    println(f"  Average age: ${avgAge}%.1f seconds")
    println(f"  Published: $publishCount costs")
    
    // Test batch weight retrieval (what A* actually uses)
    val linkWeights = Map(
      "htcaid:link;downtown_main_st" -> 100.0,
      "htcaid:link;highway_101_north" -> 200.0,
      "htcaid:link;central_avenue" -> 150.0,
      "htcaid:link;business_district_loop" -> 80.0,
      "htcaid:link;residential_connector" -> 120.0
    )
    
    val startBatch = System.nanoTime()
    val dynamicWeights = DynamicWeightCache.getBatchWeights(linkWeights)
    val batchTime = (System.nanoTime() - startBatch) / 1_000_000.0
    
    println(f"\n⚡ Batch Performance (${linkWeights.size} links):")
    println(f"  Lookup time: ${batchTime}%.3f ms (${batchTime * 1000 / linkWeights.size}%.1f μs per link)")
    
    dynamicWeights.foreach { case (linkId, weight) =>
      val staticWeight = linkWeights(linkId)
      val factor = weight / staticWeight
      val status = if (factor > 3.0) "🚨" else if (factor > 1.5) "🔴" else if (factor > 1.1) "🟡" else "🟢"
      println(f"  $status $linkId: ${staticWeight}%.0f → ${weight}%.0f (${factor}%.1fx)")
    }
    
    println("\n✅ Kafka dynamic routing demonstration complete!")
    println("💡 Benefits:")
    println("   - Ultra-fast local cache access (~μs)")
    println("   - Automatic traffic condition distribution")
    println("   - Real-world eventual consistency")
    println("   - Scales horizontally with Kafka partitions")
  }
}