package org.interscity.htc
package model.hybrid.entity.state.enumeration

/** Travel mode for person trips.
  *
  * Replaces scattered string literals ("car", "walk", "subway", etc.) with a type-safe enum.
  * The [[ArrivalLogistics.mode]] field remains a `String` for JSON backward compatibility;
  * use [[ArrivalLogistics.travelMode]] to access the typed value.
  */
enum TravelMode:
  case Walk, Car, Bicycle, Motorcycle, Bus, Subway, Transit, Auto, Unknown

object TravelMode:

  /** Modes that require the person to own a private vehicle. */
  val privateVehicle: Set[TravelMode] = Set(Car, Bicycle, Motorcycle)

  /** Public-transport modes (scheduled service). */
  val publicTransport: Set[TravelMode] = Set(Bus, Subway, Transit)

  /** Parse a raw mode string from configuration / JSON.
    *
    * "transit", "pt", and "mixed" all map to [[Transit]].
    * Unrecognised strings map to [[Unknown]].
    */
  def fromString(s: String): TravelMode = s.toLowerCase.trim match
    case "walk"                      => Walk
    case "car"                       => Car
    case "bicycle"                   => Bicycle
    case "motorcycle"                => Motorcycle
    case "bus"                       => Bus
    case "subway"                    => Subway
    case "transit" | "pt" | "mixed" => Transit
    case "auto"                      => Auto
    case _                           => Unknown
