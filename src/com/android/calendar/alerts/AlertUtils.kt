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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.android.calendar.EventInfoActivity
import com.android.calendar.Utils

object AlertUtils {
    private const val TAG = "AlertUtils"
    const val DEBUG = true
    const val SNOOZE_DELAY = 5 * 60 * 1000L

    // We use one notification id for the expired events notification.  All
    // other notifications (the 'active' future/concurrent ones) use a unique ID.
    const val EXPIRED_GROUP_NOTIFICATION_ID = 0
    const val EVENT_ID_KEY = "eventid"
    const val EVENT_START_KEY = "eventstart"
    const val EVENT_END_KEY = "eventend"
    const val NOTIFICATION_ID_KEY = "notificationid"
    const val EVENT_IDS_KEY = "eventids"
    const val EVENT_STARTS_KEY = "starts"

    // A flag for using local storage to save alert state instead of the alerts DB table.
    // This allows the unbundled app to run alongside other calendar apps without eating
    // alerts from other apps.
    var BYPASS_DB = true

    /**
     * Creates an AlarmManagerInterface that wraps a real AlarmManager.  The alarm code
     * was abstracted to an interface to make it testable.
     */
    @JvmStatic fun createAlarmManager(context: Context): AlarmManagerInterface {
        val mgr: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return object : AlarmManagerInterface {
            override operator fun set(type: Int, triggerAtMillis: Long, operation: PendingIntent?) {
                if (com.android.calendar.Utils.isKeyLimePieOrLater()) {
                    mgr.setExact(type, triggerAtMillis, operation!!)
                } else {
                    mgr.set(type, triggerAtMillis, operation!!)
                }
            }
        }
    }

    /**
     * Schedules an alarm intent with the system AlarmManager that will notify
     * listeners when a reminder should be fired. The provider will keep
     * scheduled reminders up to date but apps may use this to implement snooze
     * functionality without modifying the reminders table. Scheduled alarms
     * will generate an intent using AlertReceiver.EVENT_REMINDER_APP_ACTION.
     *
     * @param context A context for referencing system resources
     * @param manager The AlarmManager to use or null
     * @param alarmTime The time to fire the intent in UTC millis since epoch
     */
    @JvmStatic fun scheduleAlarm(
        context: Context?,
        manager: AlarmManagerInterface?,
        alarmTime: Long
    ) {
    }

    @JvmStatic fun buildEventViewIntent(c: Context, eventId: Long, begin: Long, end: Long): Intent {
        val i = Intent(Intent.ACTION_VIEW)
        val builder: Uri.Builder = CalendarContract.CONTENT_URI.buildUpon()
        builder.appendEncodedPath("events/$eventId")
        i.setData(builder.build())
        i.setClass(c, EventInfoActivity::class.java)
        i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        return i
    }
}
