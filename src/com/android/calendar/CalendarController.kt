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

import android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME
import android.provider.CalendarContract.EXTRA_EVENT_END_TIME
import android.provider.CalendarContract.Attendees.ATTENDEE_STATUS
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.text.format.Time
import android.util.Log
import android.util.Pair
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.LinkedList
import java.util.WeakHashMap

class CalendarController private constructor(context: Context?) {
    private var mContext: Context? = null

    // This uses a LinkedHashMap so that we can replace fragments based on the
    // view id they are being expanded into since we can't guarantee a reference
    // to the handler will be findable
    private val eventHandlers: LinkedHashMap<Int, EventHandler> =
        LinkedHashMap<Int, EventHandler>(5)
    private val mToBeRemovedEventHandlers: LinkedList<Int> = LinkedList<Int>()
    private val mToBeAddedEventHandlers: LinkedHashMap<Int, EventHandler> =
        LinkedHashMap<Int, EventHandler>()
    private var mFirstEventHandler: Pair<Int, EventHandler>? = null
    private var mToBeAddedFirstEventHandler: Pair<Int, EventHandler>? = null

    @Volatile
    private var mDispatchInProgressCounter = 0
    private val filters: WeakHashMap<Object, Long> = WeakHashMap<Object, Long>(1)

    // Forces the viewType. Should only be used for initialization.
    var viewType = -1
    private var mDetailViewType = -1
    var previousViewType = -1
        private set

    // The last event ID the edit view was launched with
    var eventId: Long = -1
    private val mTime: Time? = Time()

    // The last set of date flags sent with
    var dateFlags: Long = 0
        private set
    private val mUpdateTimezone: Runnable = object : Runnable {
        @Override
        override fun run() {
            mTime?.switchTimezone(Utils.getTimeZone(mContext, this))
        }
    }

    /**
     * One of the event types that are sent to or from the controller
     */
    interface EventType {
        companion object {
            // Simple view of an event
            const val VIEW_EVENT = 1L shl 1

            // Full detail view in read only mode
            const val VIEW_EVENT_DETAILS = 1L shl 2

            // full detail view in edit mode
            const val EDIT_EVENT = 1L shl 3
            const val GO_TO = 1L shl 5
            const val EVENTS_CHANGED = 1L shl 7
            const val USER_HOME = 1L shl 9

            // date range has changed, update the title
            const val UPDATE_TITLE = 1L shl 10
        }
    }

    /**
     * One of the Agenda/Day/Week/Month view types
     */
    interface ViewType {
        companion object {
            const val DETAIL = -1
            const val CURRENT = 0
            const val AGENDA = 1
            const val DAY = 2
            const val WEEK = 3
            const val MONTH = 4
            const val EDIT = 5
            const val MAX_VALUE = 5
        }
    }

    class EventInfo {
        @JvmField var eventType: Long = 0 // one of the EventType
        @JvmField var viewType = 0 // one of the ViewType
        @JvmField var id: Long = 0 // event id
        @JvmField var selectedTime: Time? = null // the selected time in focus

        // Event start and end times.  All-day events are represented in:
        // - local time for GO_TO commands
        // - UTC time for VIEW_EVENT and other event-related commands
        @JvmField var startTime: Time? = null
        @JvmField var endTime: Time? = null
        @JvmField var x = 0 // x coordinate in the activity space
        @JvmField var y = 0 // y coordinate in the activity space
        @JvmField var query: String? = null // query for a user search
        @JvmField var componentName: ComponentName? = null // used in combination with query
        @JvmField var eventTitle: String? = null
        @JvmField var calendarId: Long = 0

