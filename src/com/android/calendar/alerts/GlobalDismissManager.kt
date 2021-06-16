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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.AsyncTask
import android.util.Pair
import java.util.HashSet
import java.util.List

/**
 * Utilities for managing notification dismissal across devices.
 */
class GlobalDismissManager : BroadcastReceiver() {
    class AlarmId(var mEventId: Long, var mStart: Long)

    @Override
    @SuppressWarnings("unchecked")
    override fun onReceive(context: Context?, intent: Intent?) {
        object : AsyncTask<Pair<Context?, Intent?>?, Void?, Void?>() {
            @Override
            protected override fun doInBackground(vararg params: Pair<Context?, Intent?>?): Void? {
                return null
            }
        }.execute(Pair<Context?, Intent?>(context, intent))
    }

    companion object {
        /**
         * Globally dismiss notifications that are backed by the same events.
         *
         * @param context application context
         * @param alarmIds Unique identifiers for events that have been dismissed by the user.
         * @return true if notification_sender_id is available
         */
        @JvmStatic fun dismissGlobally(context: Context?, alarmIds: List<AlarmId>) {
            val eventIds: HashSet<Long> = HashSet<Long>(alarmIds.size)
            for (alarmId in alarmIds) {
                eventIds.add(alarmId.mEventId)
            }
        }
    }
}