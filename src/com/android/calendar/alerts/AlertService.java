/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.CalendarAlerts;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;

import com.android.calendar.R;
import com.android.calendar.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;

/**
 * This service is used to handle calendar event reminders.
 */
public class AlertService extends Service {
    static final boolean DEBUG = true;
    private static final String TAG = "AlertService";

    private volatile Looper mServiceLooper;

    static final String[] ALERT_PROJECTION = new String[] {
        CalendarAlerts._ID,                     // 0
        CalendarAlerts.EVENT_ID,                // 1
        CalendarAlerts.STATE,                   // 2
        CalendarAlerts.TITLE,                   // 3
        CalendarAlerts.EVENT_LOCATION,          // 4
        CalendarAlerts.SELF_ATTENDEE_STATUS,    // 5
        CalendarAlerts.ALL_DAY,                 // 6
        CalendarAlerts.ALARM_TIME,              // 7
        CalendarAlerts.MINUTES,                 // 8
        CalendarAlerts.BEGIN,                   // 9
        CalendarAlerts.END,                     // 10
        CalendarAlerts.DESCRIPTION,             // 11
    };

    private static final int ALERT_INDEX_ID = 0;
    private static final int ALERT_INDEX_EVENT_ID = 1;
    private static final int ALERT_INDEX_STATE = 2;
    private static final int ALERT_INDEX_TITLE = 3;
    private static final int ALERT_INDEX_EVENT_LOCATION = 4;
    private static final int ALERT_INDEX_SELF_ATTENDEE_STATUS = 5;
    private static final int ALERT_INDEX_ALL_DAY = 6;
    private static final int ALERT_INDEX_ALARM_TIME = 7;
    private static final int ALERT_INDEX_MINUTES = 8;
    private static final int ALERT_INDEX_BEGIN = 9;
    private static final int ALERT_INDEX_END = 10;
    private static final int ALERT_INDEX_DESCRIPTION = 11;

    private static final String ACTIVE_ALERTS_SELECTION = "(" + CalendarAlerts.STATE + "=? OR "
            + CalendarAlerts.STATE + "=?) AND " + CalendarAlerts.ALARM_TIME + "<=";

    private static final String[] ACTIVE_ALERTS_SELECTION_ARGS = new String[] {
            Integer.toString(CalendarAlerts.STATE_FIRED),
            Integer.toString(CalendarAlerts.STATE_SCHEDULED)
    };

    private static final String ACTIVE_ALERTS_SORT = "begin DESC, end DESC";

    private static final String DISMISS_OLD_SELECTION = CalendarAlerts.END + "<? AND "
            + CalendarAlerts.STATE + "=?";

    private static final int MINUTE_MS = 60 * 1000;

    // The grace period before changing a notification's priority bucket.
    private static final int MIN_DEPRIORITIZE_GRACE_PERIOD_MS = 15 * MINUTE_MS;

    // Hard limit to the number of notifications displayed.
    public static final int MAX_NOTIFICATIONS = 20;

    // Added wrapper for testing
    public static class NotificationWrapper {
        Notification mNotification;
        long mEventId;
        long mBegin;
        long mEnd;
        ArrayList<NotificationWrapper> mNw;

        public NotificationWrapper(Notification n, int notificationId, long eventId,
                long startMillis, long endMillis, boolean doPopup) {
            mNotification = n;
            mEventId = eventId;
            mBegin = startMillis;
            mEnd = endMillis;

            // popup?
            // notification id?
        }

        public NotificationWrapper(Notification n) {
            mNotification = n;
        }

        public void add(NotificationWrapper nw) {
            if (mNw == null) {
                mNw = new ArrayList<NotificationWrapper>();
            }
            mNw.add(nw);
        }
    }

    // Added wrapper for testing
    public static class NotificationMgrWrapper extends NotificationMgr {
        NotificationManager mNm;

        public NotificationMgrWrapper(NotificationManager nm) {
            mNm = nm;
        }

        @Override
        public void cancel(int id) {
            mNm.cancel(id);
        }

        @Override
        public void notify(int id, NotificationWrapper nw) {
            mNm.notify(id, nw.mNotification);
        }
    }

    static class NotificationInfo {
        String eventName;
        String location;
        String description;
        long startMillis;
        long endMillis;
        long eventId;
        boolean allDay;
        boolean newAlert;

        NotificationInfo(String eventName, String location, String description, long startMillis,
                long endMillis, long eventId, boolean allDay, boolean newAlert) {
            this.eventName = eventName;
            this.location = location;
            this.description = description;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.eventId = eventId;
            this.newAlert = newAlert;
            this.allDay = allDay;
        }
    }

    @Override
    public void onCreate() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        mServiceLooper.quit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
