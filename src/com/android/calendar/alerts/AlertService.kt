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
package com.android.calendar.alerts

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract.CalendarAlerts
import java.util.ArrayList

/**
 * This service is used to handle calendar event reminders.
 */
class AlertService : Service() {
    @Volatile
    private var mServiceLooper: Looper? = null

    // Added wrapper for testing
    class NotificationWrapper {
        var mNotification: Notification
        var mEventId: Long = 0
        var mBegin: Long = 0
        var mEnd: Long = 0
        var mNw: ArrayList<NotificationWrapper>? = null

        constructor(
            n: Notification,
            notificationId: Int,
            eventId: Long,
            startMillis: Long,
            endMillis: Long,
            doPopup: Boolean
        ) {
            mNotification = n
            mEventId = eventId
            mBegin = startMillis
            mEnd = endMillis

            // popup?
            // notification id?
        }

        constructor(n: Notification) {
            mNotification = n
        }

        fun add(nw: NotificationWrapper?) {
            val temp = mNw
            if (temp == null) {
                mNw = ArrayList<NotificationWrapper>()
            }
            mNw?.add(nw as AlertService.NotificationWrapper)
        }
    }

    // Added wrapper for testing
    class NotificationMgrWrapper(nm: NotificationManager) : NotificationMgr() {
        var mNm: NotificationManager
        @Override
        override fun cancel(id: Int) {
            mNm.cancel(id)
        }

        @Override
        override fun notify(id: Int, nw: NotificationWrapper?) {
            mNm.notify(id, nw?.mNotification)
        }

        init {
            mNm = nm
        }
    }

    internal class NotificationInfo(
        var eventName: String,
        var location: String,
        var description: String,
        var startMillis: Long,
        var endMillis: Long,
        var eventId: Long,
        var allDay: Boolean,
        var newAlert: Boolean
    )

    @Override
    override fun onCreate() {
    }

    @Override
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_REDELIVER_INTENT
    }

    @Override
    override fun onDestroy() {
        mServiceLooper?.quit()
    }

    @Override
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        const val DEBUG = true
        private const val TAG = "AlertService"
        val ALERT_PROJECTION = arrayOf<String>(
            CalendarAlerts._ID, // 0
            CalendarAlerts.EVENT_ID, // 1
            CalendarAlerts.STATE, // 2
            CalendarAlerts.TITLE, // 3
            CalendarAlerts.EVENT_LOCATION, // 4
            CalendarAlerts.SELF_ATTENDEE_STATUS, // 5
            CalendarAlerts.ALL_DAY, // 6
            CalendarAlerts.ALARM_TIME, // 7
            CalendarAlerts.MINUTES, // 8
            CalendarAlerts.BEGIN, // 9
            CalendarAlerts.END, // 10
            CalendarAlerts.DESCRIPTION
        )
        private const val ALERT_INDEX_ID = 0
        private const val ALERT_INDEX_EVENT_ID = 1
        private const val ALERT_INDEX_STATE = 2
        private const val ALERT_INDEX_TITLE = 3
        private const val ALERT_INDEX_EVENT_LOCATION = 4
        private const val ALERT_INDEX_SELF_ATTENDEE_STATUS = 5
        private const val ALERT_INDEX_ALL_DAY = 6
        private const val ALERT_INDEX_ALARM_TIME = 7
        private const val ALERT_INDEX_MINUTES = 8
        private const val ALERT_INDEX_BEGIN = 9
        private const val ALERT_INDEX_END = 10
        private const val ALERT_INDEX_DESCRIPTION = 11
        private val ACTIVE_ALERTS_SELECTION = ("(" + CalendarAlerts.STATE.toString() + "=? OR " +
            CalendarAlerts.STATE.toString() + "=?) AND " +
            CalendarAlerts.ALARM_TIME.toString() + "<=")
        private val ACTIVE_ALERTS_SELECTION_ARGS = arrayOf<String>(
            Integer.toString(CalendarAlerts.STATE_FIRED),
            Integer.toString(CalendarAlerts.STATE_SCHEDULED)
        )
        private const val ACTIVE_ALERTS_SORT = "begin DESC, end DESC"
        private val DISMISS_OLD_SELECTION: String = (CalendarAlerts.END.toString() + "<? AND " +
            CalendarAlerts.STATE + "=?")
        private const val MINUTE_MS = 60 * 1000

        // The grace period before changing a notification's priority bucket.
        private const val MIN_DEPRIORITIZE_GRACE_PERIOD_MS = 15 * MINUTE_MS

        // Hard limit to the number of notifications displayed.
        const val MAX_NOTIFICATIONS = 20
    }
}