package org.interscity.htc
package core.entity.control

import core.types.Tick

case class LocalTimeManagerTickInfo (
                                      tick: Tick,
                                      hasSchedule: Boolean = false,
                                      isProcessed: Boolean = false,
                                    )

