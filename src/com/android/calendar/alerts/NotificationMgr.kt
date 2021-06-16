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

import com.android.calendar.alerts.AlertService.NotificationWrapper

abstract class NotificationMgr {
    abstract fun notify(id: Int, notification: NotificationWrapper?)
    abstract fun cancel(id: Int)

    /**
     * Don't actually use the notification framework's cancelAll since the SyncAdapter
     * might post notifications and we don't want to affect those.
     */
    fun cancelAll() {
        cancelAllBetween(0, AlertService.MAX_NOTIFICATIONS)
    }

    /**
     * Cancels IDs between the specified bounds, inclusively.
     */
    fun cancelAllBetween(from: Int, to: Int) {
        for (i in from..to) {
            cancel(i)
        }
    }
}