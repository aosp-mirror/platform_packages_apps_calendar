/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.calendar.alerts;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.CalendarContract.CalendarAlerts;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.util.Log;
import android.util.Pair;

import com.android.calendar.R;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utilities for managing notification dismissal across devices.
 */
public class GlobalDismissManager extends BroadcastReceiver {
    public static class AlarmId {
        public long mEventId;
        public long mStart;

        public AlarmId(long id, long start) {
            mEventId = id;
            mStart = start;
        }
    }

    /**
     * Globally dismiss notifications that are backed by the same events.
     *
     * @param context application context
     * @param alarmIds Unique identifiers for events that have been dismissed by the user.
     * @return true if notification_sender_id is available
     */
    public static void dismissGlobally(Context context, List<AlarmId> alarmIds) {
        Set<Long> eventIds = new HashSet<Long>(alarmIds.size());
        for (AlarmId alarmId: alarmIds) {
            eventIds.add(alarmId.mEventId);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onReceive(Context context, Intent intent) {
        new AsyncTask<Pair<Context, Intent>, Void, Void>() {
            @Override
            protected Void doInBackground(Pair<Context, Intent>... params) {
                return null;
            }
        }.execute(new Pair<Context, Intent>(context, intent));
    }
}
