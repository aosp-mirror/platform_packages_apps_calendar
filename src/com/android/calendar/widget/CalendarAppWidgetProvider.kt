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

import android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY
import android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME
import android.provider.CalendarContract.EXTRA_EVENT_END_TIME
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.text.format.DateUtils
import android.text.format.Time
import android.util.Log
import android.widget.RemoteViews
import com.android.calendar.AllInOneActivity
import com.android.calendar.EventInfoActivity
import com.android.calendar.R
import com.android.calendar.Utils

/**
 * Simple widget to show next upcoming calendar event.
 */
class CalendarAppWidgetProvider : AppWidgetProvider() {
    /**
     * {@inheritDoc}
     */
    @Override
    override fun onReceive(context: Context?, intent: Intent?) {
        // Handle calendar-specific updates ourselves because they might be
        // coming in without extras, which AppWidgetProvider then blocks.
        val action: String? = intent?.getAction()
        if (LOGD) Log.d(TAG, "AppWidgetProvider got the intent: " + intent.toString())
        if (Utils.getWidgetUpdateAction(context as Context).equals(action)) {
            val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context)
            performUpdate(
                context as Context, appWidgetManager,
                appWidgetManager.getAppWidgetIds(getComponentName(context)),
                null /* no eventIds */
            )
        } else if (action != null && (action.equals(Intent.ACTION_PROVIDER_CHANGED) ||
            action.equals(Intent.ACTION_TIME_CHANGED) ||
            action.equals(Intent.ACTION_TIMEZONE_CHANGED) ||
            action.equals(Intent.ACTION_DATE_CHANGED) ||
            action.equals(Utils.getWidgetScheduledUpdateAction(context as Context)))
        ) {
            val service = Intent(context, CalendarAppWidgetService::class.java)
            context.startService(service)
        } else {
            super.onReceive(context, intent)
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    override fun onDisabled(context: Context) {
        // Unsubscribe from all AlarmManager updates
        val am: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingUpdate: PendingIntent = getUpdateIntent(context)
        am.cancel(pendingUpdate)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        performUpdate(context, appWidgetManager,
            appWidgetIds, null /* no eventIds */)
    }

    /**
     * Process and push out an update for the given appWidgetIds. This call
     * actually fires an intent to start [CalendarAppWidgetService] as a
     * background service which handles the actual update, to prevent ANR'ing
     * during database queries.
     *
     * @param context Context to use when starting [CalendarAppWidgetService].
     * @param appWidgetIds List of specific appWidgetIds to update, or null for
     * all.
     * @param changedEventIds Specific events known to be changed. If present,
     * we use it to decide if an update is necessary.
     */
    private fun performUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        changedEventIds: LongArray?
    ) {
        // Launch over to service so it can perform update
        for (appWidgetId in appWidgetIds) {
            if (LOGD) Log.d(TAG, "Building widget update...")
            val updateIntent = Intent(context, CalendarAppWidgetService::class.java)
            updateIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            if (changedEventIds != null) {
                updateIntent.putExtra(EXTRA_EVENT_IDS, changedEventIds)
            }
            updateIntent.setData(Uri.parse(updateIntent.toUri(Intent.URI_INTENT_SCHEME)))
            val views = RemoteViews(context.getPackageName(), R.layout.appwidget)
            // Calendar header
            val time = Time(Utils.getTimeZone(context, null))
            time.setToNow()
            val millis: Long = time.toMillis(true)
            val dayOfWeek: String = DateUtils.getDayOfWeekString(
                time.weekDay + 1,
                DateUtils.LENGTH_MEDIUM
            )
            val date: String? = Utils.formatDateRange(
                context, millis, millis,
                DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_DATE
                or DateUtils.FORMAT_NO_YEAR
            )
            views.setTextViewText(R.id.day_of_week, dayOfWeek)
            views.setTextViewText(R.id.date, date)
            // Attach to list of events
            views.setRemoteAdapter(appWidgetId, R.id.events_list, updateIntent)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.events_list)

            // Launch calendar app when the user taps on the header
            val launchCalendarIntent = Intent(Intent.ACTION_VIEW)
            launchCalendarIntent.setClass(context, AllInOneActivity::class.java)
            launchCalendarIntent
                .setData(Uri.parse("content://com.android.calendar/time/$millis"))
            val launchCalendarPendingIntent: PendingIntent = PendingIntent.getActivity(
                context, 0 /* no requestCode */, launchCalendarIntent, 0 /* no flags */
            )
            views.setOnClickPendingIntent(R.id.header, launchCalendarPendingIntent)

            // Each list item will call setOnClickExtra() to let the list know
            // which item
            // is selected by a user.
            val updateEventIntent: PendingIntent = getLaunchPendingIntentTemplate(context)
            views.setPendingIntentTemplate(R.id.events_list, updateEventIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        const val TAG = "CalendarAppWidgetProvider"
        const val LOGD = false

        // TODO Move these to Calendar.java
        const val EXTRA_EVENT_IDS = "com.android.calendar.EXTRA_EVENT_IDS"

        /**
         * Build [ComponentName] describing this specific
         * [AppWidgetProvider]
         */
        @JvmStatic fun getComponentName(context: Context?): ComponentName {
            return ComponentName(context as Context, CalendarAppWidgetProvider::class.java)
        }

        /**
         * Build the [PendingIntent] used to trigger an update of all calendar
         * widgets. Uses [Utils.getWidgetScheduledUpdateAction] to
         * directly target all widgets instead of using
         * [AppWidgetManager.EXTRA_APPWIDGET_IDS].
         *
         * @param context Context to use when building broadcast.
         */
        @JvmStatic fun getUpdateIntent(context: Context?): PendingIntent {
            val intent = Intent(Utils.getWidgetScheduledUpdateAction(context as Context))
            intent.setDataAndType(CalendarContract.CONTENT_URI, Utils.APPWIDGET_DATA_TYPE)
            return PendingIntent.getBroadcast(
                context, 0 /* no requestCode */, intent,
                0 /* no flags */
            )
        }

        /**
         * Build a [PendingIntent] to launch the Calendar app. This should be used
         * in combination with [RemoteViews.setPendingIntentTemplate].
         */
        @JvmStatic fun getLaunchPendingIntentTemplate(context: Context?): PendingIntent {
            val launchIntent = Intent()
            launchIntent.setAction(Intent.ACTION_VIEW)
            launchIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_TASK_ON_HOME
            )
            launchIntent.setClass(context as Context, AllInOneActivity::class.java)
            return PendingIntent.getActivity(
                context, 0 /* no requestCode */, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        /**
         * Build an [Intent] available as FillInIntent to launch the Calendar app.
         * This should be used in combination with
         * [RemoteViews.setOnClickFillInIntent].
         * If the go to time is 0, then calendar will be launched without a starting time.
         *
         * @param goToTime time that calendar should take the user to, or 0 to
         * indicate no specific start time.
         */
        @JvmStatic fun getLaunchFillInIntent(
            context: Context?,
            id: Long,
            start: Long,
            end: Long,
            allDay: Boolean
        ): Intent {
            val fillInIntent = Intent()
            var dataString = "content://com.android.calendar/events"
            if (id != 0L) {
                fillInIntent.putExtra(Utils.INTENT_KEY_DETAIL_VIEW, true)
                fillInIntent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_TASK_ON_HOME
                )
                dataString += "/$id"
                // If we have an event id - start the event info activity
                fillInIntent.setClass(context as Context, EventInfoActivity::class.java)
            } else {
                // If we do not have an event id - start AllInOne
                fillInIntent.setClass(context as Context, AllInOneActivity::class.java)
            }
            val data: Uri = Uri.parse(dataString)
            fillInIntent.setData(data)
            fillInIntent.putExtra(EXTRA_EVENT_BEGIN_TIME, start)
            fillInIntent.putExtra(EXTRA_EVENT_END_TIME, end)
            fillInIntent.putExtra(EXTRA_EVENT_ALL_DAY, allDay)
            return fillInIntent
        }
    }
}