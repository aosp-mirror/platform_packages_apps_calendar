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
package com.android.calendar

import android.content.AsyncQueryHandler
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.os.Looper
import android.provider.CalendarContract.CalendarCache
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Time
import android.util.Log

import java.util.Formatter
import java.util.HashSet
import java.util.Locale

/**
 * A class containing utility methods related to Calendar apps.
 *
 * This class is expected to move into the app framework eventually.
 */
class CalendarUtils {

    companion object {
        private const val DEBUG = false
        private const val TAG = "CalendarUtils"

        /**
         * A helper method for writing a boolean value to the preferences
         * asynchronously.
         *
         * @param context A context with access to the correct preferences
         * @param key The preference to write to
         * @param value The value to write
         */
        @JvmStatic
        fun setSharedPreference(prefs: SharedPreferences, key: String?, value: Boolean) {
            val editor: SharedPreferences.Editor = prefs.edit()
            editor.putBoolean(key, value)
            editor.apply()
        }

        /** Return a properly configured SharedPreferences instance  */
        @JvmStatic
        fun getSharedPreferences(context: Context, prefsName: String?): SharedPreferences {
            return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        }

        /**
         * A helper method for writing a String value to the preferences
         * asynchronously.
         *
         * @param context A context with access to the correct preferences
         * @param key The preference to write to
         * @param value The value to write
         */
        @JvmStatic
        fun setSharedPreference(prefs: SharedPreferences, key: String?, value: String?) {
            val editor: SharedPreferences.Editor = prefs.edit()
            editor.putString(key, value)
            editor.apply()
        }
    }

