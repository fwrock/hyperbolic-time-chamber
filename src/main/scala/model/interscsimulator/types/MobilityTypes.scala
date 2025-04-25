package org.interscity.htc
package model.interscsimulator.types

import model.interscsimulator.collections.Graph

import org.interscity.htc.model.interscsimulator.entity.state.model.{EdgeGraph, NodeGraph}

type CityMap = Graph[NodeGraph, Double, EdgeGraph]