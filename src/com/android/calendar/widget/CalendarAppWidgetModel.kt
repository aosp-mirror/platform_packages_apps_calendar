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
package com.android.calendar.widget

import com.android.calendar.R
import com.android.calendar.Utils
import android.content.Context
import android.database.Cursor
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Time
import android.util.Log
import android.view.View
import java.util.ArrayList
import java.util.LinkedList
import java.util.TimeZone

internal class CalendarAppWidgetModel(context: Context, timeZone: String?) {
    private var mHomeTZName: String? = null
    private var mShowTZ = false

    /**
     * [RowInfo] is a class that represents a single row in the widget. It
     * is actually only a pointer to either a [DayInfo] or an
     * [EventInfo] instance, since a row in the widget might be either a
     * day header or an event.
     */
    internal class RowInfo(
        /**
         * mType is either a day header (TYPE_DAY) or an event (TYPE_MEETING)
         */
        @JvmField val mType: Int,
        /**
         * If mType is TYPE_DAY, then mData is the index into day infos.
         * Otherwise mType is TYPE_MEETING and mData is the index into event
         * infos.
         */
        @JvmField val mIndex: Int
    ) {
        companion object {
            const val TYPE_DAY = 0
            const val TYPE_MEETING = 1
        }
    }

    /**
     * [EventInfo] is a class that represents an event in the widget. It
     * contains all of the data necessary to display that event, including the
     * properly localized strings and visibility settings.
     */
    internal class EventInfo {
        // Visibility value for When textview (View.GONE or View.VISIBLE)
        @JvmField var visibWhen: Int
        @JvmField var `when`: String? = null
        // Visibility value for Where textview (View.GONE or View.VISIBLE)
        @JvmField var visibWhere: Int
        @JvmField var where: String? = null
        // Visibility value for Title textview (View.GONE or View.VISIBLE)
        @JvmField var visibTitle: Int
        @JvmField var title: String? = null
        @JvmField var selfAttendeeStatus = 0
        @JvmField var id: Long = 0
        @JvmField var start: Long = 0
        @JvmField var end: Long = 0
        @JvmField var allDay = false
        @JvmField var color = 0

        @Override
        override fun toString(): String {
            val builder = StringBuilder()
            builder.append("EventInfo [visibTitle=")
            builder.append(visibTitle)
            builder.append(", title=")
            builder.append(title)
            builder.append(", visibWhen=")
            builder.append(visibWhen)
            builder.append(", id=")
            builder.append(id)
            builder.append(", when=")
            builder.append(`when`)
            builder.append(", visibWhere=")
            builder.append(visibWhere)
            builder.append(", where=")
            builder.append(where)
            builder.append(", color=")
            builder.append(String.format("0x%x", color))
            builder.append(", selfAttendeeStatus=")
            builder.append(selfAttendeeStatus)
            builder.append("]")
            return builder.toString()
        }

        @Override
        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + if (allDay) 1231 else 1237
            result = prime * result + (id xor (id ushr 32)).toInt()
            result = prime * result + (end xor (end ushr 32)).toInt()
            result = prime * result + (start xor (start ushr 32)).toInt()
            result = prime * result + if (title == null) 0 else title!!.hashCode()
            result = prime * result + visibTitle
            result = prime * result + visibWhen
            result = prime * result + visibWhere
            result = prime * result + if (`when` == null) 0 else `when`!!.hashCode()
            result = prime * result + if (where == null) 0 else where!!.hashCode()
            result = prime * result + color
            result = prime * result + selfAttendeeStatus
            return result
        }

        @Override
        override fun equals(obj: Any?): Boolean {
            if (this == obj) return true
            if (obj == null) return false
            if (this::class != obj::class) return false
            val other = obj as EventInfo
            if (id != other.id) return false
            if (allDay != other.allDay) return false
            if (end != other.end) return false
            if (start != other.start) return false
            if (title == null) {
                if (other.title != null) return false
            } else if (!title!!.equals(other.title)) return false
            if (visibTitle != other.visibTitle) return false
            if (visibWhen != other.visibWhen) return false
            if (visibWhere != other.visibWhere) return false
            if (`when` == null) {
                if (other.`when` != null) return false
            } else if (!`when`!!.equals(other.`when`)) {
                return false
            }
            if (where == null) {
                if (other.where != null) return false
            } else if (!where!!.equals(other.where)) {
                return false
            }
            if (color != other.color) {
                return false
            }
            return if (selfAttendeeStatus != other.selfAttendeeStatus) {
                false
            } else true
        }