    /**
     * This class contains methods specific to reading and writing time zone
     * values.
     */
    class TimeZoneUtils
    /**
     * The name of the file where the shared prefs for Calendar are stored
     * must be provided. All activities within an app should provide the
     * same preferences name or behavior may become erratic.
     *
     * @param prefsName
     */(  // The name of the shared preferences file. This name must be maintained for historical
            // reasons, as it's what PreferenceManager assigned the first time the file was created.
            private val mPrefsName: String) {
        /**
         * This is a helper class for handling the async queries and updates for the
         * time zone settings in Calendar.
         */
        private inner class AsyncTZHandler(cr: ContentResolver?) : AsyncQueryHandler(cr) {
            protected override fun onQueryComplete(token: Int, cookie: Any?, cursor: Cursor?) {
                synchronized(mTZCallbacks) {
                    if (cursor == null) {
                        mTZQueryInProgress = false
                        mFirstTZRequest = true
                        return
                    }
                    var writePrefs = false
                    // Check the values in the db
                    val keyColumn: Int = cursor.getColumnIndexOrThrow(CalendarCache.KEY)
                    val valueColumn: Int = cursor.getColumnIndexOrThrow(CalendarCache.VALUE)
                    while (cursor.moveToNext()) {
                        val key: String = cursor.getString(keyColumn)
                        val value: String = cursor.getString(valueColumn)
                        if (TextUtils.equals(key, CalendarCache.KEY_TIMEZONE_TYPE)) {
                            val useHomeTZ: Boolean = !TextUtils.equals(
                                    value, CalendarCache.TIMEZONE_TYPE_AUTO)
                            if (useHomeTZ != mUseHomeTZ) {
                                writePrefs = true
                                mUseHomeTZ = useHomeTZ
                            }
                        } else if (TextUtils.equals(
                                        key, CalendarCache.KEY_TIMEZONE_INSTANCES_PREVIOUS)) {
                            if (!TextUtils.isEmpty(value) && !TextUtils.equals(mHomeTZ, value)) {
                                writePrefs = true
                                mHomeTZ = value
                            }
                        }
                    }
                    cursor.close()
                    if (writePrefs) {
                        val prefs: SharedPreferences =
                        getSharedPreferences(cookie as Context, mPrefsName)
                        // Write the prefs
                        setSharedPreference(prefs, KEY_HOME_TZ_ENABLED, mUseHomeTZ)
                        setSharedPreference(prefs, KEY_HOME_TZ, mHomeTZ)
                    }
                    mTZQueryInProgress = false
                    for (callback in mTZCallbacks) {
                        if (callback != null) {
                            callback.run()
                        }
                    }
                    mTZCallbacks.clear()
                }
            }
        }

        /**
         * Formats a date or a time range according to the local conventions.
         *
         * This formats a date/time range using Calendar's time zone and the
         * local conventions for the region of the device.
         *
         * If the [DateUtils.FORMAT_UTC] flag is used it will pass in
         * the UTC time zone instead.
         *
         * @param context the context is required only if the time is shown
         * @param startMillis the start time in UTC milliseconds
         * @param endMillis the end time in UTC milliseconds
         * @param flags a bit mask of options See
         * [formatDateRange][DateUtils.formatDateRange]
         * @return a string containing the formatted date/time range.
         */
        fun formatDateRange(context: Context, startMillis: Long,
                            endMillis: Long, flags: Int): String {
            var date: String
            val tz: String
            tz = if (flags and DateUtils.FORMAT_UTC !== 0) {
                Time.TIMEZONE_UTC
            } else {
                getTimeZone(context, null)
            }
            synchronized(mSB) {
                mSB.setLength(0)
                date = DateUtils.formatDateRange(context, mF, startMillis, endMillis, flags,
                        tz).toString()
            }
            return date
        }

        /**
         * Writes a new home time zone to the db.
         *
         * Updates the home time zone in the db asynchronously and updates
         * the local cache. Sending a time zone of
         * [CalendarCache.TIMEZONE_TYPE_AUTO] will cause it to be set
         * to the device's time zone. null or empty tz will be ignored.
         *
         * @param context The calling activity
         * @param timeZone The time zone to set Calendar to, or
         * [CalendarCache.TIMEZONE_TYPE_AUTO]
         */
        fun setTimeZone(context: Context, timeZone: String) {
            if (TextUtils.isEmpty(timeZone)) {
                if (DEBUG) {
                    Log.d(TAG, "Empty time zone, nothing to be done.")
                }
                return
            }
            var updatePrefs = false
            synchronized(mTZCallbacks) {
                if (CalendarCache.TIMEZONE_TYPE_AUTO.equals(timeZone)) {
                    if (mUseHomeTZ) {
                        updatePrefs = true
                    }
                    mUseHomeTZ = false
                } else {
                    if (!mUseHomeTZ || !TextUtils.equals(mHomeTZ, timeZone)) {
                        updatePrefs = true
                    }
                    mUseHomeTZ = true
                    mHomeTZ = timeZone
                }
            }
            if (updatePrefs) {
                // Write the prefs
                val prefs: SharedPreferences = getSharedPreferences(context, mPrefsName)
                setSharedPreference(prefs, KEY_HOME_TZ_ENABLED, mUseHomeTZ)
                setSharedPreference(prefs, KEY_HOME_TZ, mHomeTZ)

                // Update the db
                val values = ContentValues()
                if (mHandler != null) {
                    mHandler?.cancelOperation(mToken)
                }
                mHandler = AsyncTZHandler(context.getContentResolver())

                // skip 0 so query can use it
                if (++mToken == 0) {
                    mToken = 1
                }

                // Write the use home tz setting
                values.put(CalendarCache.VALUE, if (mUseHomeTZ) CalendarCache.TIMEZONE_TYPE_HOME
                           else CalendarCache.TIMEZONE_TYPE_AUTO)
                mHandler?.startUpdate(mToken, null, CalendarCache.URI, values, "key=?",
                        TIMEZONE_TYPE_ARGS)

                // If using a home tz write it to the db
                if (mUseHomeTZ) {
                    val values2 = ContentValues()
                    values2.put(CalendarCache.VALUE, mHomeTZ)
                    mHandler?.startUpdate(mToken, null, CalendarCache.URI, values2,
                            "key=?", TIMEZONE_INSTANCES_ARGS)
                }
            }
        }

        /**
         * Gets the time zone that Calendar should be displayed in
         *
         * This is a helper method to get the appropriate time zone for Calendar. If this
         * is the first time this method has been called it will initiate an asynchronous
         * query to verify that the data in preferences is correct. The callback supplied
         * will only be called if this query returns a value other than what is stored in
         * preferences and should cause the calling activity to refresh anything that
         * depends on calling this method.
         *
         * @param context The calling activity
         * @param callback The runnable that should execute if a query returns new values
         * @return The string value representing the time zone Calendar should display
         */
        fun getTimeZone(context: Context, callback: Runnable?): String {
            synchronized(mTZCallbacks) {
                if (mFirstTZRequest) {
                    val prefs: SharedPreferences = getSharedPreferences(context, mPrefsName)
                    mUseHomeTZ = prefs.getBoolean(KEY_HOME_TZ_ENABLED, false)
                    mHomeTZ = prefs.getString(KEY_HOME_TZ, Time.getCurrentTimezone()) ?: String()

                    // Only check content resolver if we have a looper to attach to use
                    if (Looper.myLooper() != null) {
                        mTZQueryInProgress = true
                        mFirstTZRequest = false

                        // When the async query returns it should synchronize on
                        // mTZCallbacks, update mUseHomeTZ, mHomeTZ, and the
                        // preferences, set mTZQueryInProgress to false, and call all
                        // the runnables in mTZCallbacks.
                        if (mHandler == null) {
                            mHandler = AsyncTZHandler(context.getContentResolver())
                        }
                        mHandler?.startQuery(0, context, CalendarCache.URI,
                                             CALENDAR_CACHE_POJECTION, null, null, null)
                    }
                }
                if (mTZQueryInProgress && callback != null) {
                    mTZCallbacks.add(callback)
                }
            }
            return if (mUseHomeTZ) mHomeTZ else Time.getCurrentTimezone()
        }

        /**
         * Forces a query of the database to check for changes to the time zone.
         * This should be called if another app may have modified the db. If a
         * query is already in progress the callback will be added to the list
         * of callbacks to be called when it returns.
         *
         * @param context The calling activity
         * @param callback The runnable that should execute if a query returns
         * new values
         */
        fun forceDBRequery(context: Context, callback: Runnable) {
            synchronized(mTZCallbacks) {
                if (mTZQueryInProgress) {
                    mTZCallbacks.add(callback)
                    return
                }
                mFirstTZRequest = true
                getTimeZone(context, callback)
            }
        }

        companion object {
            private val TIMEZONE_TYPE_ARGS = arrayOf<String>(CalendarCache.KEY_TIMEZONE_TYPE)
            private val TIMEZONE_INSTANCES_ARGS =
            arrayOf<String>(CalendarCache.KEY_TIMEZONE_INSTANCES)
            val CALENDAR_CACHE_POJECTION = arrayOf<String>(
                    CalendarCache.KEY, CalendarCache.VALUE
            )
            private val mSB: StringBuilder = StringBuilder(50)
            private val mF: Formatter = Formatter(mSB, Locale.getDefault())

            @Volatile
            private var mFirstTZRequest = true

            @Volatile
            private var mTZQueryInProgress = false

            @Volatile
            private var mUseHomeTZ = false

            @Volatile
            private var mHomeTZ: String = Time.getCurrentTimezone()
            private val mTZCallbacks: HashSet<Runnable> = HashSet<Runnable>()
            private var mToken = 1
            private var mHandler: AsyncTZHandler? = null

            /**
             * This is the key used for writing whether or not a home time zone should
             * be used in the Calendar app to the Calendar Preferences.
             */
            const val KEY_HOME_TZ_ENABLED = "preferences_home_tz_enabled"

            /**
             * This is the key used for writing the time zone that should be used if
             * home time zones are enabled for the Calendar app.
             */
            const val KEY_HOME_TZ = "preferences_home_tz"
        }
    }
}