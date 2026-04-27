package org.interscity.htc
package model.mobility.entity.state.enumeration

enum MovableStatusEnum:
  case Start,
    Ready,
    RouteWaiting,
    Moving,
    WaitingSignal,
    WaitingSignalState,
    WaitingLinkEntry,
    Stopped,
    Waiting,
    WaitingLoadPassenger,
    WaitingUnloadPassenger,
    Finished