        init {
            visibWhen = View.GONE
            visibWhere = View.GONE
            visibTitle = View.GONE
        }
    }

    /**
     * [DayInfo] is a class that represents a day header in the widget. It
     * contains all of the data necessary to display that day header, including
     * the properly localized string.
     */
    internal class DayInfo(
        /** The Julian day  */
        @JvmField var mJulianDay: Int,
        /** The string representation of this day header, to be displayed  */
        @JvmField var mDayLabel: String? = null
    ) {
        @Override
        override fun toString(): String {
            return mDayLabel as String
        }

        @Override
        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + (mDayLabel?.hashCode() ?: 0)
            result = prime * result + mJulianDay
            return result
        }

        @Override
        override fun equals(obj: Any?): Boolean {
            if (this == obj) return true
            if (obj == null) return false
            if (this::class !== obj::class) return false
            val other = obj as DayInfo
            if (mDayLabel == null) {
                if (other.mDayLabel != null) return false
            } else if (!mDayLabel.equals(other.mDayLabel)) return false
            return if (mJulianDay != other.mJulianDay) false else true
        }
    }

    @JvmField val mRowInfos: ArrayList<RowInfo>
    @JvmField val mEventInfos: ArrayList<EventInfo>
    @JvmField val mDayInfos: ArrayList<DayInfo>
    @JvmField val mContext: Context?
    @JvmField val mNow: Long
    @JvmField val mTodayJulianDay: Int
    @JvmField val mMaxJulianDay: Int
    fun buildFromCursor(cursor: Cursor, timeZone: String?) {
        val recycle = Time(timeZone)
        val mBuckets: ArrayList<LinkedList<RowInfo>> =
            ArrayList<LinkedList<RowInfo>>(CalendarAppWidgetService.MAX_DAYS)
        for (i in 0 until CalendarAppWidgetService.MAX_DAYS) {
            mBuckets.add(LinkedList<RowInfo>())
        }
        recycle.setToNow()
        mShowTZ = !TextUtils.equals(timeZone, Time.getCurrentTimezone())
        if (mShowTZ) {
            mHomeTZName = TimeZone.getTimeZone(timeZone).getDisplayName(
                recycle.isDst !== 0,
                TimeZone.SHORT
            )
        }
        cursor.moveToPosition(-1)
        val tz = Utils.getTimeZone(mContext, null)
        while (cursor.moveToNext()) {
            val rowId: Int = cursor.getPosition()
            val eventId: Long = cursor.getLong(CalendarAppWidgetService.INDEX_EVENT_ID)
            val allDay = cursor.getInt(CalendarAppWidgetService.INDEX_ALL_DAY) !== 0
            var start: Long = cursor.getLong(CalendarAppWidgetService.INDEX_BEGIN)
            var end: Long = cursor.getLong(CalendarAppWidgetService.INDEX_END)
            val title: String = cursor.getString(CalendarAppWidgetService.INDEX_TITLE)
            val location: String = cursor.getString(CalendarAppWidgetService.INDEX_EVENT_LOCATION)
            // we don't compute these ourselves because it seems to produce the
            // wrong endDay for all day events
            val startDay: Int = cursor.getInt(CalendarAppWidgetService.INDEX_START_DAY)
            val endDay: Int = cursor.getInt(CalendarAppWidgetService.INDEX_END_DAY)
            val color: Int = cursor.getInt(CalendarAppWidgetService.INDEX_COLOR)
            val selfStatus: Int = cursor
                .getInt(CalendarAppWidgetService.INDEX_SELF_ATTENDEE_STATUS)

            // Adjust all-day times into local timezone
            if (allDay) {
                start = Utils.convertAlldayUtcToLocal(recycle, start, tz as String)
                end = Utils.convertAlldayUtcToLocal(recycle, end, tz as String)
            }
            if (LOGD) {
                Log.d(
                    TAG, "Row #" + rowId + " allDay:" + allDay + " start:" + start +
                        " end:" + end + " eventId:" + eventId
                )
            }

            // we might get some extra events when querying, in order to
            // deal with all-day events
            if (end < mNow) {
                continue
            }
            val i: Int = mEventInfos.size
            mEventInfos.add(
                populateEventInfo(
                    eventId, allDay, start, end, startDay, endDay, title,
                    location, color, selfStatus
                )
            )
            // populate the day buckets that this event falls into
            val from: Int = Math.max(startDay, mTodayJulianDay)
            val to: Int = Math.min(endDay, mMaxJulianDay)
            for (day in from..to) {
                val bucket: LinkedList<RowInfo> = mBuckets.get(day - mTodayJulianDay)
                val rowInfo = RowInfo(RowInfo.TYPE_MEETING, i)
                if (allDay) {
                    bucket.addFirst(rowInfo)
                } else {
                    bucket.add(rowInfo)
                }
            }
        }
        var day = mTodayJulianDay
        var count = 0
        for (bucket in mBuckets) {
            if (!bucket.isEmpty()) {
                // We don't show day header in today
                if (day != mTodayJulianDay) {
                    val dayInfo = populateDayInfo(day, recycle)
                    // Add the day header
                    val dayIndex: Int = mDayInfos.size
                    mDayInfos.add(dayInfo as CalendarAppWidgetModel.DayInfo)
                    mRowInfos.add(RowInfo(RowInfo.TYPE_DAY, dayIndex))
                }

                // Add the event row infos
                mRowInfos.addAll(bucket)
                count += bucket.size
            }
            day++
            if (count >= CalendarAppWidgetService.EVENT_MIN_COUNT) {
                break
            }
        }
    }

    private fun populateEventInfo(
        eventId: Long,
        allDay: Boolean,
        start: Long,
        end: Long,
        startDay: Int,
        endDay: Int,
        title: String,
        location: String,
        color: Int,
        selfStatus: Int
    ): EventInfo {
        val eventInfo = EventInfo()

        // Compute a human-readable string for the start time of the event
        val whenString = StringBuilder()
        val visibWhen: Int
        var flags: Int = DateUtils.FORMAT_ABBREV_ALL
        visibWhen = View.VISIBLE
        if (allDay) {
            flags = flags or DateUtils.FORMAT_SHOW_DATE
            whenString.append(Utils.formatDateRange(mContext, start, end, flags))
        } else {
            flags = flags or DateUtils.FORMAT_SHOW_TIME
            if (DateFormat.is24HourFormat(mContext)) {
                flags = flags or DateUtils.FORMAT_24HOUR
            }
            if (endDay > startDay) {
                flags = flags or DateUtils.FORMAT_SHOW_DATE
            }
            whenString.append(Utils.formatDateRange(mContext, start, end, flags))
            if (mShowTZ) {
                whenString.append(" ").append(mHomeTZName)
            }
        }
        eventInfo.id = eventId
        eventInfo.start = start
        eventInfo.end = end
        eventInfo.allDay = allDay
        eventInfo.`when` = whenString.toString()
        eventInfo.visibWhen = visibWhen
        eventInfo.color = color
        eventInfo.selfAttendeeStatus = selfStatus

        // What
        if (TextUtils.isEmpty(title)) {
            eventInfo.title = mContext?.getString(R.string.no_title_label)
        } else {
            eventInfo.title = title
        }
        eventInfo.visibTitle = View.VISIBLE

        // Where
        if (!TextUtils.isEmpty(location)) {
            eventInfo.visibWhere = View.VISIBLE
            eventInfo.where = location
        } else {
            eventInfo.visibWhere = View.GONE
        }
        return eventInfo
    }

    private fun populateDayInfo(julianDay: Int, recycle: Time?): DayInfo? {
        val millis: Long = recycle?.setJulianDay(julianDay) as Long
        var flags: Int = DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_DATE
        val label: String?
        if (julianDay == mTodayJulianDay + 1) {
            label = mContext?.getString(
                R.string.agenda_tomorrow,
                Utils.formatDateRange(mContext, millis, millis, flags).toString()
            )
        } else {
            flags = flags or DateUtils.FORMAT_SHOW_WEEKDAY
            label = Utils.formatDateRange(mContext, millis, millis, flags)
        }
        return DayInfo(julianDay, label as String)
    }

    @Override
    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("\nCalendarAppWidgetModel [eventInfos=")
        builder.append(mEventInfos)
        builder.append("]")
        return builder.toString()
    }

    companion object {
        private val TAG: String = CalendarAppWidgetModel::class.java.getSimpleName()
        private const val LOGD = false
    }

    init {
        mNow = System.currentTimeMillis()
        val time = Time(timeZone)
        time.setToNow() // This is needed for gmtoff to be set
        mTodayJulianDay = Time.getJulianDay(mNow, time.gmtoff)
        mMaxJulianDay = mTodayJulianDay + CalendarAppWidgetService.MAX_DAYS - 1
        mEventInfos = ArrayList<EventInfo>(50)
        mRowInfos = ArrayList<RowInfo>(50)
        mDayInfos = ArrayList<DayInfo>(8)
        mContext = context
    }
}