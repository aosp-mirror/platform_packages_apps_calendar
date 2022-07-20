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

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.CursorLoader
import android.content.Intent
import android.content.Loader
import android.content.res.Resources
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.text.format.DateUtils
import android.text.format.Time
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.android.calendar.R
import com.android.calendar.Utils
import com.android.calendar.widget.CalendarAppWidgetModel.DayInfo
import com.android.calendar.widget.CalendarAppWidgetModel.EventInfo
import com.android.calendar.widget.CalendarAppWidgetModel.RowInfo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CalendarAppWidgetService : RemoteViewsService() {
    companion object {
        private const val TAG = "CalendarWidget"
        const val EVENT_MIN_COUNT = 20
        const val EVENT_MAX_COUNT = 100

        // Minimum delay between queries on the database for widget updates in ms
        const val WIDGET_UPDATE_THROTTLE = 500
        private val EVENT_SORT_ORDER: String = (Instances.START_DAY.toString() + " ASC, " +
            Instances.START_MINUTE + " ASC, " + Instances.END_DAY + " ASC, " +
            Instances.END_MINUTE + " ASC LIMIT " + EVENT_MAX_COUNT)
        private val EVENT_SELECTION: String = Calendars.VISIBLE.toString() + "=1"
        private val EVENT_SELECTION_HIDE_DECLINED: String =
            (Calendars.VISIBLE.toString() + "=1 AND " +
                Instances.SELF_ATTENDEE_STATUS + "!=" + Attendees.ATTENDEE_STATUS_DECLINED)
        @JvmField
        val EVENT_PROJECTION = arrayOf<String>(
            Instances.ALL_DAY,
            Instances.BEGIN,
            Instances.END,
            Instances.TITLE,
            Instances.EVENT_LOCATION,
            Instances.EVENT_ID,
            Instances.START_DAY,
            Instances.END_DAY,
            Instances.DISPLAY_COLOR, // If SDK < 16, set to Instances.CALENDAR_COLOR.
            Instances.SELF_ATTENDEE_STATUS
        )
        const val INDEX_ALL_DAY = 0
        const val INDEX_BEGIN = 1
        const val INDEX_END = 2
        const val INDEX_TITLE = 3
        const val INDEX_EVENT_LOCATION = 4
        const val INDEX_EVENT_ID = 5
        const val INDEX_START_DAY = 6
        const val INDEX_END_DAY = 7
        const val INDEX_COLOR = 8
        const val INDEX_SELF_ATTENDEE_STATUS = 9
        const val MAX_DAYS = 7
        private val SEARCH_DURATION: Long = MAX_DAYS * DateUtils.DAY_IN_MILLIS

        /**
         * Update interval used when no next-update calculated, or bad trigger time in past.
         * Unit: milliseconds.
         */
        private val UPDATE_TIME_NO_EVENTS: Long = DateUtils.HOUR_IN_MILLIS * 6

        /**
         * Format given time for debugging output.
         *
         * @param unixTime Target time to report.
         * @param now Current system time from [System.currentTimeMillis]
         * for calculating time difference.
         */
        fun formatDebugTime(unixTime: Long, now: Long): String {
            val time = Time()
            time.set(unixTime)
            var delta = unixTime - now
            return if (delta > DateUtils.MINUTE_IN_MILLIS) {
                delta /= DateUtils.MINUTE_IN_MILLIS
                String.format(
                    "[%d] %s (%+d mins)", unixTime,
                    time.format("%H:%M:%S"), delta
                )
            } else {
                delta /= DateUtils.SECOND_IN_MILLIS
                String.format(
                    "[%d] %s (%+d secs)", unixTime,
                    time.format("%H:%M:%S"), delta
                )
            }
        }

        init {
            if (!Utils.isJellybeanOrLater()) {
                EVENT_PROJECTION[INDEX_COLOR] = Instances.CALENDAR_COLOR
            }
        }
    }

    @Override
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return CalendarFactory(getApplicationContext(), intent)
    }

    class CalendarFactory : BroadcastReceiver, RemoteViewsService.RemoteViewsFactory,
                            Loader.OnLoadCompleteListener<Cursor?> {
        private var mContext: Context? = null
        private var mResources: Resources? = null
        private var mLastSerialNum = -1
        private var mLoader: CursorLoader? = null
        private val mHandler: Handler = Handler()
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
        private var mAppWidgetId = 0
        private var mDeclinedColor = 0
        private var mStandardColor = 0
        private var mAllDayColor = 0
        private val mTimezoneChanged: Runnable = object : Runnable {
            @Override
            override fun run() {
                if (mLoader != null) {
                    mLoader?.forceLoad()
                }
            }
        }

        private fun createUpdateLoaderRunnable(
            selection: String,
            result: PendingResult,
            version: Int
        ): Runnable {
            return object : Runnable {
                @Override
                override fun run() {
                    // If there is a newer load request in the queue, skip loading.
                    if (mLoader != null && version >= currentVersion.get()) {
                        val uri: Uri = createLoaderUri()
                        mLoader?.setUri(uri)
                        mLoader?.setSelection(selection)
                        synchronized(mLock) { mLastSerialNum = ++mSerialNum }
                        mLoader?.forceLoad()
                    }
                    result.finish()
                }
            }
        }

        constructor(context: Context, intent: Intent) {
            mContext = context
            mResources = context.getResources()
            mAppWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            )
            mDeclinedColor = mResources?.getColor(R.color.appwidget_item_declined_color) as Int
            mStandardColor = mResources?.getColor(R.color.appwidget_item_standard_color) as Int
            mAllDayColor = mResources?.getColor(R.color.appwidget_item_allday_color) as Int
        }

        constructor() {
            // This is being created as part of onReceive
        }

        @Override
        override fun onCreate() {
            val selection = queryForSelection()
            initLoader(selection)
        }

        @Override
        override fun onDataSetChanged() {
        }

        @Override
        override fun onDestroy() {
            if (mLoader != null) {
                mLoader?.reset()
            }
        }

        @Override
        override fun getLoadingView(): RemoteViews {
            val views = RemoteViews(mContext?.getPackageName(), R.layout.appwidget_loading)
            return views
        }

        @Override
        override fun getViewAt(position: Int): RemoteViews? {
            // we use getCount here so that it doesn't return null when empty
            if (position < 0 || position >= getCount()) {
                return null
            }
            if (mModel == null) {
                val views = RemoteViews(
                    mContext?.getPackageName(),
                    R.layout.appwidget_loading
                )
                val intent: Intent = CalendarAppWidgetProvider.getLaunchFillInIntent(
                    mContext,
                    0,
                    0,
                    0,
                    false
                )
                views.setOnClickFillInIntent(R.id.appwidget_loading, intent)
                return views
            }
            if (mModel!!.mEventInfos.isEmpty() || mModel!!.mRowInfos.isEmpty()) {
                val views = RemoteViews(
                    mContext?.getPackageName(),
                    R.layout.appwidget_no_events
                )
                val intent: Intent = CalendarAppWidgetProvider.getLaunchFillInIntent(
                    mContext,
                    0,
                    0,
                    0,
                    false
                )
                views.setOnClickFillInIntent(R.id.appwidget_no_events, intent)
                return views
            }
            val rowInfo: RowInfo? = mModel?.mRowInfos?.get(position)
            return if (rowInfo!!.mType == RowInfo.TYPE_DAY) {
                val views = RemoteViews(
                    mContext?.getPackageName(),
                    R.layout.appwidget_day
                )
                val dayInfo: DayInfo? = mModel?.mDayInfos?.get(rowInfo.mIndex)
                updateTextView(views, R.id.date, View.VISIBLE, dayInfo!!.mDayLabel)
                views
            } else {
                val views: RemoteViews?
                val eventInfo: EventInfo? = mModel?.mEventInfos?.get(rowInfo.mIndex)
                if (eventInfo!!.allDay) {
                    views = RemoteViews(
                        mContext?.getPackageName(),
                        R.layout.widget_all_day_item
                    )
                } else {
                    views = RemoteViews(mContext?.getPackageName(), R.layout.widget_item)
                }
                val displayColor: Int = Utils.getDisplayColorFromColor(eventInfo.color)
                val now: Long = System.currentTimeMillis()
                if (!eventInfo.allDay && eventInfo.start <= now && now <= eventInfo.end) {
                    views.setInt(
                        R.id.widget_row, "setBackgroundResource",
                        R.drawable.agenda_item_bg_secondary
                    )
                } else {
                    views.setInt(
                        R.id.widget_row, "setBackgroundResource",
                        R.drawable.agenda_item_bg_primary
                    )
                }
                if (!eventInfo.allDay) {
                    updateTextView(views, R.id.`when`, eventInfo.visibWhen
                        as Int, eventInfo.`when`)
                    updateTextView(views, R.id.where, eventInfo.visibWhere
                        as Int, eventInfo.where)
                }
                updateTextView(views, R.id.title, eventInfo.visibTitle as Int, eventInfo.title)
                views.setViewVisibility(R.id.agenda_item_color, View.VISIBLE)
                val selfAttendeeStatus: Int = eventInfo.selfAttendeeStatus as Int
                if (eventInfo.allDay) {
                    if (selfAttendeeStatus == Attendees.ATTENDEE_STATUS_INVITED) {
                        views.setInt(
                            R.id.agenda_item_color, "setImageResource",
                            R.drawable.widget_chip_not_responded_bg
                        )
                        views.setInt(R.id.title, "setTextColor", displayColor)
                    } else {
                        views.setInt(
                            R.id.agenda_item_color, "setImageResource",
                            R.drawable.widget_chip_responded_bg
                        )
                        views.setInt(R.id.title, "setTextColor", mAllDayColor)
                    }
                    if (selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED) {
                        // 40% opacity
                        views.setInt(
                            R.id.agenda_item_color, "setColorFilter",
                            Utils.getDeclinedColorFromColor(displayColor)
                        )
                    } else {
                        views.setInt(R.id.agenda_item_color, "setColorFilter", displayColor)
                    }
                } else if (selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED) {
                    views.setInt(R.id.title, "setTextColor", mDeclinedColor)
                    views.setInt(R.id.`when`, "setTextColor", mDeclinedColor)
                    views.setInt(R.id.where, "setTextColor", mDeclinedColor)
                    views.setInt(
                        R.id.agenda_item_color, "setImageResource",
                        R.drawable.widget_chip_responded_bg
                    )
                    // 40% opacity
                    views.setInt(
                        R.id.agenda_item_color, "setColorFilter",
                        Utils.getDeclinedColorFromColor(displayColor)
                    )
                } else {
                    views.setInt(R.id.title, "setTextColor", mStandardColor)
                    views.setInt(R.id.`when`, "setTextColor", mStandardColor)
                    views.setInt(R.id.where, "setTextColor", mStandardColor)
                    if (selfAttendeeStatus == Attendees.ATTENDEE_STATUS_INVITED) {
                        views.setInt(
                            R.id.agenda_item_color, "setImageResource",
                            R.drawable.widget_chip_not_responded_bg
                        )
                    } else {
                        views.setInt(
                            R.id.agenda_item_color, "setImageResource",
                            R.drawable.widget_chip_responded_bg
                        )
                    }
                    views.setInt(R.id.agenda_item_color, "setColorFilter", displayColor)
                }
                var start: Long = eventInfo.start as Long
                var end: Long = eventInfo.end as Long
                // An element in ListView.
                if (eventInfo.allDay) {
                    val tz: String? = Utils.getTimeZone(mContext, null)
                    val recycle = Time()
                    start = Utils.convertAlldayLocalToUTC(recycle, start, tz as String)
                    end = Utils.convertAlldayLocalToUTC(recycle, end, tz as String)
                }
                val fillInIntent: Intent = CalendarAppWidgetProvider.getLaunchFillInIntent(
                    mContext, eventInfo.id, start, end, eventInfo.allDay
                )
                views.setOnClickFillInIntent(R.id.widget_row, fillInIntent)
                views
            }
        }

        @Override
        override fun getViewTypeCount(): Int {
            return 5
        }

        @Override
        override fun getCount(): Int {
            // if there are no events, we still return 1 to represent the "no
            // events" view
            if (mModel == null) {
                return 1
            }
            return Math.max(1, mModel?.mRowInfos?.size as Int)
        }

        @Override
        override fun getItemId(position: Int): Long {
            if (mModel == null || mModel?.mRowInfos?.isEmpty() as Boolean ||
                position >= getCount()) {
                return 0
            }
            val rowInfo: RowInfo = mModel?.mRowInfos?.get(position) as RowInfo
            if (rowInfo.mType == RowInfo.TYPE_DAY) {
                return rowInfo.mIndex.toLong()
            }
            val eventInfo: EventInfo = mModel?.mEventInfos?.get(rowInfo.mIndex) as EventInfo
            val prime: Long = 31
            var result: Long = 1
            result = prime * result + (eventInfo.id xor (eventInfo.id ushr 32)) as Int
            result = prime * result + (eventInfo.start xor (eventInfo.start ushr 32)) as Int
            return result
        }

        @Override
        override fun hasStableIds(): Boolean {
            return true
        }

        /**
         * Query across all calendars for upcoming event instances from now
         * until some time in the future. Widen the time range that we query by
         * one day on each end so that we can catch all-day events. All-day
         * events are stored starting at midnight in UTC but should be included
         * in the list of events starting at midnight local time. This may fetch
         * more events than we actually want, so we filter them out later.
         *
         * @param selection The selection string for the loader to filter the query with.
         */
        fun initLoader(selection: String?) {
            if (LOGD) Log.d(TAG, "Querying for widget events...")

            // Search for events from now until some time in the future
            val uri: Uri = createLoaderUri()
            mLoader = CursorLoader(
                mContext, uri, EVENT_PROJECTION, selection, null,
                EVENT_SORT_ORDER
            )
            mLoader?.setUpdateThrottle(WIDGET_UPDATE_THROTTLE.toLong())
            synchronized(mLock) { mLastSerialNum = ++mSerialNum }
            mLoader?.registerListener(mAppWidgetId, this)
            mLoader?.startLoading()
        }

        /**
         * This gets the selection string for the loader.  This ends up doing a query in the
         * shared preferences.
         */
        private fun queryForSelection(): String {
            return if (Utils.getHideDeclinedEvents(mContext)) EVENT_SELECTION_HIDE_DECLINED
            else EVENT_SELECTION
        }

        /**
         * @return The uri for the loader
         */
        private fun createLoaderUri(): Uri {
            val now: Long = System.currentTimeMillis()
            // Add a day on either side to catch all-day events
            val begin: Long = now - DateUtils.DAY_IN_MILLIS
            val end: Long =
                now + SEARCH_DURATION + DateUtils.DAY_IN_MILLIS
            return Uri.withAppendedPath(
                Instances.CONTENT_URI,
                begin.toString() + "/" + end
            )
        }

        /**
         * Calculates and returns the next time we should push widget updates.
         */
        private fun calculateUpdateTime(
            model: CalendarAppWidgetModel,
            now: Long,
            timeZone: String
        ): Long {
            // Make sure an update happens at midnight or earlier
            var minUpdateTime = getNextMidnightTimeMillis(timeZone)
            for (event in model.mEventInfos) {
                val start: Long
                val end: Long
                start = event.start
                end = event.end

                // We want to update widget when we enter/exit time range of an event.
                if (now < start) {
                    minUpdateTime = Math.min(minUpdateTime, start)
                } else if (now < end) {
                    minUpdateTime = Math.min(minUpdateTime, end)
                }
            }
            return minUpdateTime
        }

        /*
         * (non-Javadoc)
         * @see
         * android.content.Loader.OnLoadCompleteListener#onLoadComplete(android
         * .content.Loader, java.lang.Object)
         */
        @Override
        override fun onLoadComplete(loader: Loader<Cursor?>?, cursor: Cursor?) {
            if (cursor == null) {
                return
            }
            // If a newer update has happened since we started clean up and
            // return
            synchronized(mLock) {
                if (cursor.isClosed()) {
                    Log.wtf(TAG, "Got a closed cursor from onLoadComplete")
                    return
                }
                if (mLastSerialNum != mSerialNum) {
                    return
                }
                val now: Long = System.currentTimeMillis()
                val tz: String? = Utils.getTimeZone(mContext, mTimezoneChanged)

                // Copy it to a local static cursor.
                val matrixCursor: MatrixCursor? = Utils.matrixCursorFromCursor(cursor)
                try {
                    mModel = buildAppWidgetModel(mContext, matrixCursor, tz)
                } finally {
                    if (matrixCursor != null) {
                        matrixCursor.close()
                    }
                    if (cursor != null) {
                        cursor.close()
                    }
                }

                // Schedule an alarm to wake ourselves up for the next update.
                // We also cancel
                // all existing wake-ups because PendingIntents don't match
                // against extras.
                var triggerTime = calculateUpdateTime(mModel as CalendarAppWidgetModel,
                    now, tz as String)

                // If no next-update calculated, or bad trigger time in past,
                // schedule
                // update about six hours from now.
                if (triggerTime < now) {
                    Log.w(TAG, "Encountered bad trigger time " + formatDebugTime(triggerTime, now))
                    triggerTime = now + UPDATE_TIME_NO_EVENTS
                }
                val alertManager: AlarmManager = mContext
                    ?.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pendingUpdate: PendingIntent = CalendarAppWidgetProvider
                    .getUpdateIntent(mContext)
                alertManager.cancel(pendingUpdate)
                alertManager.set(AlarmManager.RTC, triggerTime, pendingUpdate)
                val time = Time(Utils.getTimeZone(mContext, null))
                time.setToNow()
                if (time.normalize(true) !== sLastUpdateTime) {
                    val time2 = Time(Utils.getTimeZone(mContext, null))
                    time2.set(sLastUpdateTime)
                    time2.normalize(true)
                    if (time.year !== time2.year || time.yearDay !== time2.yearDay) {
                        val updateIntent = Intent(
                            Utils.getWidgetUpdateAction(mContext as Context)
                        )
                        mContext?.sendBroadcast(updateIntent)
                    }
                    sLastUpdateTime = time.toMillis(true)
                }
                val widgetManager: AppWidgetManager = AppWidgetManager.getInstance(mContext)
                if (widgetManager == null) {
                    return
                }
                if (mAppWidgetId == -1) {
                    val ids: IntArray = widgetManager.getAppWidgetIds(
                        CalendarAppWidgetProvider
                            .getComponentName(mContext)
                    )
                    widgetManager.notifyAppWidgetViewDataChanged(ids, R.id.events_list)
                } else {
                    widgetManager.notifyAppWidgetViewDataChanged(mAppWidgetId, R.id.events_list)
                }
            }
        }

        @Override
        override fun onReceive(context: Context?, intent: Intent) {
            if (LOGD) Log.d(TAG, "AppWidgetService received an intent. It was " + intent.toString())
            mContext = context

            // We cannot do any queries from the UI thread, so push the 'selection' query
            // to a background thread.  However the implementation of the latter query
            // (cursor loading) uses CursorLoader which must be initiated from the UI thread,
            // so there is some convoluted handshaking here.
            //
            // Note that as currently implemented, this must run in a single threaded executor
            // or else the loads may be run out of order.
            //
            // TODO: Remove use of mHandler and CursorLoader, and do all the work synchronously
            // in the background thread.  All the handshaking going on here between the UI and
            // background thread with using goAsync, mHandler, and CursorLoader is confusing.
            val result: PendingResult = goAsync()
            executor.submit(object : Runnable {
                @Override
                override fun run() {
                    // We always complete queryForSelection() even if the load task ends up being
                    // canceled because of a more recent one.  Optimizing this to allow
                    // canceling would require keeping track of all the PendingResults
                    // (from goAsync) to abort them.  Defer this until it becomes a problem.
                    val selection = queryForSelection()
                    if (mLoader == null) {
                        mAppWidgetId = -1
                        mHandler.post(object : Runnable {
                            @Override
                            override fun run() {
                                initLoader(selection)
                                result.finish()
                            }
                        })
                    } else {
                        mHandler.post(
                            createUpdateLoaderRunnable(
                                selection, result,
                                currentVersion.incrementAndGet()
                            )
                        )
                    }
                }
            })
        }

        internal companion object {
            private const val LOGD = false

            // Suppress unnecessary logging about update time. Need to be static as this object is
            // re-instantiated frequently.
            // TODO: It seems loadData() is called via onCreate() four times, which should mean
            // unnecessary CalendarFactory object is created and dropped. It is not efficient.
            private var sLastUpdateTime = UPDATE_TIME_NO_EVENTS
            private var mModel: CalendarAppWidgetModel? = null
            private val mLock: Object = Object()

            @Volatile
            private var mSerialNum = 0
            private val currentVersion: AtomicInteger = AtomicInteger(0)

            /* @VisibleForTesting */
            @JvmStatic protected fun buildAppWidgetModel(
                context: Context?,
                cursor: Cursor?,
                timeZone: String?
            ): CalendarAppWidgetModel {
                val model = CalendarAppWidgetModel(context as Context, timeZone)
                model.buildFromCursor(cursor as Cursor, timeZone)
                return model
            }

            @JvmStatic private fun getNextMidnightTimeMillis(timezone: String): Long {
                val time = Time()
                time.setToNow()
                time.monthDay++
                time.hour = 0
                time.minute = 0
                time.second = 0
                val midnightDeviceTz: Long = time.normalize(true)
                time.timezone = timezone
                time.setToNow()
                time.monthDay++
                time.hour = 0
                time.minute = 0
                time.second = 0
                val midnightHomeTz: Long = time.normalize(true)
                return Math.min(midnightDeviceTz, midnightHomeTz)
            }

            @JvmStatic fun updateTextView(
                views: RemoteViews,
                id: Int,
                visibility: Int,
                string: String?
            ) {
                views.setViewVisibility(id, visibility)
                if (visibility == View.VISIBLE) {
                    views.setTextViewText(id, string)
                }
            }
        }
    }
}