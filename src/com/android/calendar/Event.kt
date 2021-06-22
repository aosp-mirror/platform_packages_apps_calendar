/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.os.Debug
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Log

import java.util.ArrayList
import java.util.Arrays
import java.util.Iterator
import java.util.concurrent.atomic.AtomicInteger

// TODO: should Event be Parcelable so it can be passed via Intents?
class Event : Cloneable {
    companion object {
        private const val TAG = "CalEvent"
        private const val PROFILE = false

        /**
         * The sort order is:
         * 1) events with an earlier start (begin for normal events, startday for allday)
         * 2) events with a later end (end for normal events, endday for allday)
         * 3) the title (unnecessary, but nice)
         *
         * The start and end day is sorted first so that all day events are
         * sorted correctly with respect to events that are >24 hours (and
         * therefore show up in the allday area).
         */
        private const val SORT_EVENTS_BY = "begin ASC, end DESC, title ASC"
        private const val SORT_ALLDAY_BY = "startDay ASC, endDay DESC, title ASC"
        private const val DISPLAY_AS_ALLDAY = "dispAllday"
        private const val EVENTS_WHERE = DISPLAY_AS_ALLDAY + "=0"
        private const val ALLDAY_WHERE = DISPLAY_AS_ALLDAY + "=1"

        // The projection to use when querying instances to build a list of events
        @JvmField
        val EVENT_PROJECTION = arrayOf<String>(
            Instances.TITLE, // 0
            Instances.EVENT_LOCATION, // 1
            Instances.ALL_DAY, // 2
            Instances.DISPLAY_COLOR, // 3 If SDK < 16, set to Instances.CALENDAR_COLOR.
            Instances.EVENT_TIMEZONE, // 4
            Instances.EVENT_ID, // 5
            Instances.BEGIN, // 6
            Instances.END,  // 7
            Instances._ID,  // 8
            Instances.START_DAY, // 9
            Instances.END_DAY, // 10
            Instances.START_MINUTE, // 11
            Instances.END_MINUTE, // 12
            Instances.HAS_ALARM, // 13
            Instances.RRULE,  // 14
            Instances.RDATE,  // 15
            Instances.SELF_ATTENDEE_STATUS, // 16
            Events.ORGANIZER, // 17
            Events.GUESTS_CAN_MODIFY, // 18
            Instances.ALL_DAY.toString() + "=1 OR (" + Instances.END + "-" +
                Instances.BEGIN + ")>=" +
                DateUtils.DAY_IN_MILLIS + " AS " + DISPLAY_AS_ALLDAY
        )

        // The indices for the projection array above.
        private const val PROJECTION_TITLE_INDEX = 0
        private const val PROJECTION_LOCATION_INDEX = 1
        private const val PROJECTION_ALL_DAY_INDEX = 2
        private const val PROJECTION_COLOR_INDEX = 3
        private const val PROJECTION_TIMEZONE_INDEX = 4
        private const val PROJECTION_EVENT_ID_INDEX = 5
        private const val PROJECTION_BEGIN_INDEX = 6
        private const val PROJECTION_END_INDEX = 7
        private const val PROJECTION_START_DAY_INDEX = 9
        private const val PROJECTION_END_DAY_INDEX = 10
        private const val PROJECTION_START_MINUTE_INDEX = 11
        private const val PROJECTION_END_MINUTE_INDEX = 12
        private const val PROJECTION_HAS_ALARM_INDEX = 13
        private const val PROJECTION_RRULE_INDEX = 14
        private const val PROJECTION_RDATE_INDEX = 15
        private const val PROJECTION_SELF_ATTENDEE_STATUS_INDEX = 16
        private const val PROJECTION_ORGANIZER_INDEX = 17
        private const val PROJECTION_GUESTS_CAN_INVITE_OTHERS_INDEX = 18
        private const val PROJECTION_DISPLAY_AS_ALLDAY = 19
        private var mNoTitleString: String? = null
        private var mNoColorColor = 0
        @JvmStatic fun newInstance(): Event {
            val e = Event()
            e.id = 0
            e.title = null
            e.color = 0
            e.location = null
            e.allDay = false
            e.startDay = 0
            e.endDay = 0
            e.startTime = 0
            e.endTime = 0
            e.startMillis = 0
            e.endMillis = 0
            e.hasAlarm = false
            e.isRepeating = false
            e.selfAttendeeStatus = Attendees.ATTENDEE_STATUS_NONE
            return e
        }

        /**
         * Loads *days* days worth of instances starting at *startDay*.
         */
        @JvmStatic fun loadEvents(
            context: Context?,
            events: ArrayList<Event?>,
            startDay: Int,
            days: Int,
            requestId: Int,
            sequenceNumber: AtomicInteger?
        ) {
            if (PROFILE) {
                Debug.startMethodTracing("loadEvents")
            }
            var cEvents: Cursor? = null
            var cAllday: Cursor? = null
            events.clear()
            try {
                val endDay = startDay + days - 1

                // We use the byDay instances query to get a list of all events for
                // the days we're interested in.
                // The sort order is: events with an earlier start time occur
                // first and if the start times are the same, then events with
                // a later end time occur first. The later end time is ordered
                // first so that long rectangles in the calendar views appear on
                // the left side.  If the start and end times of two events are
                // the same then we sort alphabetically on the title.  This isn't
                // required for correctness, it just adds a nice touch.

                // Respect the preference to show/hide declined events
                val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
                val hideDeclined: Boolean = prefs?.getBoolean(
                    GeneralPreferences.KEY_HIDE_DECLINED,
                    false
                ) as Boolean
                var where = EVENTS_WHERE
                var whereAllday = ALLDAY_WHERE
                if (hideDeclined) {
                    val hideString = (" AND " + Instances.SELF_ATTENDEE_STATUS.toString() + "!=" +
                        Attendees.ATTENDEE_STATUS_DECLINED)
                    where += hideString
                    whereAllday += hideString
                }
                cEvents = instancesQuery(
                    context?.getContentResolver(), EVENT_PROJECTION, startDay,
                    endDay, where, null, SORT_EVENTS_BY
                )
                cAllday = instancesQuery(
                    context?.getContentResolver(), EVENT_PROJECTION, startDay,
                    endDay, whereAllday, null, SORT_ALLDAY_BY
                )

                // Check if we should return early because there are more recent
                // load requests waiting.
                if (requestId != sequenceNumber?.get()) {
                    return
                }
                buildEventsFromCursor(events, cEvents, context, startDay, endDay)
                buildEventsFromCursor(events, cAllday, context, startDay, endDay)
            } finally {
                if (cEvents != null) {
                    cEvents.close()
                }
                if (cAllday != null) {
                    cAllday.close()
                }
                if (PROFILE) {
                    Debug.stopMethodTracing()
                }
            }
        }

        /**
         * Performs a query to return all visible instances in the given range
         * that match the given selection. This is a blocking function and
         * should not be done on the UI thread. This will cause an expansion of
         * recurring events to fill this time range if they are not already
         * expanded and will slow down for larger time ranges with many
         * recurring events.
         *
         * @param cr The ContentResolver to use for the query
         * @param projection The columns to return
         * @param begin The start of the time range to query in UTC millis since
         * epoch
         * @param end The end of the time range to query in UTC millis since
         * epoch
         * @param selection Filter on the query as an SQL WHERE statement
         * @param selectionArgs Args to replace any '?'s in the selection
         * @param orderBy How to order the rows as an SQL ORDER BY statement
         * @return A Cursor of instances matching the selection
         */
        @JvmStatic private fun instancesQuery(
            cr: ContentResolver?,
            projection: Array<String>,
            startDay: Int,
            endDay: Int,
            selection: String,
            selectionArgs: Array<String?>?,
            orderBy: String?
        ): Cursor? {
            var selection = selection
            var selectionArgs = selectionArgs
            val WHERE_CALENDARS_SELECTED: String = Calendars.VISIBLE.toString() + "=?"
            val WHERE_CALENDARS_ARGS = arrayOf<String?>("1")
            val DEFAULT_SORT_ORDER = "begin ASC"
            val builder: Uri.Builder = Instances.CONTENT_BY_DAY_URI.buildUpon()
            ContentUris.appendId(builder, startDay.toLong())
            ContentUris.appendId(builder, endDay.toLong())
            if (TextUtils.isEmpty(selection)) {
                selection = WHERE_CALENDARS_SELECTED
                selectionArgs = WHERE_CALENDARS_ARGS
            } else {
                selection = "($selection) AND $WHERE_CALENDARS_SELECTED"
                if (selectionArgs != null && selectionArgs.size > 0) {
                    selectionArgs = Arrays.copyOf(selectionArgs, selectionArgs.size + 1)
                    selectionArgs[selectionArgs.size - 1] = WHERE_CALENDARS_ARGS[0]
                } else {
                    selectionArgs = WHERE_CALENDARS_ARGS
                }
            }
            return cr?.query(
                builder.build(), projection, selection, selectionArgs,
                orderBy ?: DEFAULT_SORT_ORDER
            )
        }

        /**
         * Adds all the events from the cursors to the events list.
         *
         * @param events The list of events
         * @param cEvents Events to add to the list
         * @param context
         * @param startDay
         * @param endDay
         */
        @JvmStatic fun buildEventsFromCursor(
            events: ArrayList<Event?>?,
            cEvents: Cursor?,
            context: Context?,
            startDay: Int,
            endDay: Int
        ) {
            if (cEvents == null || events == null) {
                Log.e(TAG, "buildEventsFromCursor: null cursor or null events list!")
                return
            }
            val count: Int = cEvents.getCount()
            if (count == 0) {
                return
            }
            val res: Resources? = context?.getResources()
            mNoTitleString = res?.getString(R.string.no_title_label)
            mNoColorColor = res?.getColor(R.color.event_center) as Int
            // Sort events in two passes so we ensure the allday and standard events
            // get sorted in the correct order
            cEvents.moveToPosition(-1)
            while (cEvents.moveToNext()) {
                val e = generateEventFromCursor(cEvents)
                if (e.startDay > endDay || e.endDay < startDay) {
                    continue
                }
                events.add(e)
            }
        }

        /**
         * @param cEvents Cursor pointing at event
         * @return An event created from the cursor
         */
        @JvmStatic private fun generateEventFromCursor(cEvents: Cursor): Event {
            val e = Event()
            e.id = cEvents.getLong(PROJECTION_EVENT_ID_INDEX)
            e.title = cEvents.getString(PROJECTION_TITLE_INDEX)
            e.location = cEvents.getString(PROJECTION_LOCATION_INDEX)
            e.allDay = cEvents.getInt(PROJECTION_ALL_DAY_INDEX) !== 0
            e.organizer = cEvents.getString(PROJECTION_ORGANIZER_INDEX)
            e.guestsCanModify = cEvents.getInt(PROJECTION_GUESTS_CAN_INVITE_OTHERS_INDEX) !== 0
            if (e.title == null || e.title!!.length == 0) {
                e.title = mNoTitleString
            }
            if (!cEvents.isNull(PROJECTION_COLOR_INDEX)) {
                // Read the color from the database
                e.color = Utils.getDisplayColorFromColor(cEvents.getInt(PROJECTION_COLOR_INDEX))
            } else {
                e.color = mNoColorColor
            }
            val eStart: Long = cEvents.getLong(PROJECTION_BEGIN_INDEX)
            val eEnd: Long = cEvents.getLong(PROJECTION_END_INDEX)
            e.startMillis = eStart
            e.startTime = cEvents.getInt(PROJECTION_START_MINUTE_INDEX)
            e.startDay = cEvents.getInt(PROJECTION_START_DAY_INDEX)
            e.endMillis = eEnd
            e.endTime = cEvents.getInt(PROJECTION_END_MINUTE_INDEX)
            e.endDay = cEvents.getInt(PROJECTION_END_DAY_INDEX)
            e.hasAlarm = cEvents.getInt(PROJECTION_HAS_ALARM_INDEX) !== 0

            // Check if this is a repeating event
            val rrule: String = cEvents.getString(PROJECTION_RRULE_INDEX)
            val rdate: String = cEvents.getString(PROJECTION_RDATE_INDEX)
            if (!TextUtils.isEmpty(rrule) || !TextUtils.isEmpty(rdate)) {
                e.isRepeating = true
            } else {
                e.isRepeating = false
            }
            e.selfAttendeeStatus = cEvents.getInt(PROJECTION_SELF_ATTENDEE_STATUS_INDEX)
            return e
        }

        /**
         * Computes a position for each event.  Each event is displayed
         * as a non-overlapping rectangle.  For normal events, these rectangles
         * are displayed in separate columns in the week view and day view.  For
         * all-day events, these rectangles are displayed in separate rows along
         * the top.  In both cases, each event is assigned two numbers: N, and
         * Max, that specify that this event is the Nth event of Max number of
         * events that are displayed in a group. The width and position of each
         * rectangle depend on the maximum number of rectangles that occur at
         * the same time.
         *
         * @param eventsList the list of events, sorted into increasing time order
         * @param minimumDurationMillis minimum duration acceptable as cell height of each event
         * rectangle in millisecond. Should be 0 when it is not determined.
         */
        /* package */
        @JvmStatic fun computePositions(
            eventsList: ArrayList<Event>?,
            minimumDurationMillis: Long
        ) {
            if (eventsList == null) {
                return
            }

            // Compute the column positions separately for the all-day events
            doComputePositions(eventsList, minimumDurationMillis, false)
            doComputePositions(eventsList, minimumDurationMillis, true)
        }

        @JvmStatic private fun doComputePositions(
            eventsList: ArrayList<Event>,
            minimumDurationMillis: Long,
            doAlldayEvents: Boolean
        ) {
            var minimumDurationMillis = minimumDurationMillis
            val activeList: ArrayList<Event> = ArrayList<Event>()
            val groupList: ArrayList<Event> = ArrayList<Event>()
            if (minimumDurationMillis < 0) {
                minimumDurationMillis = 0
            }
            var colMask: Long = 0
            var maxCols = 0
            for (event in eventsList) {
                // Process all-day events separately
                if (event.drawAsAllday() != doAlldayEvents) continue
                colMask = if (!doAlldayEvents) {
                    removeNonAlldayActiveEvents(
                        event, activeList.iterator() as Iterator<Event>,
                        minimumDurationMillis, colMask
                    )
                } else {
                    removeAlldayActiveEvents(event, activeList.iterator()
                        as Iterator<Event>, colMask)
                }

                // If the active list is empty, then reset the max columns, clear
                // the column bit mask, and empty the groupList.
                if (activeList.isEmpty()) {
                    for (ev in groupList) {
                        ev.maxColumns = maxCols
                    }
                    maxCols = 0
                    colMask = 0
                    groupList.clear()
                }

                // Find the first empty column.  Empty columns are represented by
                // zero bits in the column mask "colMask".
                var col = findFirstZeroBit(colMask)
                if (col == 64) col = 63
                colMask = colMask or (1L shl col)
                event.column = col
                activeList.add(event)
                groupList.add(event)
                val len: Int = activeList.size
                if (maxCols < len) maxCols = len
            }
            for (ev in groupList) {
                ev.maxColumns = maxCols
            }
        }

        @JvmStatic private fun removeAlldayActiveEvents(
            event: Event,
            iter: Iterator<Event>,
            colMask: Long
        ): Long {
            // Remove the inactive allday events. An event on the active list
            // becomes inactive when the end day is less than the current event's
            // start day.
            var colMask = colMask
            while (iter.hasNext()) {
                val active = iter.next()
                if (active.endDay < event.startDay) {
                    colMask = colMask and (1L shl active.column).inv()
                    iter.remove()
                }
            }
            return colMask
        }

        @JvmStatic private fun removeNonAlldayActiveEvents(
            event: Event,
            iter: Iterator<Event>,
            minDurationMillis: Long,
            colMask: Long
        ): Long {
            var colMask = colMask
            val start = event.getStartMillis()
            // Remove the inactive events. An event on the active list
            // becomes inactive when its end time is less than or equal to
            // the current event's start time.
            while (iter.hasNext()) {
                val active = iter.next()
                val duration: Long = Math.max(
                    active.getEndMillis() - active.getStartMillis(), minDurationMillis
                )
                if (active.getStartMillis() + duration <= start) {
                    colMask = colMask and (1L shl active.column).inv()
                    iter.remove()
                }
            }
            return colMask
        }

        @JvmStatic fun findFirstZeroBit(`val`: Long): Int {
            for (ii in 0..63) {
                if (`val` and (1L shl ii) == 0L) return ii
            }
            return 64
        }

        init {
            if (!Utils.isJellybeanOrLater()) {
                EVENT_PROJECTION[PROJECTION_COLOR_INDEX] = Instances.CALENDAR_COLOR
            }
        }
    }

