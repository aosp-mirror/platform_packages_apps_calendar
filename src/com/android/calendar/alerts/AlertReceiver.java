/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.calendar.alerts;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.telephony.TelephonyManager;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.URLSpan;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.android.calendar.R;
import com.android.calendar.Utils;
import com.android.calendar.alerts.AlertService.NotificationWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
 *    -n "com.android.calendar/.alerts.AlertReceiver"
 */
public class AlertReceiver extends BroadcastReceiver {
    private static final String TAG = "AlertReceiver";

    // The broadcast for notification refreshes scheduled by the app. This is to
    // distinguish the EVENT_REMINDER broadcast sent by the provider.
    public static final String EVENT_REMINDER_APP_ACTION =
            "com.android.calendar.EVENT_REMINDER_APP";

    public static final String ACTION_DISMISS_OLD_REMINDERS = "removeOldReminders";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (AlertService.DEBUG) {
            Log.d(TAG, "onReceive: a=" + intent.getAction() + " " + intent.toString());
        }
        closeNotificationShade(context);
    }

    public static NotificationWrapper makeBasicNotification(Context context, String title,
            String summaryText, long startMillis, long endMillis, long eventId,
            int notificationId, boolean doPopup, int priority) {
        Notification n = buildBasicNotification(new Notification.Builder(context),
                context, title, summaryText, startMillis, endMillis, eventId, notificationId,
                doPopup, priority, false);
        return new NotificationWrapper(n, notificationId, eventId, startMillis, endMillis, doPopup);
    }

    private static Notification buildBasicNotification(Notification.Builder notificationBuilder,
            Context context, String title, String summaryText, long startMillis, long endMillis,
            long eventId, int notificationId, boolean doPopup, int priority,
            boolean addActionButtons) {
        Resources resources = context.getResources();
        if (title == null || title.length() == 0) {
            title = resources.getString(R.string.no_title_label);
        }

        // Create the base notification.
        notificationBuilder.setContentTitle(title);
        notificationBuilder.setContentText(summaryText);
        notificationBuilder.setSmallIcon(R.drawable.stat_notify_calendar);
        if (Utils.isJellybeanOrLater()) {
            // Turn off timestamp.
            notificationBuilder.setWhen(0);

            // Should be one of the values in Notification (ie. Notification.PRIORITY_HIGH, etc).
            // A higher priority will encourage notification manager to expand it.
            notificationBuilder.setPriority(priority);
        }
        return notificationBuilder.getNotification();
    }

    private void closeNotificationShade(Context context) {
        Intent closeNotificationShadeIntent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.sendBroadcast(closeNotificationShadeIntent);
    }
}
