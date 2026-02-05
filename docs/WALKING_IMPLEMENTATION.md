# Walking Implementation (Mesoscopic Level)

## Overview
The Person actor now supports mesoscopic walking trips, calculating realistic travel times based on the road network topology and typical pedestrian speeds.

## Implementation Details

### Walking Speed
- **Default speed:** 1.4 m/s (5.04 km/h)
- This represents typical pedestrian walking speed on urban streets
- Future enhancements could support variable speeds based on:
  - Terrain/elevation
  - Weather conditions
  - Person attributes (age, fitness)
  - Activity type (rushing vs. leisurely)

### Route Calculation
Walking trips use the same road network as vehicles:
1. Calculate shortest path from origin to destination using A* algorithm (via `GPSUtil.calcRoute`)
2. Sum link lengths along the route to get total distance
3. Calculate walking time: `time = distance / walking_speed`
4. Schedule arrival at destination after calculated duration

### Execution Flow
When a Person actor needs to walk:
```scala
initiateWalkingTrip(origin, destination)
  ↓
GPSUtil.calcRoute(origin, destination)
  ↓
calculateRouteDistance(routeQueue)  // Sum link lengths
  ↓
walkingTime = distance / 1.4 m/s
  ↓
Schedule arrival: onFinishSpontaneous(currentTick + walkingTime)
  ↓
Report walking_trip_start
  ↓
[Time passes - Person waits]
  ↓
advanceToNextActivity() at scheduled arrival
  ↓
Report walking_trip_completed
```

### State Management
During walking:
- `currentTripVehicleId` = `"walking"` (special identifier)
- `currentTripStartTick` = departure time
- Person actor scheduled to wake at arrival tick
- No intermediate events during walk (mesoscopic abstraction)

### Reporting
Two events are reported for each walking trip:

#### 1. walking_trip_start
```scala
{
  "event_type": "walking_trip_start",
  "person_id": "htcaid:person;123",
  "origin": "htcaid:node;456",
  "destination": "htcaid:node;789",
  "distance": 500.0,           // meters
  "walking_time_ticks": 358,   // ticks (≈seconds)
  "arrival_tick": 1500,
  "walking_speed": 1.4,        // m/s
  "tick": 1142
}
```

#### 2. walking_trip_completed
```scala
{
  "event_type": "walking_trip_completed",
  "person_id": "htcaid:person;123",
  "travel_time": 358,          // ticks
  "arrival_tick": 1500,
  "tick": 1500
}
```

## Configuration Example

```json
{
  "sequence": 1,
  "activityType": "Work",
  "nodeId": "htcaid:node;work_location",
  "endTime": "17:00",
  "arrivalLogistics": {
    "mode": "walk"
  }
}
```

## Comparison with Other Modes

| Mode | Implementation | Route Calc | Time Model | Actor Messages |
|------|---------------|-----------|------------|----------------|
| **walk** | Mesoscopic | A* on road network | distance/speed | None (self-scheduled) |
| **car** | Hybrid (meso/micro) | A* on road network | Link traversal events | StartTrip, TripCompleted |
| **bicycle** | Hybrid (meso/micro) | A* on road network | Link traversal events | StartTrip, TripCompleted |
| **motorcycle** | Hybrid (meso/micro) | A* on road network | Link traversal events | StartTrip, TripCompleted |
| **transit** | (Future) | Transit network | Schedule-based | (TBD) |

## Limitations (Current Implementation)

1. **No intermediate position tracking:** Person "teleports" after walking time
2. **Constant speed:** No acceleration/deceleration, hills, obstacles
3. **Uses road network:** Assumes pedestrians follow vehicle paths (no sidewalks, shortcuts, parks)
4. **No capacity constraints:** Unlimited sidewalk capacity
5. **No interactions:** Walkers don't interact with vehicles or other pedestrians

## Future Enhancements

### Short-term (Mesoscopic)
- [ ] Configurable walking speed per person
- [ ] Walking speed factors (age, fitness, urgency)
- [ ] Separate pedestrian network (sidewalks, crosswalks, parks)
- [ ] Elevation/terrain speed adjustment
- [ ] Weather impact on walking speed

### Long-term (Microscopic)
- [ ] Microscopic walking simulation (position tracking)
- [ ] Pedestrian-vehicle interactions at crosswalks
- [ ] Sidewalk capacity and flow dynamics
- [ ] Group walking behavior
- [ ] Dynamic route choice (avoiding crowded areas)

## Testing

### Unit Tests
Test `initiateWalkingTrip` with:
- Valid origin/destination (route exists)
- Invalid origin/destination (no route)
- Zero distance (origin == destination)
- Long distance routes

### Integration Tests
Test person daily schedule with walking:
```
Home → (walk) → Work → (walk) → Shopping → (walk) → Home
```

### Performance
Walking implementation is very lightweight:
- Route calculation: ~O(V log V) for A* (same as vehicles)
- No per-tick updates during walk
- Minimal state overhead (just trip identifiers)

## Related Files
- [Person.scala](../src/main/scala/model/hybrid/actor/Person.scala) - Main implementation
- [PersonState.scala](../src/main/scala/model/hybrid/entity/state/PersonState.scala) - State model
- [GPSUtil.scala](../src/main/scala/model/hybrid/util/GPSUtil.scala) - Route calculation
- [CityMapUtil.scala](../src/main/scala/model/hybrid/util/CityMapUtil.scala) - Road network data
- [PERSON_CENTRIC_MODEL.md](./PERSON_CENTRIC_MODEL.md) - Person-centric architecture
