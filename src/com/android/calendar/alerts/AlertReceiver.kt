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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.util.Log
import com.android.calendar.R
import com.android.calendar.Utils
import com.android.calendar.alerts.AlertService.NotificationWrapper

/**
 * Receives android.intent.action.EVENT_REMINDER intents and handles
 * event reminders.  The intent URI specifies an alert id in the
 * CalendarAlerts database table.  This class also receives the
 * BOOT_COMPLETED intent so that it can add a status bar notification
 * if there are Calendar event alarms that have not been dismissed.
 * It also receives the TIME_CHANGED action so that it can fire off
 * snoozed alarms that have become ready.  The real work is done in
 * the AlertService class.
 *
 * To trigger this code after pushing the apk to device:
 * adb shell am broadcast -a "android.intent.action.EVENT_REMINDER"
 * -n "com.android.calendar/.alerts.AlertReceiver"
 */
class AlertReceiver : BroadcastReceiver() {
    @Override
    override fun onReceive(context: Context, intent: Intent) {
        if (AlertService.DEBUG) {
            Log.d(TAG, "onReceive: a=" + intent.getAction().toString() + " " + intent.toString())
        }
    }

    companion object {
        private const val TAG = "AlertReceiver"

        // The broadcast for notification refreshes scheduled by the app. This is to
        // distinguish the EVENT_REMINDER broadcast sent by the provider.
        const val EVENT_REMINDER_APP_ACTION = "com.android.calendar.EVENT_REMINDER_APP"
        const val ACTION_DISMISS_OLD_REMINDERS = "removeOldReminders"
        fun makeBasicNotification(
            context: Context,
            title: String,
            summaryText: String,
            startMillis: Long,
            endMillis: Long,
            eventId: Long,
            notificationId: Int,
            doPopup: Boolean,
            priority: Int
        ): NotificationWrapper {
            val n: Notification = buildBasicNotification(
                Notification.Builder(context),
                context,
                title,
                summaryText,
                startMillis,
                endMillis,
                eventId,
                notificationId,
                doPopup,
                priority, false
            )
            return NotificationWrapper(n, notificationId, eventId, startMillis, endMillis, doPopup)
        }

        private fun buildBasicNotification(
            notificationBuilder: Notification.Builder,
            context: Context,
            title: String,
            summaryText: String,
            startMillis: Long,
            endMillis: Long,
            eventId: Long,
            notificationId: Int,
            doPopup: Boolean,
            priority: Int,
            addActionButtons: Boolean
        ): Notification {
            var title: String? = title
            val resources: Resources = context.getResources()
            if (title == null || title.length == 0) {
                title = resources.getString(R.string.no_title_label)
            }

            // Create the base notification.
            notificationBuilder.setContentTitle(title)
            notificationBuilder.setContentText(summaryText)
            notificationBuilder.setSmallIcon(R.drawable.stat_notify_calendar)
            if (Utils.isJellybeanOrLater()) {
                // Turn off timestamp.
                notificationBuilder.setWhen(0)

                // Should be one of the values in Notification
                // (ie. Notification.PRIORITY_HIGH, etc).
                // A higher priority will encourage notification manager to expand it.
                notificationBuilder.setPriority(priority)
            }
            return notificationBuilder.getNotification()
        }
    }
}