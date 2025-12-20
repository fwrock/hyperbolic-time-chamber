package org.interscity.htc
package model.mobility.util

object LinkMessageStats {
  
  @volatile private var totalRequestEnterLink: Long = 0L
  @volatile private var totalEnterLink: Long = 0L
  @volatile private var totalLeaveLink: Long = 0L
  
  def incrementRequestEnterLink(): Unit = totalRequestEnterLink += 1
  def incrementEnterLink(): Unit = totalEnterLink += 1
  def incrementLeaveLink(): Unit = totalLeaveLink += 1
  
  def printStats(): Unit = {
    println(s"\n=== Link Message Statistics ===")
    println(s"Total RequestEnterLink messages: $totalRequestEnterLink")
    println(s"Total EnterLink messages: $totalEnterLink")
    println(s"Total LeaveLink messages: $totalLeaveLink")
    val totalMessages = totalRequestEnterLink + totalEnterLink + totalLeaveLink
    println(s"Total link messages: $totalMessages")
    println(s"================================\n")
  }
  
  def reset(): Unit = {
    totalRequestEnterLink = 0L
    totalEnterLink = 0L
    totalLeaveLink = 0L
  }
}
