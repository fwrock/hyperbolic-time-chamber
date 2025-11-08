package org.interscity.htc
package model.hybrid.entity.state.enumeration

enum MovableStatusEnum:
  case Start,
    Ready,
    RouteWaiting,
    Moving,
    WaitingSignal,
    WaitingSignalState,
    Stopped,
    Waiting,
    WaitingLoadPassenger,
    WaitingUnloadPassenger,
    Finished
