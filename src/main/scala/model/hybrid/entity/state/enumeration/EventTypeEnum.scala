package org.interscity.htc
package model.hybrid.entity.state.enumeration

enum EventTypeEnum:
  case Arrival,
    Departure,
    Stop,
    Start,
    Pass,
    Enter,
    Exit,
    Change,
    Wait,
    Finish,
    RequestRoute,
    ForwardRoute,
    ReceiveRoute,
    ReceiveSignalState,
    EnterLink,
    LeaveLink,
    EnterNode,
    LeaveNode,
    ReceiveEnterLinkInfo,
    ReceiveLeaveLinkInfo,
    ReceiveEnterNodeInfo,
    ReceiveLeaveNodeInfo,
    TrafficSignalChangeStatus,
    RequestSignalState