    @JvmField var id: Long = 0
    @JvmField var color = 0
    @JvmField var title: CharSequence? = null
    @JvmField var location: CharSequence? = null
    @JvmField var allDay = false
    @JvmField var organizer: String? = null
    @JvmField var guestsCanModify = false
    @JvmField var startDay = 0 // start Julian day
    @JvmField var endDay = 0 // end Julian day
    @JvmField var startTime = 0 // Start and end time are in minutes since midnight
    @JvmField var endTime = 0
    @JvmField var startMillis = 0L // UTC milliseconds since the epoch
    @JvmField var endMillis = 0L // UTC milliseconds since the epoch
    @JvmField var column = 0
    @JvmField var maxColumns = 0
    @JvmField var hasAlarm = false
    @JvmField var isRepeating = false
    @JvmField var selfAttendeeStatus = 0

    // The coordinates of the event rectangle drawn on the screen.
    @JvmField var left = 0f
    @JvmField var right = 0f
    @JvmField var top = 0f
    @JvmField var bottom = 0f

    // These 4 fields are used for navigating among events within the selected
    // hour in the Day and Week view.
    @JvmField var nextRight: Event? = null
    @JvmField var nextLeft: Event? = null
    @JvmField var nextUp: Event? = null
    @JvmField var nextDown: Event? = null
    @Override
    @Throws(CloneNotSupportedException::class)
    override fun clone(): Object {
        super.clone()
        val e = Event()
        e.title = title
        e.color = color
        e.location = location
        e.allDay = allDay
        e.startDay = startDay
        e.endDay = endDay
        e.startTime = startTime
        e.endTime = endTime
        e.startMillis = startMillis
        e.endMillis = endMillis
        e.hasAlarm = hasAlarm
        e.isRepeating = isRepeating
        e.selfAttendeeStatus = selfAttendeeStatus
        e.organizer = organizer
        e.guestsCanModify = guestsCanModify
        return e as Object
    }

