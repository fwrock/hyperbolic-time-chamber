package org.interscity.htc
package model.hybrid.entity.event.data.person

import core.entity.event.data.BaseEventData

/** Message from Person to owned Vehicle when the person's daily schedule is complete.
  *
  * Signals the vehicle to self-destruct and free memory:
  *   - If vehicle is Parked → selfDestruct() immediately.
  *   - If vehicle is active (mid-trip) → flag to destruct on next deactivation.
  *
  * @param personId
  *   ID of the person whose schedule is complete
  */
case class PersonScheduleCompleteData(personId: String) extends BaseEventData
