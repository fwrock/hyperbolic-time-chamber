package org.interscity.htc
package model.mobility.types

import model.mobility.collections.Graph

import org.interscity.htc.model.mobility.entity.state.model.{ EdgeGraph, NodeGraph }

type CityMap = Graph[NodeGraph, Double, EdgeGraph]