        /**
         * For EventType.VIEW_EVENT:
         * It is the default attendee response and an all day event indicator.
         * Set to Attendees.ATTENDEE_STATUS_NONE, Attendees.ATTENDEE_STATUS_ACCEPTED,
         * Attendees.ATTENDEE_STATUS_DECLINED, or Attendees.ATTENDEE_STATUS_TENTATIVE.
         * To signal the event is an all-day event, "or" ALL_DAY_MASK with the response.
         * Alternatively, use buildViewExtraLong(), getResponse(), and isAllDay().
         *
         *
         * For EventType.GO_TO:
         * Set to [.EXTRA_GOTO_TIME] to go to the specified date/time.
         * Set to [.EXTRA_GOTO_DATE] to consider the date but ignore the time.
         * Set to [.EXTRA_GOTO_BACK_TO_PREVIOUS] if back should bring back previous view.
         * Set to [.EXTRA_GOTO_TODAY] if this is a user request to go to the current time.
         *
         *
         * For EventType.UPDATE_TITLE:
         * Set formatting flags for Utils.formatDateRange
         */
        @JvmField var extraLong: Long = 0
        val isAllDay: Boolean
            get() {
                if (eventType != EventType.VIEW_EVENT) {
                    Log.wtf(TAG, "illegal call to isAllDay , wrong event type $eventType")
                    return false
                }
                return if (extraLong and ALL_DAY_MASK != 0L) true else false
            }
        val response: Int
            get() {
                if (eventType != EventType.VIEW_EVENT) {
                    Log.wtf(TAG, "illegal call to getResponse , wrong event type $eventType")
                    return Attendees.ATTENDEE_STATUS_NONE
                }
                val response = (extraLong and ATTENTEE_STATUS_MASK).toInt()
                when (response) {
                    ATTENDEE_STATUS_NONE_MASK -> return Attendees.ATTENDEE_STATUS_NONE
                    ATTENDEE_STATUS_ACCEPTED_MASK -> return Attendees.ATTENDEE_STATUS_ACCEPTED
                    ATTENDEE_STATUS_DECLINED_MASK -> return Attendees.ATTENDEE_STATUS_DECLINED
                    ATTENDEE_STATUS_TENTATIVE_MASK -> return Attendees.ATTENDEE_STATUS_TENTATIVE
                    else -> Log.wtf(TAG, "Unknown attendee response $response")
                }
                return ATTENDEE_STATUS_NONE_MASK
            }

        companion object {
            private const val ATTENTEE_STATUS_MASK: Long = 0xFF
            private const val ALL_DAY_MASK: Long = 0x100
            private const val ATTENDEE_STATUS_NONE_MASK = 0x01
            private const val ATTENDEE_STATUS_ACCEPTED_MASK = 0x02
            private const val ATTENDEE_STATUS_DECLINED_MASK = 0x04
            private const val ATTENDEE_STATUS_TENTATIVE_MASK = 0x08

            // Used to build the extra long for a VIEW event.
            @JvmStatic fun buildViewExtraLong(response: Int, allDay: Boolean): Long {
                var extra = if (allDay) ALL_DAY_MASK else 0
                extra = when (response) {
                    Attendees.ATTENDEE_STATUS_NONE -> extra or
                            ATTENDEE_STATUS_NONE_MASK.toLong()
                    Attendees.ATTENDEE_STATUS_ACCEPTED -> extra or
                            ATTENDEE_STATUS_ACCEPTED_MASK.toLong()
                    Attendees.ATTENDEE_STATUS_DECLINED -> extra or
                            ATTENDEE_STATUS_DECLINED_MASK.toLong()
                    Attendees.ATTENDEE_STATUS_TENTATIVE -> extra or
                            ATTENDEE_STATUS_TENTATIVE_MASK.toLong()
                    else -> {
                        Log.wtf(
                                TAG,
                                "Unknown attendee response $response"
                        )
                        extra or ATTENDEE_STATUS_NONE_MASK.toLong()
                    }
                }
                return extra
            }
        }
    }

    interface EventHandler {
        val supportedEventTypes: Long
        fun handleEvent(event: EventInfo?)

        /**
         * This notifies the handler that the database has changed and it should
         * update its view.
         */
        fun eventsChanged()
    }

    fun sendEventRelatedEvent(
        sender: Object?,
        eventType: Long,
        eventId: Long,
        startMillis: Long,
        endMillis: Long,
        x: Int,
        y: Int,
        selectedMillis: Long
    ) {
        // TODO: pass the real allDay status or at least a status that says we don't know the
        // status and have the receiver query the data.
        // The current use of this method for VIEW_EVENT is by the day view to show an EventInfo
        // so currently the missing allDay status has no effect.
        sendEventRelatedEventWithExtra(
                sender, eventType, eventId, startMillis, endMillis, x, y,
                EventInfo.buildViewExtraLong(Attendees.ATTENDEE_STATUS_NONE, false),
                selectedMillis
        )
    }

