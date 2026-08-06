package org.interscity.htc
package model.hybrid.entity.state.enumeration

enum ActorTypeEnum:
  case Car,
    Bus,
    Person,
    Subway,
    Train,
    Bicycle,
    Motorcycle

/** Default MICRO car-following bounds per vehicle type (m/s²), formerly transmitted on every
  * `EnterLinkData` message; now derived here from `actorType` (already carried on that message)
  * so the Link doesn't need them on the wire at all. Values match what each vehicle actor used to
  * override individually (`Movable.microMaxAcceleration`/`microMaxDeceleration`).
  */
extension (actorType: ActorTypeEnum)
  def microMaxAcceleration: Double = actorType match
    case ActorTypeEnum.Bicycle    => 1.0
    case ActorTypeEnum.Bus        => 1.2
    case ActorTypeEnum.Motorcycle => 3.5
    case _                        => 2.6

  def microMaxDeceleration: Double = actorType match
    case ActorTypeEnum.Bicycle    => 3.0
    case ActorTypeEnum.Bus        => 3.5
    case ActorTypeEnum.Motorcycle => 5.0
    case _                        => 4.5
