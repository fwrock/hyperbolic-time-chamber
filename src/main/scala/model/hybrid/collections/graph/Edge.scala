package org.interscity.htc
package model.hybrid.collections.graph

case class Edge[V, W, L](source: V, target: V, weight: W, label: L)