    /**
     * Helper for sending New/View/Edit/Delete events
     *
     * @param sender object of the caller
     * @param eventType one of [EventType]
     * @param eventId event id
     * @param startMillis start time
     * @param endMillis end time
     * @param x x coordinate in the activity space
     * @param y y coordinate in the activity space
     * @param extraLong default response value for the "simple event view" and all day indication.
     * Use Attendees.ATTENDEE_STATUS_NONE for no response.
     * @param selectedMillis The time to specify as selected
     */
    fun sendEventRelatedEventWithExtra(
        sender: Object?,
        eventType: Long,
        eventId: Long,
        startMillis: Long,
        endMillis: Long,
        x: Int,
        y: Int,
        extraLong: Long,
        selectedMillis: Long
    ) {
        sendEventRelatedEventWithExtraWithTitleWithCalendarId(
                sender, eventType, eventId,
                startMillis, endMillis, x, y, extraLong, selectedMillis, null, -1
        )
    }

    /**
     * Helper for sending New/View/Edit/Delete events
     *
     * @param sender object of the caller
     * @param eventType one of [EventType]
     * @param eventId event id
     * @param startMillis start time
     * @param endMillis end time
     * @param x x coordinate in the activity space
     * @param y y coordinate in the activity space
     * @param extraLong default response value for the "simple event view" and all day indication.
     * Use Attendees.ATTENDEE_STATUS_NONE for no response.
     * @param selectedMillis The time to specify as selected
     * @param title The title of the event
     * @param calendarId The id of the calendar which the event belongs to
     */
    fun sendEventRelatedEventWithExtraWithTitleWithCalendarId(
        sender: Object?,
        eventType: Long,
        eventId: Long,
        startMillis: Long,
        endMillis: Long,
        x: Int,
        y: Int,
        extraLong: Long,
        selectedMillis: Long,
        title: String?,
        calendarId: Long
    ) {
        val info = EventInfo()
        info.eventType = eventType
        if (eventType == EventType.VIEW_EVENT_DETAILS) {
            info.viewType = ViewType.CURRENT
        }
        info.id = eventId
        info.startTime = Time(Utils.getTimeZone(mContext, mUpdateTimezone))
        (info.startTime as Time).set(startMillis)
        if (selectedMillis != -1L) {
            info.selectedTime = Time(Utils.getTimeZone(mContext, mUpdateTimezone))
            (info.selectedTime as Time).set(selectedMillis)
        } else {
            info.selectedTime = info.startTime
        }
        info.endTime = Time(Utils.getTimeZone(mContext, mUpdateTimezone))
        (info.endTime as Time).set(endMillis)
        info.x = x
        info.y = y
        info.extraLong = extraLong
        info.eventTitle = title
        info.calendarId = calendarId
        this.sendEvent(sender, info)
    }

    /**
     * Helper for sending non-calendar-event events
     *
     * @param sender object of the caller
     * @param eventType one of [EventType]
     * @param start start time
     * @param end end time
     * @param eventId event id
     * @param viewType [ViewType]
     */
    fun sendEvent(
        sender: Object?,
        eventType: Long,
        start: Time?,
        end: Time?,
        eventId: Long,
        viewType: Int
    ) {
        sendEvent(
                sender, eventType, start, end, start, eventId, viewType, EXTRA_GOTO_TIME, null,
                null
        )
    }

    /**
     * sendEvent() variant with extraLong, search query, and search component name.
     */
    fun sendEvent(
        sender: Object?,
        eventType: Long,
        start: Time?,
        end: Time?,
        eventId: Long,
        viewType: Int,
        extraLong: Long,
        query: String?,
        componentName: ComponentName?
    ) {
        sendEvent(
                sender, eventType, start, end, start, eventId, viewType, extraLong, query,
                componentName
        )
    }

    fun sendEvent(
        sender: Object?,
        eventType: Long,
        start: Time?,
        end: Time?,
        selected: Time?,
        eventId: Long,
        viewType: Int,
        extraLong: Long,
        query: String?,
        componentName: ComponentName?
    ) {
        val info = EventInfo()
        info.eventType = eventType
        info.startTime = start
        info.selectedTime = selected
        info.endTime = end
        info.id = eventId
        info.viewType = viewType
        info.query = query
        info.componentName = componentName
        info.extraLong = extraLong
        this.sendEvent(sender, info)
    }

