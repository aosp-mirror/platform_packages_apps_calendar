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
 * limitations under the License
 */
package com.android.calendar.alerts

import android.app.IntentService
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.CalendarContract.CalendarAlerts
import androidx.core.app.TaskStackBuilder
import android.util.Log
import com.android.calendar.EventInfoActivity
import com.android.calendar.alerts.GlobalDismissManager.AlarmId
import java.util.LinkedList
import java.util.List

/**
 * Service for asynchronously marking fired alarms as dismissed.
 */
class DismissAlarmsService : IntentService("DismissAlarmsService") {
    @Override
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @Override
    override fun onHandleIntent(intent: Intent?) {
        if (AlertService.DEBUG) {
            Log.d(TAG, "onReceive: a=" + intent?.getAction().toString() + " " + intent.toString())
        }
        val eventId = intent?.getLongExtra(AlertUtils.EVENT_ID_KEY, -1)
        val eventStart = intent?.getLongExtra(AlertUtils.EVENT_START_KEY, -1)
        val eventEnd = intent?.getLongExtra(AlertUtils.EVENT_END_KEY, -1)
        val eventIds = intent?.getLongArrayExtra(AlertUtils.EVENT_IDS_KEY)
        val eventStarts = intent?.getLongArrayExtra(AlertUtils.EVENT_STARTS_KEY)
        val notificationId = intent?.getIntExtra(AlertUtils.NOTIFICATION_ID_KEY, -1)
        val alarmIds = LinkedList<AlarmId>()
        val uri: Uri = CalendarAlerts.CONTENT_URI
        val selection: String

        // Dismiss a specific fired alarm if id is present, otherwise, dismiss all alarms
        if (eventId != -1L) {
            alarmIds.add(AlarmId(eventId as Long, eventStart as Long))
            selection =
                CalendarAlerts.STATE.toString() + "=" + CalendarAlerts.STATE_FIRED + " AND " +
                    CalendarAlerts.EVENT_ID + "=" + eventId
        } else if (eventIds != null && eventIds.size > 0 && eventStarts != null &&
            eventIds.size == eventStarts.size) {
            selection = buildMultipleEventsQuery(eventIds)
            for (i in eventIds.indices) {
                alarmIds.add(AlarmId(eventIds[i], eventStarts[i]))
            }
        } else {
            // NOTE: I don't believe that this ever happens.
            selection = CalendarAlerts.STATE.toString() + "=" + CalendarAlerts.STATE_FIRED
        }
        GlobalDismissManager.dismissGlobally(getApplicationContext(),
            alarmIds as List<GlobalDismissManager.AlarmId>)
        val resolver: ContentResolver = getContentResolver()
        val values = ContentValues()
        values.put(PROJECTION[COLUMN_INDEX_STATE], CalendarAlerts.STATE_DISMISSED)
        resolver.update(uri, values, selection, null)

        // Remove from notification bar.
        if (notificationId != -1) {
            val nm: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationId as Int)
        }
        if (SHOW_ACTION.equals(intent.getAction())) {
            // Show event on Calendar app by building an intent and task stack to start
            // EventInfoActivity with AllInOneActivity as the parent activity rooted to home.
            val i: Intent = AlertUtils.buildEventViewIntent(this, eventId as Long,
                                                            eventStart as Long, eventEnd as Long)
            TaskStackBuilder.create(this)
                .addParentStack(EventInfoActivity::class.java).addNextIntent(i).startActivities()
        }
    }

    private fun buildMultipleEventsQuery(eventIds: LongArray): String {
        val selection = StringBuilder()
        selection.append(CalendarAlerts.STATE)
        selection.append("=")
        selection.append(CalendarAlerts.STATE_FIRED)
        if (eventIds.size > 0) {
            selection.append(" AND (")
            selection.append(CalendarAlerts.EVENT_ID)
            selection.append("=")
            selection.append(eventIds[0])
            for (i in 1 until eventIds.size) {
                selection.append(" OR ")
                selection.append(CalendarAlerts.EVENT_ID)
                selection.append("=")
                selection.append(eventIds[i])
            }
            selection.append(")")
        }
        return selection.toString()
    }

    companion object {
        private const val TAG = "DismissAlarmsService"
        const val SHOW_ACTION = "com.android.calendar.SHOW"
        const val DISMISS_ACTION = "com.android.calendar.DISMISS"
        private val PROJECTION = arrayOf<String>(
            CalendarAlerts.STATE
        )
        private const val COLUMN_INDEX_STATE = 0
    }
}