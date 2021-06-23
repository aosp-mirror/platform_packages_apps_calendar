/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.calendar

import android.graphics.Rect

class EventGeometry {
    // This is the space from the grid line to the event rectangle.
    private var mCellMargin = 0
    private var mMinuteHeight = 0f
    private var mHourGap = 0f
    private var mMinEventHeight = 0f
    fun setCellMargin(cellMargin: Int) {
        mCellMargin = cellMargin
    }

    fun setHourGap(gap: Float) {
        mHourGap = gap
    }

    fun setMinEventHeight(height: Float) {
        mMinEventHeight = height
    }

    fun setHourHeight(height: Float) {
        mMinuteHeight = height / 60.0f
    }

    // Computes the rectangle coordinates of the given event on the screen.
    // Returns true if the rectangle is visible on the screen.
    fun computeEventRect(date: Int, left: Int, top: Int, cellWidth: Int, event: Event): Boolean {
        if (event.drawAsAllday()) {
            return false
        }
        val cellMinuteHeight = mMinuteHeight
        val startDay: Int = event.startDay
        val endDay: Int = event.endDay
        if (startDay > date || endDay < date) {
            return false
        }
        var startTime: Int = event.startTime
        var endTime: Int = event.endTime

        // If the event started on a previous day, then show it starting
        // at the beginning of this day.
        if (startDay < date) {
            startTime = 0
        }

        // If the event ends on a future day, then show it extending to
        // the end of this day.
        if (endDay > date) {
            endTime = DayView.MINUTES_PER_DAY
        }
        val col: Int = event.column
        val maxCols: Int = event.maxColumns
        val startHour = startTime / 60
        var endHour = endTime / 60

        // If the end point aligns on a cell boundary then count it as
        // ending in the previous cell so that we don't cross the border
        // between hours.
        if (endHour * 60 == endTime) endHour -= 1
        event.top = top as Float
        event.top += (startTime * cellMinuteHeight).toInt()
        event.top += startHour * mHourGap
        event.bottom = top as Float
        event.bottom += (endTime * cellMinuteHeight).toInt()
        event.bottom += endHour * mHourGap - 1

        // Make the rectangle be at least mMinEventHeight pixels high
        if (event.bottom < event.top + mMinEventHeight) {
            event.bottom = event.top + mMinEventHeight
        }
        val colWidth = (cellWidth - (maxCols + 1) * mCellMargin).toFloat() / maxCols.toFloat()
        event.left = left + col * (colWidth + mCellMargin)
        event.right = event.left + colWidth
        return true
    }

    /**
     * Returns true if this event intersects the selection region.
     */
    fun eventIntersectsSelection(event: Event, selection: Rect): Boolean {
        return if (event.left < selection.right && event.right >= selection.left &&
            event.top < selection.bottom && event.bottom >= selection.top) {
            true
        } else false
    }

    /**
     * Computes the distance from the given point to the given event.
     */
    fun pointToEvent(x: Float, y: Float, event: Event): Float {
        val left: Float = event.left
        val right: Float = event.right
        val top: Float = event.top
        val bottom: Float = event.bottom
        if (x >= left) {
            if (x <= right) {
                return if (y >= top) {
                    if (y <= bottom) {
                        // x,y is inside the event rectangle
                        0f
                    } else y - bottom
                    // x,y is below the event rectangle
                } else top - y
                // x,y is above the event rectangle
            }

            // x > right
            val dx = x - right
            if (y < top) {
                // the upper right corner
                val dy = top - y
                return (Math.sqrt(dx as Double * dx + dy as Double * dy)) as Float
            }
            if (y > bottom) {
                // the lower right corner
                val dy = y - bottom
                return (Math.sqrt(dx as Double * dx + dy as Double * dy)) as Float
            }
            // x,y is to the right of the event rectangle
            return dx
        }
        // x < left
        val dx = left - x
        if (y < top) {
            // the upper left corner
            val dy = top - y
            return (Math.sqrt(dx as Double * dx + dy as Double * dy)) as Float
        }
        if (y > bottom) {
            // the lower left corner
            val dy = y - bottom
            return (Math.sqrt(dx as Double * dx + dy as Double * dy)) as Float
        }
        // x,y is to the left of the event rectangle
        return dx
    }
}