    fun copyTo(dest: Event) {
        dest.id = id
        dest.title = title
        dest.color = color
        dest.location = location
        dest.allDay = allDay
        dest.startDay = startDay
        dest.endDay = endDay
        dest.startTime = startTime
        dest.endTime = endTime
        dest.startMillis = startMillis
        dest.endMillis = endMillis
        dest.hasAlarm = hasAlarm
        dest.isRepeating = isRepeating
        dest.selfAttendeeStatus = selfAttendeeStatus
        dest.organizer = organizer
        dest.guestsCanModify = guestsCanModify
    }

    fun dump() {
        Log.e("Cal", "+-----------------------------------------+")
        Log.e("Cal", "+        id = $id")
        Log.e("Cal", "+     color = $color")
        Log.e("Cal", "+     title = $title")
        Log.e("Cal", "+  location = $location")
        Log.e("Cal", "+    allDay = $allDay")
        Log.e("Cal", "+  startDay = $startDay")
        Log.e("Cal", "+    endDay = $endDay")
        Log.e("Cal", "+ startTime = $startTime")
        Log.e("Cal", "+   endTime = $endTime")
        Log.e("Cal", "+ organizer = $organizer")
        Log.e("Cal", "+  guestwrt = $guestsCanModify")
    }

    fun intersects(
        julianDay: Int,
        startMinute: Int,
        endMinute: Int
    ): Boolean {
        if (endDay < julianDay) {
            return false
        }
        if (startDay > julianDay) {
            return false
        }
        if (endDay == julianDay) {
            if (endTime < startMinute) {
                return false
            }
            // An event that ends at the start minute should not be considered
            // as intersecting the given time span, but don't exclude
            // zero-length (or very short) events.
            if (endTime == startMinute &&
                (startTime != endTime || startDay != endDay)) {
                return false
            }
        }
        return !(startDay == julianDay && startTime > endMinute)
    }

    /**
     * Returns the event title and location separated by a comma.  If the
     * location is already part of the title (at the end of the title), then
     * just the title is returned.
     *
     * @return the event title and location as a String
     */
    val titleAndLocation: String
        get() {
            var text = title.toString()

            // Append the location to the title, unless the title ends with the
            // location (for example, "meeting in building 42" ends with the
            // location).
            if (location != null) {
                val locationString = location.toString()
                if (!text.endsWith(locationString)) {
                    text += ", $locationString"
                }
            }
            return text
        }

    // TODO(damianpatel): this getter will likely not be
    // needed once DayView.java is converted
    fun getColumn(): Int {
        return column
    }

    fun setStartMillis(startMillis: Long) {
        this.startMillis = startMillis
    }

    fun getStartMillis(): Long {
        return startMillis
    }

    fun setEndMillis(endMillis: Long) {
        this.endMillis = endMillis
    }

    fun getEndMillis(): Long {
        return endMillis
    }

    fun drawAsAllday(): Boolean {
        // Use >= so we'll pick up Exchange allday events
        return allDay || endMillis - startMillis >= DateUtils.DAY_IN_MILLIS
    }
}