    fun sendEvent(sender: Object?, event: EventInfo) {
        // TODO Throw exception on invalid events
        if (DEBUG) {
            Log.d(TAG, eventInfoToString(event))
        }
        val filteredTypes: Long? = filters.get(sender)
        if (filteredTypes != null && filteredTypes.toLong() and event.eventType != 0L) {
            // Suppress event per filter
            if (DEBUG) {
                Log.d(TAG, "Event suppressed")
            }
            return
        }
        previousViewType = viewType

        // Fix up view if not specified
        if (event.viewType == ViewType.DETAIL) {
            event.viewType = mDetailViewType
            viewType = mDetailViewType
        } else if (event.viewType == ViewType.CURRENT) {
            event.viewType = viewType
        } else if (event.viewType != ViewType.EDIT) {
            viewType = event.viewType
            if (event.viewType == ViewType.AGENDA || event.viewType == ViewType.DAY ||
                    Utils.getAllowWeekForDetailView() && event.viewType == ViewType.WEEK) {
                mDetailViewType = viewType
            }
        }
        if (DEBUG) {
            Log.d(TAG, "vvvvvvvvvvvvvvv")
            Log.d(
                    TAG,
                    "Start  " + if (event.startTime == null) "null" else event.startTime.toString()
            )
            Log.d(TAG, "End    " + if (event.endTime == null) "null" else event.endTime.toString())
            Log.d(
                    TAG,
                    "Select " + if (event.selectedTime == null) "null"
                    else event.selectedTime.toString()
            )
            Log.d(TAG, "mTime  " + if (mTime == null) "null" else mTime.toString())
        }
        var startMillis: Long = 0
        val temp = event.startTime
        if (temp != null) {
            startMillis = (event.startTime as Time).toMillis(false)
        }

        // Set mTime if selectedTime is set
        val temp1 = event.selectedTime
        if (temp1 != null && temp1.toMillis(false) != 0L) {
            mTime?.set(event.selectedTime)
        } else {
            if (startMillis != 0L) {
                // selectedTime is not set so set mTime to startTime iff it is not
                // within start and end times
                val mtimeMillis: Long = mTime?.toMillis(false) as Long
                val temp2 = event.endTime
                if (mtimeMillis < startMillis ||
                        temp2 != null && mtimeMillis > temp2.toMillis(false)) {
                    mTime.set(event.startTime)
                }
            }
            event.selectedTime = mTime
        }
        // Store the formatting flags if this is an update to the title
        if (event.eventType == EventType.UPDATE_TITLE) {
            dateFlags = event.extraLong
        }

        // Fix up start time if not specified
        if (startMillis == 0L) {
            event.startTime = mTime
        }
        if (DEBUG) {
            Log.d(
                    TAG,
                    "Start  " + if (event.startTime == null) "null" else
                        event.startTime.toString()
            )
            Log.d(TAG, "End    " + if (event.endTime == null) "null" else
                event.endTime.toString())
            Log.d(
                    TAG,
                    "Select " + if (event.selectedTime == null) "null" else
                        event.selectedTime.toString()
            )
            Log.d(TAG, "mTime  " + if (mTime == null) "null" else mTime.toString())
            Log.d(TAG, "^^^^^^^^^^^^^^^")
        }

        // Store the eventId if we're entering edit event
        if ((event.eventType and EventType.VIEW_EVENT_DETAILS) != 0L) {
            if (event.id > 0) {
                eventId = event.id
            } else {
                eventId = -1
            }
        }
        var handled = false
        synchronized(this) {
            mDispatchInProgressCounter++
            if (DEBUG) {
                Log.d(
                        TAG,
                        "sendEvent: Dispatching to " + eventHandlers.size.toString() + " handlers"
                )
            }
            // Dispatch to event handler(s)
            val temp3 = mFirstEventHandler
            if (temp3 != null) {
                // Handle the 'first' one before handling the others
                val handler: EventHandler? = mFirstEventHandler?.second
                if (handler != null && handler.supportedEventTypes and event.eventType != 0L &&
                        !mToBeRemovedEventHandlers.contains(mFirstEventHandler?.first)) {
                    handler.handleEvent(event)
                    handled = true
                }
            }
            val handlers: MutableIterator<MutableMap.MutableEntry<Int,
                CalendarController.EventHandler>> = eventHandlers.entries.iterator()
            while (handlers.hasNext()) {
                val entry: MutableMap.MutableEntry<Int,
                    CalendarController.EventHandler> = handlers.next()
                val key: Int = entry.key.toInt()
                val temp4 = mFirstEventHandler
                if (temp4 != null && key.toInt() == temp4.first.toInt()) {
                    // If this was the 'first' handler it was already handled
                    continue
                }
                val eventHandler: EventHandler = entry.value
                if (eventHandler != null &&
                    eventHandler.supportedEventTypes and event.eventType != 0L) {
                    if (mToBeRemovedEventHandlers.contains(key)) {
                        continue
                    }
                    eventHandler.handleEvent(event)
                    handled = true
                }
            }
            mDispatchInProgressCounter--
            if (mDispatchInProgressCounter == 0) {

                // Deregister removed handlers
                if (mToBeRemovedEventHandlers.size > 0) {
                    for (zombie in mToBeRemovedEventHandlers) {
                        eventHandlers.remove(zombie)
                        val temp5 = mFirstEventHandler
                        if (temp5 != null && zombie.equals(temp5.first)) {
                            mFirstEventHandler = null
                        }
                    }
                    mToBeRemovedEventHandlers.clear()
                }
                // Add new handlers
                if (mToBeAddedFirstEventHandler != null) {
                    mFirstEventHandler = mToBeAddedFirstEventHandler
                    mToBeAddedFirstEventHandler = null
                }
                if (mToBeAddedEventHandlers.size > 0) {
                    for (food in mToBeAddedEventHandlers.entries) {
                        eventHandlers.put(food.key, food.value)
                    }
                }
            }
        }
    }

