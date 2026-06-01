package org.interscity.htc
package model.hybrid.entity.state.model

/** One stop in an ordered route sequence.
  *
  * @param stopId
  *   Transit-map stop `id` (e.g. `"htcaid:stop;bus_sptrans_301790"`). Must match a `TransitStop.id`
  *   value in `transit_map.json`.
  * @param travelTimeFromPrevSeconds
  *   Scheduled travel time from the immediately preceding stop, in seconds. `0` for the first stop
  *   in the sequence.
  */
case class RouteStop(
  stopId: String,
  travelTimeFromPrevSeconds: Int
)

/** A transit route with its full ordered stop sequence, used by the RAPTOR router.
  *
  * JSON file format (flat array in `transit_routes.json`):
  * {{{
  * [
  *   {
  *     "lineId": "1012-10",
  *     "stopType": "bus",
  *     "headwaySeconds": 600,
  *     "stops": [
  *       { "stopId": "htcaid:stop;bus_sptrans_301790", "travelTimeFromPrevSeconds": 0 },
  *       { "stopId": "htcaid:stop;bus_sptrans_301764", "travelTimeFromPrevSeconds": 80 }
  *     ]
  *   }
  * ]
  * }}}
  *
  * The `lineId` value must match the line labels stored in `TransitStop.lines` in the transit map.
  *
  * @param lineId
  *   Line label — must match entries in [[TransitStop.lines]] (e.g. `"1012-10"`).
  * @param stopType
  *   `"bus"` or `"subway"`.
  * @param headwaySeconds
  *   Expected service interval in seconds. Used to compute expected boarding wait time
  *   (`headwaySeconds / 2`) in the frequency-based RAPTOR algorithm.
  * @param stops
  *   Ordered stop sequence from first terminal to last terminal.
  */
case class TransitRoute(
  lineId: String,
  stopType: String,
  headwaySeconds: Int,
  stops: List[RouteStop]
)