    /**
     * Adds or updates an event handler. This uses a LinkedHashMap so that we can
     * replace fragments based on the view id they are being expanded into.
     *
     * @param key The view id or placeholder for this handler
     * @param eventHandler Typically a fragment or activity in the calendar app
     */
    fun registerEventHandler(key: Int, eventHandler: EventHandler?) {
        synchronized(this) {
            if (mDispatchInProgressCounter > 0) {
                mToBeAddedEventHandlers.put(key,
                        eventHandler as CalendarController.EventHandler)
            } else {
                eventHandlers.put(key, eventHandler as CalendarController.EventHandler)
            }
        }
    }

    fun registerFirstEventHandler(key: Int, eventHandler: EventHandler?) {
        synchronized(this) {
            registerEventHandler(key, eventHandler)
            if (mDispatchInProgressCounter > 0) {
                mToBeAddedFirstEventHandler = Pair<Int, EventHandler>(key, eventHandler)
            } else {
                mFirstEventHandler = Pair<Int, EventHandler>(key, eventHandler)
            }
        }
    }

    fun deregisterEventHandler(key: Int) {
        synchronized(this) {
            if (mDispatchInProgressCounter > 0) {
                // To avoid ConcurrencyException, stash away the event handler for now.
                mToBeRemovedEventHandlers.add(key)
            } else {
                eventHandlers.remove(key)
                val temp6 = mFirstEventHandler
                if (temp6 != null && temp6.first == key) {
                    mFirstEventHandler = null
                } else {}
            }
        }
    }

    fun deregisterAllEventHandlers() {
        synchronized(this) {
            if (mDispatchInProgressCounter > 0) {
                // To avoid ConcurrencyException, stash away the event handler for now.
                mToBeRemovedEventHandlers.addAll(eventHandlers.keys)
            } else {
                eventHandlers.clear()
                mFirstEventHandler = null
            }
        }
    }

    // FRAG_TODO doesn't work yet
    fun filterBroadcasts(sender: Object?, eventTypes: Long) {
        filters.put(sender, eventTypes)
    }
    /**
     * @return the time that this controller is currently pointed at
     */
    /**
     * Set the time this controller is currently pointed at
     *
     * @param millisTime Time since epoch in millis
     */
    var time: Long?
        get() = mTime?.toMillis(false)
        set(millisTime) {
            mTime?.set(millisTime as Long)
        }

    fun launchViewEvent(eventId: Long, startMillis: Long, endMillis: Long, response: Int) {
        val intent = Intent(Intent.ACTION_VIEW)
        val eventUri: Uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        intent.setData(eventUri)
        intent.setClass(mContext as Context, AllInOneActivity::class.java)
        intent.putExtra(EXTRA_EVENT_BEGIN_TIME, startMillis)
        intent.putExtra(EXTRA_EVENT_END_TIME, endMillis)
        intent.putExtra(ATTENDEE_STATUS, response)
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        mContext?.startActivity(intent)
    }

    private fun eventInfoToString(eventInfo: EventInfo): String {
        var tmp = "Unknown"
        val builder = StringBuilder()
        if (eventInfo.eventType and EventType.GO_TO != 0L) {
            tmp = "Go to time/event"
        } else if (eventInfo.eventType and EventType.VIEW_EVENT != 0L) {
            tmp = "View event"
        } else if (eventInfo.eventType and EventType.VIEW_EVENT_DETAILS != 0L) {
            tmp = "View details"
        } else if (eventInfo.eventType and EventType.EVENTS_CHANGED != 0L) {
            tmp = "Refresh events"
        } else if (eventInfo.eventType and EventType.USER_HOME != 0L) {
            tmp = "Gone home"
        } else if (eventInfo.eventType and EventType.UPDATE_TITLE != 0L) {
            tmp = "Update title"
        }
        builder.append(tmp)
        builder.append(": id=")
        builder.append(eventInfo.id)
        builder.append(", selected=")
        builder.append(eventInfo.selectedTime)
        builder.append(", start=")
        builder.append(eventInfo.startTime)
        builder.append(", end=")
        builder.append(eventInfo.endTime)
        builder.append(", viewType=")
        builder.append(eventInfo.viewType)
        builder.append(", x=")
        builder.append(eventInfo.x)
        builder.append(", y=")
        builder.append(eventInfo.y)
        return builder.toString()
    }

    companion object {
        private const val DEBUG = false
        private const val TAG = "CalendarController"
        const val EVENT_EDIT_ON_LAUNCH = "editMode"
        const val MIN_CALENDAR_YEAR = 1970
        const val MAX_CALENDAR_YEAR = 2036
        const val MIN_CALENDAR_WEEK = 0
        const val MAX_CALENDAR_WEEK = 3497 // weeks between 1/1/1970 and 1/1/2037
        private val instances: WeakHashMap<Context, WeakReference<CalendarController>> =
                WeakHashMap<Context, WeakReference<CalendarController>>()

        /**
         * Pass to the ExtraLong parameter for EventType.GO_TO to signal the time
         * can be ignored
         */
        const val EXTRA_GOTO_DATE: Long = 1
        const val EXTRA_GOTO_TIME: Long = 2
        const val EXTRA_GOTO_BACK_TO_PREVIOUS: Long = 4
        const val EXTRA_GOTO_TODAY: Long = 8

        /**
         * Creates and/or returns an instance of CalendarController associated with
         * the supplied context. It is best to pass in the current Activity.
         *
         * @param context The activity if at all possible.
         */
        @JvmStatic fun getInstance(context: Context?): CalendarController? {
            synchronized(instances) {
                var controller: CalendarController? = null
                val weakController: WeakReference<CalendarController>? = instances.get(context)
                if (weakController != null) {
                    controller = weakController.get()
                }
                if (controller == null) {
                    controller = CalendarController(context)
                    instances.put(context, WeakReference(controller))
                }
                return controller
            }
        }

        /**
         * Removes an instance when it is no longer needed. This should be called in
         * an activity's onDestroy method.
         *
         * @param context The activity used to create the controller
         */
        @JvmStatic fun removeInstance(context: Context?) {
            instances.remove(context)
        }
    }

    init {
        mContext = context
        mUpdateTimezone.run()
        mTime?.setToNow()
        mDetailViewType = Utils.getSharedPreference(
                mContext,
                GeneralPreferences.KEY_DETAILED_VIEW,
                GeneralPreferences.DEFAULT_DETAILED_VIEW
        )
    }
}