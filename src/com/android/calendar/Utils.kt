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

import android.app.Activity
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Time
import android.util.Log
import com.android.calendar.CalendarController.ViewType
import com.android.calendar.CalendarUtils.TimeZoneUtils
import java.util.ArrayList
import java.util.Arrays
import java.util.Calendar
import java.util.Formatter
import java.util.HashMap
import java.util.LinkedHashSet
import java.util.LinkedList
import java.util.List
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

object Utils {
    private const val DEBUG = false
    private const val TAG = "CalUtils"

    // Set to 0 until we have UI to perform undo
    const val UNDO_DELAY: Long = 0

    // For recurring events which instances of the series are being modified
    const val MODIFY_UNINITIALIZED = 0
    const val MODIFY_SELECTED = 1
    const val MODIFY_ALL_FOLLOWING = 2
    const val MODIFY_ALL = 3

    // When the edit event view finishes it passes back the appropriate exit
    // code.
    const val DONE_REVERT = 1 shl 0
    const val DONE_SAVE = 1 shl 1
    const val DONE_DELETE = 1 shl 2

    // And should re run with DONE_EXIT if it should also leave the view, just
    // exiting is identical to reverting
    const val DONE_EXIT = 1 shl 0
    const val OPEN_EMAIL_MARKER = " <"
    const val CLOSE_EMAIL_MARKER = ">"
    const val INTENT_KEY_DETAIL_VIEW = "DETAIL_VIEW"
    const val INTENT_KEY_VIEW_TYPE = "VIEW"
    const val INTENT_VALUE_VIEW_TYPE_DAY = "DAY"
    const val INTENT_KEY_HOME = "KEY_HOME"
    val MONDAY_BEFORE_JULIAN_EPOCH: Int = Time.EPOCH_JULIAN_DAY - 3
    const val DECLINED_EVENT_ALPHA = 0x66
    const val DECLINED_EVENT_TEXT_ALPHA = 0xC0
    private const val SATURATION_ADJUST = 1.3f
    private const val INTENSITY_ADJUST = 0.8f

    // Defines used by the DNA generation code
    const val DAY_IN_MINUTES = 60 * 24
    const val WEEK_IN_MINUTES = DAY_IN_MINUTES * 7

    // The work day is being counted as 6am to 8pm
    var WORK_DAY_MINUTES = 14 * 60
    var WORK_DAY_START_MINUTES = 6 * 60
    var WORK_DAY_END_MINUTES = 20 * 60
    var WORK_DAY_END_LENGTH = 24 * 60 - WORK_DAY_END_MINUTES
    var CONFLICT_COLOR = -0x1000000
    var mMinutesLoaded = false
    const val YEAR_MIN = 1970
    const val YEAR_MAX = 2036

    // The name of the shared preferences file. This name must be maintained for
    // historical
    // reasons, as it's what PreferenceManager assigned the first time the file
    // was created.
    const val SHARED_PREFS_NAME = "com.android.calendar_preferences"
    const val KEY_QUICK_RESPONSES = "preferences_quick_responses"
    const val KEY_ALERTS_VIBRATE_WHEN = "preferences_alerts_vibrateWhen"
    const val APPWIDGET_DATA_TYPE = "vnd.android.data/update"
    const val MACHINE_GENERATED_ADDRESS = "calendar.google.com"
    private val mTZUtils: TimeZoneUtils? = TimeZoneUtils(SHARED_PREFS_NAME)
    @JvmField var allowWeekForDetailView = false
    internal var tardis: Long = 0
        private set
    private var sVersion: String? = null
    private val mWildcardPattern: Pattern = Pattern.compile("^.*$")

    /**
     * A coordinate must be of the following form for Google Maps to correctly use it:
     * Latitude, Longitude
     *
     * This may be in decimal form:
     * Latitude: {-90 to 90}
     * Longitude: {-180 to 180}
     *
     * Or, in degrees, minutes, and seconds:
     * Latitude: {-90 to 90}° {0 to 59}' {0 to 59}"
     * Latitude: {-180 to 180}° {0 to 59}' {0 to 59}"
     * + or - degrees may also be represented with N or n, S or s for latitude, and with
     * E or e, W or w for longitude, where the direction may either precede or follow the value.
     *
     * Some examples of coordinates that will be accepted by the regex:
     * 37.422081°, -122.084576°
     * 37.422081,-122.084576
     * +37°25'19.49", -122°5'4.47"
     * 37°25'19.49"N, 122°5'4.47"W
     * N 37° 25' 19.49",  W 122° 5' 4.47"
     */
    private const val COORD_DEGREES_LATITUDE = ("([-+NnSs]" + "(\\s)*)?" +
        "[1-9]?[0-9](\u00B0)" + "(\\s)*" +
        "([1-5]?[0-9]\')?" + "(\\s)*" +
        "([1-5]?[0-9]" + "(\\.[0-9]+)?\")?" +
        "((\\s)*" + "[NnSs])?")
    private const val COORD_DEGREES_LONGITUDE = ("([-+EeWw]" + "(\\s)*)?" +
        "(1)?[0-9]?[0-9](\u00B0)" + "(\\s)*" +
        "([1-5]?[0-9]\')?" + "(\\s)*" +
        "([1-5]?[0-9]" + "(\\.[0-9]+)?\")?" +
        "((\\s)*" + "[EeWw])?")
    private const val COORD_DEGREES_PATTERN = (COORD_DEGREES_LATITUDE + "(\\s)*" + "," + "(\\s)*" +
        COORD_DEGREES_LONGITUDE)
    private const val COORD_DECIMAL_LATITUDE = ("[+-]?" +
        "[1-9]?[0-9]" + "(\\.[0-9]+)" +
        "(\u00B0)?")
    private const val COORD_DECIMAL_LONGITUDE = ("[+-]?" +
        "(1)?[0-9]?[0-9]" + "(\\.[0-9]+)" +
        "(\u00B0)?")
    private const val COORD_DECIMAL_PATTERN = (COORD_DECIMAL_LATITUDE + "(\\s)*" + "," + "(\\s)*" +
        COORD_DECIMAL_LONGITUDE)
    private val COORD_PATTERN: Pattern =
        Pattern.compile(COORD_DEGREES_PATTERN + "|" + COORD_DECIMAL_PATTERN)
    private const val NANP_ALLOWED_SYMBOLS = "()+-*#."
    private const val NANP_MIN_DIGITS = 7
    private const val NANP_MAX_DIGITS = 11

    /**
     * Returns whether the SDK is the KeyLimePie release or later.
     */
    @JvmStatic fun isKeyLimePieOrLater(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
    }

    /**
     * Returns whether the SDK is the Jellybean release or later.
     */
    @JvmStatic fun isJellybeanOrLater(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
    }

    @JvmStatic fun getViewTypeFromIntentAndSharedPref(activity: Activity): Int {
        val intent: Intent? = activity.getIntent()
        val extras: Bundle? = intent?.getExtras()
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(activity)
        if (TextUtils.equals(intent?.getAction(), Intent.ACTION_EDIT)) {
            return ViewType.EDIT
        }
        if (extras != null) {
            if (extras.getBoolean(INTENT_KEY_DETAIL_VIEW, false)) {
                // This is the "detail" view which is either agenda or day view
                return prefs?.getInt(
                    GeneralPreferences.KEY_DETAILED_VIEW,
                    GeneralPreferences.DEFAULT_DETAILED_VIEW
                ) as Int
            } else if (INTENT_VALUE_VIEW_TYPE_DAY.equals(extras.getString(INTENT_KEY_VIEW_TYPE))) {
                // Not sure who uses this. This logic came from LaunchActivity
                return ViewType.DAY
            }
        }

        // Default to the last view
        return prefs?.getInt(
            GeneralPreferences.KEY_START_VIEW, GeneralPreferences.DEFAULT_START_VIEW
        ) as Int
    }

    /**
     * Gets the intent action for telling the widget to update.
     */
    @JvmStatic fun getWidgetUpdateAction(context: Context): String {
        return context.getPackageName().toString() + ".APPWIDGET_UPDATE"
    }

    /**
     * Gets the intent action for telling the widget to update.
     */
    @JvmStatic fun getWidgetScheduledUpdateAction(context: Context): String {
        return context.getPackageName().toString() + ".APPWIDGET_SCHEDULED_UPDATE"
    }

    /**
     * Writes a new home time zone to the db. Updates the home time zone in the
     * db asynchronously and updates the local cache. Sending a time zone of
     * **tbd** will cause it to be set to the device's time zone. null or empty
     * tz will be ignored.
     *
     * @param context The calling activity
     * @param timeZone The time zone to set Calendar to, or **tbd**
     */
    @JvmStatic fun setTimeZone(context: Context?, timeZone: String?) {
        mTZUtils?.setTimeZone(context as Context, timeZone as String)
    }

    /**
     * Gets the time zone that Calendar should be displayed in This is a helper
     * method to get the appropriate time zone for Calendar. If this is the
     * first time this method has been called it will initiate an asynchronous
     * query to verify that the data in preferences is correct. The callback
     * supplied will only be called if this query returns a value other than
     * what is stored in preferences and should cause the calling activity to
     * refresh anything that depends on calling this method.
     *
     * @param context The calling activity
     * @param callback The runnable that should execute if a query returns new
     * values
     * @return The string value representing the time zone Calendar should
     * display
     */
    @JvmStatic fun getTimeZone(context: Context?, callback: Runnable?): String? {
        return mTZUtils?.getTimeZone(context as Context, callback)
    }

    /**
     * Formats a date or a time range according to the local conventions.
     *
     * @param context the context is required only if the time is shown
     * @param startMillis the start time in UTC milliseconds
     * @param endMillis the end time in UTC milliseconds
     * @param flags a bit mask of options See [formatDateRange][DateUtils.formatDateRange]
     * @return a string containing the formatted date/time range.
     */
    @JvmStatic fun formatDateRange(
        context: Context?,
        startMillis: Long,
        endMillis: Long,
        flags: Int
    ): String? {
        return mTZUtils?.formatDateRange(context as Context, startMillis, endMillis, flags)
    }

    @JvmStatic fun getDefaultVibrate(context: Context, prefs: SharedPreferences?): Boolean {
        val vibrate: Boolean
        if (prefs?.contains(KEY_ALERTS_VIBRATE_WHEN) == true) {
            // Migrate setting to new 4.2 behavior
            //
            // silent and never -> off
            // always -> on
            val vibrateWhen: String? = prefs.getString(KEY_ALERTS_VIBRATE_WHEN, null)
            vibrate = vibrateWhen != null && vibrateWhen.equals(
                context
                    .getString(R.string.prefDefault_alerts_vibrate_true)
            )
            prefs.edit().remove(KEY_ALERTS_VIBRATE_WHEN).commit()
            Log.d(
                TAG, "Migrating KEY_ALERTS_VIBRATE_WHEN(" +
                    vibrateWhen + ") to KEY_ALERTS_VIBRATE = " + vibrate
            )
        } else {
            vibrate = prefs?.getBoolean(
                GeneralPreferences.KEY_ALERTS_VIBRATE,
                false
            ) as Boolean
        }
        return vibrate
    }

    @JvmStatic fun getSharedPreference(
        context: Context?,
        key: String?,
        defaultValue: Array<String>?
    ): Array<String>? {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        val ss = prefs?.getStringSet(key, null)
        if (ss != null) {
            val strings = arrayOfNulls<String>(ss.size)
            return ss.toTypedArray()
        }
        return defaultValue
    }

    @JvmStatic fun getSharedPreference(
        context: Context?,
        key: String?,
        defaultValue: String?
    ): String? {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        return prefs?.getString(key, defaultValue)
    }

    @JvmStatic fun getSharedPreference(context: Context?, key: String?, defaultValue: Int): Int {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        return prefs?.getInt(key, defaultValue) as Int
    }

    @JvmStatic fun getSharedPreference(
        context: Context?,
        key: String?,
        defaultValue: Boolean
    ): Boolean {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        return prefs?.getBoolean(key, defaultValue) as Boolean
    }

    /**
     * Asynchronously sets the preference with the given key to the given value
     *
     * @param context the context to use to get preferences from
     * @param key the key of the preference to set
     * @param value the value to set
     */
    @JvmStatic fun setSharedPreference(context: Context?, key: String?, value: String?) {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        prefs?.edit()?.putString(key, value)?.apply()
    }

    @JvmStatic fun setSharedPreference(context: Context?, key: String?, values: Array<String?>) {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        val set: LinkedHashSet<String?> = LinkedHashSet<String?>()
        for (value in values) {
            set.add(value)
        }
        prefs?.edit()?.putStringSet(key, set)?.apply()
    }

    internal fun tardis() {
        tardis = System.currentTimeMillis()
    }

    @JvmStatic fun setSharedPreference(context: Context?, key: String?, value: Boolean) {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        val editor: SharedPreferences.Editor? = prefs?.edit()
        editor?.putBoolean(key, value)
        editor?.apply()
    }

    @JvmStatic fun setSharedPreference(context: Context?, key: String?, value: Int) {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        val editor: SharedPreferences.Editor? = prefs?.edit()
        editor?.putInt(key, value)
        editor?.apply()
    }

    @JvmStatic fun removeSharedPreference(context: Context?, key: String?) {
        val prefs: SharedPreferences? = context?.getSharedPreferences(
            GeneralPreferences.SHARED_PREFS_NAME, Context.MODE_PRIVATE
        )
        prefs?.edit()?.remove(key)?.apply()
    }

    /**
     * Save default agenda/day/week/month view for next time
     *
     * @param context
     * @param viewId [CalendarController.ViewType]
     */
    @JvmStatic fun setDefaultView(context: Context?, viewId: Int) {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        val editor: SharedPreferences.Editor? = prefs?.edit()
        var validDetailView = false
        validDetailView =
            if (allowWeekForDetailView && viewId == CalendarController.ViewType.WEEK) {
                true
            } else {
                (viewId == CalendarController.ViewType.AGENDA ||
                    viewId == CalendarController.ViewType.DAY)
            }
        if (validDetailView) {
            // Record the detail start view
            editor?.putInt(GeneralPreferences.KEY_DETAILED_VIEW, viewId)
        }

        // Record the (new) start view
        editor?.putInt(GeneralPreferences.KEY_START_VIEW, viewId)
        editor?.apply()
    }

    @JvmStatic fun matrixCursorFromCursor(cursor: Cursor?): MatrixCursor? {
        if (cursor == null) {
            return null
        }
        var columnNames: Array<String?> = cursor.getColumnNames()
        if (columnNames == null) {
            columnNames = arrayOf()
        }
        val newCursor = MatrixCursor(columnNames)
        val numColumns: Int = cursor.getColumnCount()
        val data = arrayOfNulls<String>(numColumns)
        cursor.moveToPosition(-1)
        while (cursor.moveToNext()) {
            for (i in 0 until numColumns) {
                data[i] = cursor.getString(i)
            }
            newCursor.addRow(data)
        }
        return newCursor
    }

    /**
     * Compares two cursors to see if they contain the same data.
     *
     * @return Returns true of the cursors contain the same data and are not
     * null, false otherwise
     */
    @JvmStatic fun compareCursors(c1: Cursor?, c2: Cursor?): Boolean {
        if (c1 == null || c2 == null) {
            return false
        }
        val numColumns: Int = c1.getColumnCount()
        if (numColumns != c2.getColumnCount()) {
            return false
        }
        if (c1.getCount() !== c2.getCount()) {
            return false
        }
        c1.moveToPosition(-1)
        c2.moveToPosition(-1)
        while (c1.moveToNext() && c2.moveToNext()) {
            for (i in 0 until numColumns) {
                if (!TextUtils.equals(c1.getString(i), c2.getString(i))) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * If the given intent specifies a time (in milliseconds since the epoch),
     * then that time is returned. Otherwise, the current time is returned.
     */
    @JvmStatic fun timeFromIntentInMillis(intent: Intent?): Long? {
        // If the time was specified, then use that. Otherwise, use the current
        // time.
        val data: Uri? = intent?.getData()
        var millis: Long? = intent?.getLongExtra(EXTRA_EVENT_BEGIN_TIME, -1)?.toLong()
        if (millis == -1L && data != null && data.isHierarchical()) {
            val path: List<String> = data.getPathSegments() as List<String>
            if (path.size == 2 && path[0].equals("time")) {
                try {
                    millis = (data.getLastPathSegment()?.toLong())
                } catch (e: NumberFormatException) {
                    Log.i(
                        "Calendar", "timeFromIntentInMillis: Data existed but no valid time " +
                            "found. Using current time."
                    )
                }
            }
        }
        if ((millis ?: 0L) <= 0) {
            millis = System.currentTimeMillis()
        }
        return millis
    }

    /**
     * Formats the given Time object so that it gives the month and year (for
     * example, "September 2007").
     *
     * @param time the time to format
     * @return the string containing the weekday and the date
     */
    @JvmStatic fun formatMonthYear(context: Context?, time: Time): String? {
        val flags: Int = (DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY
            or DateUtils.FORMAT_SHOW_YEAR)
        val millis: Long = time.toMillis(true)
        return formatDateRange(context, millis, millis, flags)
    }

    /**
     * Returns a list joined together by the provided delimiter, for example,
     * ["a", "b", "c"] could be joined into "a,b,c"
     *
     * @param things the things to join together
     * @param delim the delimiter to use
     * @return a string contained the things joined together
     */
    @JvmStatic fun join(things: List<*>, delim: String?): String {
        val builder = StringBuilder()
        var first = true
        for (thing in things) {
            if (first) {
                first = false
            } else {
                builder.append(delim)
            }
            builder.append(thing.toString())
        }
        return builder.toString()
    }

    /**
     * Returns the week since [Time.EPOCH_JULIAN_DAY] (Jan 1, 1970)
     * adjusted for first day of week.
     *
     * This takes a julian day and the week start day and calculates which
     * week since [Time.EPOCH_JULIAN_DAY] that day occurs in, starting
     * at 0. *Do not* use this to compute the ISO week number for the year.
     *
     * @param julianDay The julian day to calculate the week number for
     * @param firstDayOfWeek Which week day is the first day of the week,
     * see [Time.SUNDAY]
     * @return Weeks since the epoch
     */
    @JvmStatic fun getWeeksSinceEpochFromJulianDay(julianDay: Int, firstDayOfWeek: Int): Int {
        var diff: Int = Time.THURSDAY - firstDayOfWeek
        if (diff < 0) {
            diff += 7
        }
        val refDay: Int = Time.EPOCH_JULIAN_DAY - diff
        return (julianDay - refDay) / 7
    }

    /**
     * Takes a number of weeks since the epoch and calculates the Julian day of
     * the Monday for that week.
     *
     * This assumes that the week containing the [Time.EPOCH_JULIAN_DAY]
     * is considered week 0. It returns the Julian day for the Monday
     * `week` weeks after the Monday of the week containing the epoch.
     *
     * @param week Number of weeks since the epoch
     * @return The julian day for the Monday of the given week since the epoch
     */
    @JvmStatic fun getJulianMondayFromWeeksSinceEpoch(week: Int): Int {
        return MONDAY_BEFORE_JULIAN_EPOCH + week * 7
    }

    /**
     * Get first day of week as android.text.format.Time constant.
     *
     * @return the first day of week in android.text.format.Time
     */
    @JvmStatic fun getFirstDayOfWeek(context: Context?): Int {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        val pref: String? = prefs?.getString(
            GeneralPreferences.KEY_WEEK_START_DAY, GeneralPreferences.WEEK_START_DEFAULT
        )
        val startDay: Int
        startDay = if (GeneralPreferences.WEEK_START_DEFAULT.equals(pref)) {
            Calendar.getInstance().getFirstDayOfWeek()
        } else {
            Integer.parseInt(pref)
        }
        return if (startDay == Calendar.SATURDAY) {
            Time.SATURDAY
        } else if (startDay == Calendar.MONDAY) {
            Time.MONDAY
        } else {
            Time.SUNDAY
        }
    }

    /**
     * Get first day of week as java.util.Calendar constant.
     *
     * @return the first day of week as a java.util.Calendar constant
     */
    @JvmStatic fun getFirstDayOfWeekAsCalendar(context: Context?): Int {
        return convertDayOfWeekFromTimeToCalendar(getFirstDayOfWeek(context))
    }

    /**
     * Converts the day of the week from android.text.format.Time to java.util.Calendar
     */
    @JvmStatic fun convertDayOfWeekFromTimeToCalendar(timeDayOfWeek: Int): Int {
        return when (timeDayOfWeek) {
            Time.MONDAY -> Calendar.MONDAY
            Time.TUESDAY -> Calendar.TUESDAY
            Time.WEDNESDAY -> Calendar.WEDNESDAY
            Time.THURSDAY -> Calendar.THURSDAY
            Time.FRIDAY -> Calendar.FRIDAY
            Time.SATURDAY -> Calendar.SATURDAY
            Time.SUNDAY -> Calendar.SUNDAY
            else -> throw IllegalArgumentException(
                "Argument must be between Time.SUNDAY and " +
                    "Time.SATURDAY"
            )
        }
    }

    /**
     * @return true when week number should be shown.
     */
    @JvmStatic fun getShowWeekNumber(context: Context?): Boolean {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        return prefs?.getBoolean(
            GeneralPreferences.KEY_SHOW_WEEK_NUM, GeneralPreferences.DEFAULT_SHOW_WEEK_NUM
        ) as Boolean
    }

    /**
     * @return true when declined events should be hidden.
     */
    @JvmStatic fun getHideDeclinedEvents(context: Context?): Boolean {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        return prefs?.getBoolean(GeneralPreferences.KEY_HIDE_DECLINED, false) as Boolean
    }

    @JvmStatic fun getDaysPerWeek(context: Context?): Int {
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(context)
        return prefs?.getInt(GeneralPreferences.KEY_DAYS_PER_WEEK, 7) as Int
    }

    /**
     * Determine whether the column position is Saturday or not.
     *
     * @param column the column position
     * @param firstDayOfWeek the first day of week in android.text.format.Time
     * @return true if the column is Saturday position
     */
    @JvmStatic fun isSaturday(column: Int, firstDayOfWeek: Int): Boolean {
        return (firstDayOfWeek == Time.SUNDAY && column == 6 ||
            firstDayOfWeek == Time.MONDAY && column == 5 ||
            firstDayOfWeek == Time.SATURDAY && column == 0)
    }

    /**
     * Determine whether the column position is Sunday or not.
     *
     * @param column the column position
     * @param firstDayOfWeek the first day of week in android.text.format.Time
     * @return true if the column is Sunday position
     */
    @JvmStatic fun isSunday(column: Int, firstDayOfWeek: Int): Boolean {
        return (firstDayOfWeek == Time.SUNDAY && column == 0 ||
            firstDayOfWeek == Time.MONDAY && column == 6 ||
            firstDayOfWeek == Time.SATURDAY && column == 1)
    }

    /**
     * Convert given UTC time into current local time. This assumes it is for an
     * allday event and will adjust the time to be on a midnight boundary.
     *
     * @param recycle Time object to recycle, otherwise null.
     * @param utcTime Time to convert, in UTC.
     * @param tz The time zone to convert this time to.
     */
    @JvmStatic fun convertAlldayUtcToLocal(recycle: Time?, utcTime: Long, tz: String): Long {
        var recycle: Time? = recycle
        if (recycle == null) {
            recycle = Time()
        }
        recycle.timezone = Time.TIMEZONE_UTC
        recycle.set(utcTime)
        recycle.timezone = tz
        return recycle.normalize(true)
    }

    @JvmStatic fun convertAlldayLocalToUTC(recycle: Time?, localTime: Long, tz: String): Long {
        var recycle: Time? = recycle
        if (recycle == null) {
            recycle = Time()
        }
        recycle.timezone = tz
        recycle.set(localTime)
        recycle.timezone = Time.TIMEZONE_UTC
        return recycle.normalize(true)
    }

    /**
     * Finds and returns the next midnight after "theTime" in milliseconds UTC
     *
     * @param recycle - Time object to recycle, otherwise null.
     * @param theTime - Time used for calculations (in UTC)
     * @param tz The time zone to convert this time to.
     */
    @JvmStatic fun getNextMidnight(recycle: Time?, theTime: Long, tz: String): Long {
        var recycle: Time? = recycle
        if (recycle == null) {
            recycle = Time()
        }
        recycle.timezone = tz
        recycle.set(theTime)
        recycle.monthDay++
        recycle.hour = 0
        recycle.minute = 0
        recycle.second = 0
        return recycle.normalize(true)
    }

    @JvmStatic fun setAllowWeekForDetailView(allowWeekView: Boolean) {
        this.allowWeekForDetailView = allowWeekView
    }

    @JvmStatic fun getAllowWeekForDetailView(): Boolean {
        return this.allowWeekForDetailView
    }

    @JvmStatic fun getConfigBool(c: Context, key: Int): Boolean {
        return c.getResources().getBoolean(key)
    }

    /**
     * For devices with Jellybean or later, darkens the given color to ensure that white text is
     * clearly visible on top of it.  For devices prior to Jellybean, does nothing, as the
     * sync adapter handles the color change.
     *
     * @param color
     */
    @JvmStatic fun getDisplayColorFromColor(color: Int): Int {
        if (!isJellybeanOrLater()) {
            return color
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = Math.min(hsv[1] * SATURATION_ADJUST, 1.0f)
        hsv[2] = hsv[2] * INTENSITY_ADJUST
        return Color.HSVToColor(hsv)
    }

    // This takes a color and computes what it would look like blended with
    // white. The result is the color that should be used for declined events.
    @JvmStatic fun getDeclinedColorFromColor(color: Int): Int {
        val bg = -0x1
        val a = DECLINED_EVENT_ALPHA
        val r = (color and 0x00ff0000) * a + (bg and 0x00ff0000) * (0xff - a) and -0x1000000
        val g = (color and 0x0000ff00) * a + (bg and 0x0000ff00) * (0xff - a) and 0x00ff0000
        val b = (color and 0x000000ff) * a + (bg and 0x000000ff) * (0xff - a) and 0x0000ff00
        return -0x1000000 or (r or g or b shr 8)
    }

    @JvmStatic fun trySyncAndDisableUpgradeReceiver(context: Context?) {
        val pm: PackageManager? = context?.getPackageManager()
        val upgradeComponent = ComponentName(context as Context, UpgradeReceiver::class.java)
        if (pm?.getComponentEnabledSetting(upgradeComponent) ===
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ) {
            // The upgrade receiver has been disabled, which means this code has been run before,
            // so no need to sync.
            return
        }
        val extras = Bundle()
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        ContentResolver.requestSync(
            null /* no account */,
            Calendars.CONTENT_URI.getAuthority(),
            extras
        )

        // Now unregister the receiver so that we won't continue to sync every time.
        pm?.setComponentEnabledSetting(
            upgradeComponent,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
        )
    }

    /**
     * Converts a list of events to a list of segments to draw. Assumes list is
     * ordered by start time of the events. The function processes events for a
     * range of days from firstJulianDay to firstJulianDay + dayXs.length - 1.
     * The algorithm goes over all the events and creates a set of segments
     * ordered by start time. This list of segments is then converted into a
     * HashMap of strands which contain the draw points and are organized by
     * color. The strands can then be drawn by setting the paint color to each
     * strand's color and calling drawLines on its set of points. The points are
     * set up using the following parameters.
     *
     *  * Events between midnight and WORK_DAY_START_MINUTES are compressed
     * into the first 1/8th of the space between top and bottom.
     *  * Events between WORK_DAY_END_MINUTES and the following midnight are
     * compressed into the last 1/8th of the space between top and bottom
     *  * Events between WORK_DAY_START_MINUTES and WORK_DAY_END_MINUTES use
     * the remaining 3/4ths of the space
     *  * All segments drawn will maintain at least minPixels height, except
     * for conflicts in the first or last 1/8th, which may be smaller
     *
     *
     * @param firstJulianDay The julian day of the first day of events
     * @param events A list of events sorted by start time
     * @param top The lowest y value the dna should be drawn at
     * @param bottom The highest y value the dna should be drawn at
     * @param dayXs An array of x values to draw the dna at, one for each day
     * @param conflictColor the color to use for conflicts
     * @return
     */
    @JvmStatic fun createDNAStrands(
        firstJulianDay: Int,
        events: ArrayList<Event?>?,
        top: Int,
        bottom: Int,
        minPixels: Int,
        dayXs: IntArray?,
        context: Context?
    ): HashMap<Int, DNAStrand>? {
        if (!mMinutesLoaded) {
            if (context == null) {
                Log.wtf(TAG, "No context and haven't loaded parameters yet! Can't create DNA.")
            }
            val res: Resources? = context?.getResources()
            CONFLICT_COLOR = res?.getColor(R.color.month_dna_conflict_time_color) as Int
            WORK_DAY_START_MINUTES = res.getInteger(R.integer.work_start_minutes) as Int
            WORK_DAY_END_MINUTES = res.getInteger(R.integer.work_end_minutes) as Int
            WORK_DAY_END_LENGTH = DAY_IN_MINUTES - WORK_DAY_END_MINUTES
            WORK_DAY_MINUTES = WORK_DAY_END_MINUTES - WORK_DAY_START_MINUTES
            mMinutesLoaded = true
        }
        if (events == null || events.isEmpty() || dayXs == null || dayXs.size < 1 ||
            bottom - top < 8 || minPixels < 0) {
            Log.e(
                TAG,
                "Bad values for createDNAStrands! events:" + events + " dayXs:" +
                    Arrays.toString(dayXs) + " bot-top:" + (bottom - top) + " minPixels:" +
                    minPixels
            )
            return null
        }
        val segments: LinkedList<DNASegment> = LinkedList<DNASegment>()
        val strands: HashMap<Int, DNAStrand> = HashMap<Int, DNAStrand>()
        // add a black strand by default, other colors will get added in
        // the loop
        val blackStrand = DNAStrand()
        blackStrand.color = CONFLICT_COLOR
        strands.put(CONFLICT_COLOR, blackStrand)
        // the min length is the number of minutes that will occupy
        // MIN_SEGMENT_PIXELS in the 'work day' time slot. This computes the
        // minutes/pixel * minpx where the number of pixels are 3/4 the total
        // dna height: 4*(mins/(px * 3/4))
        val minMinutes = minPixels * 4 * WORK_DAY_MINUTES / (3 * (bottom - top))

        // There are slightly fewer than half as many pixels in 1/6 the space,
        // so round to 2.5x for the min minutes in the non-work area
        val minOtherMinutes = minMinutes * 5 / 2
        val lastJulianDay = firstJulianDay + dayXs.size - 1
        val event = Event()
        // Go through all the events for the week
        for (currEvent in events) {
            // if this event is outside the weeks range skip it
            if (currEvent != null &&
                (currEvent.endDay < firstJulianDay || currEvent.startDay > lastJulianDay)) {
                continue
            }
            if (currEvent?.drawAsAllday() == true) {
                addAllDayToStrands(currEvent, strands, firstJulianDay, dayXs.size)
                continue
            }
            // Copy the event over so we can clip its start and end to our range
            currEvent?.copyTo(event)
            if (event.startDay < firstJulianDay) {
                event.startDay = firstJulianDay
                event.startTime = 0
            }
            // If it starts after the work day make sure the start is at least
            // minPixels from midnight
            if (event.startTime > DAY_IN_MINUTES - minOtherMinutes) {
                event.startTime = DAY_IN_MINUTES - minOtherMinutes
            }
            if (event.endDay > lastJulianDay) {
                event.endDay = lastJulianDay
                event.endTime = DAY_IN_MINUTES - 1
            }
            // If the end time is before the work day make sure it ends at least
            // minPixels after midnight
            if (event.endTime < minOtherMinutes) {
                event.endTime = minOtherMinutes
            }
            // If the start and end are on the same day make sure they are at
            // least minPixels apart. This only needs to be done for times
            // outside the work day as the min distance for within the work day
            // is enforced in the segment code.
            if (event.startDay === event.endDay &&
                event.endTime - event.startTime < minOtherMinutes
            ) {
                // If it's less than minPixels in an area before the work
                // day
                if (event.startTime < WORK_DAY_START_MINUTES) {
                    // extend the end to the first easy guarantee that it's
                    // minPixels
                    event.endTime = Math.min(
                        event.startTime + minOtherMinutes,
                        WORK_DAY_START_MINUTES + minMinutes
                    )
                    // if it's in the area after the work day
                } else if (event.endTime > WORK_DAY_END_MINUTES) {
                    // First try shifting the end but not past midnight
                    event.endTime = Math.min(event.endTime + minOtherMinutes, DAY_IN_MINUTES - 1)
                    // if it's still too small move the start back
                    if (event.endTime - event.startTime < minOtherMinutes) {
                        event.startTime = event.endTime - minOtherMinutes
                    }
                }
            }

            // This handles adding the first segment
            if (segments.size == 0) {
                addNewSegment(segments, event, strands, firstJulianDay, 0, minMinutes)
                continue
            }
            // Now compare our current start time to the end time of the last
            // segment in the list
            val lastSegment: DNASegment = segments.getLast()
            var startMinute: Int =
                (event.startDay - firstJulianDay) * DAY_IN_MINUTES + event.startTime
            var endMinute: Int = Math.max(
                (event.endDay - firstJulianDay) * DAY_IN_MINUTES +
                    event.endTime, startMinute + minMinutes
            )
            if (startMinute < 0) {
                startMinute = 0
            }
            if (endMinute >= WEEK_IN_MINUTES) {
                endMinute = WEEK_IN_MINUTES - 1
            }
            // If we start before the last segment in the list ends we need to
            // start going through the list as this may conflict with other
            // events
            if (startMinute < lastSegment.endMinute) {
                var i: Int = segments.size
                // find the last segment this event intersects with
                while (--i >= 0 && endMinute < segments.get(i).startMinute) {}

                var currSegment: DNASegment = DNASegment()
                // for each segment this event intersects with
                while (i >= 0 && startMinute <= segments.get(i)
                        .also { currSegment = it }.endMinute) {

                    // if the segment is already a conflict ignore it
                    if (currSegment.color == CONFLICT_COLOR) {
                        i--
                        continue
                    }
                    // if the event ends before the segment and wouldn't create
                    // a segment that is too small split off the right side
                    if (endMinute < currSegment.endMinute - minMinutes) {
                        val rhs = DNASegment()
                        rhs.endMinute = currSegment.endMinute
                        rhs.color = currSegment.color
                        rhs.startMinute = endMinute + 1
                        rhs.day = currSegment.day
                        currSegment.endMinute = endMinute
                        segments.add(i + 1, rhs)
                        // Equivalent to strands.get(rhs.color)?.count++
                        // but there is no null safe invocation for ++
                        strands.get(rhs.color)?.count = strands.get(rhs.color)?.count?.inc() as Int
                        if (DEBUG) {
                            Log.d(
                                TAG, "Added rhs, curr:" + currSegment.toString() + " i:" +
                                    segments.get(i).toString()
                            )
                        }
                    }
                    // if the event starts after the segment and wouldn't create
                    // a segment that is too small split off the left side
                    if (startMinute > currSegment.startMinute + minMinutes) {
                        val lhs = DNASegment()
                        lhs.startMinute = currSegment.startMinute
                        lhs.color = currSegment.color
                        lhs.endMinute = startMinute - 1
                        lhs.day = currSegment.day
                        currSegment.startMinute = startMinute
                        // increment i so that we are at the right position when
                        // referencing the segments to the right and left of the
                        // current segment.
                        segments.add(i++, lhs)
                        strands.get(lhs.color)?.count = strands.get(lhs.color)?.count?.inc() as Int
                        if (DEBUG) {
                            Log.d(
                                TAG, "Added lhs, curr:" + currSegment.toString() + " i:" +
                                    segments.get(i).toString()
                            )
                        }
                    }
                    // if the right side is black merge this with the segment to
                    // the right if they're on the same day and overlap
                    if (i + 1 < segments.size) {
                        val rhs: DNASegment = segments.get(i + 1)
                        if (rhs.color == CONFLICT_COLOR && currSegment.day == rhs.day &&
                            rhs.startMinute <= currSegment.endMinute + 1) {
                            rhs.startMinute = Math.min(currSegment.startMinute, rhs.startMinute)
                            segments.remove(currSegment)
                            strands.get(currSegment.color)?.count =
                                strands.get(currSegment.color)?.count?.dec() as Int
                            // point at the new current segment
                            currSegment = rhs
                        }
                    }
                    // if the left side is black merge this with the segment to
                    // the left if they're on the same day and overlap
                    if (i - 1 >= 0) {
                        val lhs: DNASegment = segments.get(i - 1)
                        if (lhs.color == CONFLICT_COLOR && currSegment.day == lhs.day &&
                            lhs.endMinute >= currSegment.startMinute - 1) {
                            lhs.endMinute = Math.max(currSegment.endMinute, lhs.endMinute)
                            segments.remove(currSegment)
                            strands.get(currSegment.color)?.count =
                                strands.get(currSegment.color)?.count?.dec() as Int
                            // point at the new current segment
                            currSegment = lhs
                            // point i at the new current segment in case new
                            // code is added
                            i--
                        }
                    }
                    // if we're still not black, decrement the count for the
                    // color being removed, change this to black, and increment
                    // the black count
                    if (currSegment.color != CONFLICT_COLOR) {
                        strands.get(currSegment.color)?.count =
                            strands.get(currSegment.color)?.count?.dec() as Int
                        currSegment.color = CONFLICT_COLOR
                        strands.get(CONFLICT_COLOR)?.count =
                            strands.get(CONFLICT_COLOR)?.count?.inc() as Int
                    }
                    i--
                }
            }
            // If this event extends beyond the last segment add a new segment
            if (endMinute > lastSegment.endMinute) {
                addNewSegment(
                    segments, event, strands, firstJulianDay, lastSegment.endMinute,
                    minMinutes
                )
            }
        }
        weaveDNAStrands(segments, firstJulianDay, strands, top, bottom, dayXs)
        return strands
    }

    // This figures out allDay colors as allDay events are found
    private fun addAllDayToStrands(
        event: Event?,
        strands: HashMap<Int, DNAStrand>,
        firstJulianDay: Int,
        numDays: Int
    ) {
        val strand = getOrCreateStrand(strands, CONFLICT_COLOR)
        // if we haven't initialized the allDay portion create it now
        if (strand?.allDays == null) {
            strand?.allDays = IntArray(numDays)
        }

        // For each day this event is on update the color
        val end: Int = Math.min((event?.endDay ?: 0) - firstJulianDay, numDays - 1)
        for (i in Math.max((event?.startDay ?: 0) - firstJulianDay, 0)..end) {
            if (strand?.allDays!![i] != 0) {
                // if this day already had a color, it is now a conflict
                strand?.allDays!![i] = CONFLICT_COLOR
            } else {
                // else it's just the color of the event
                strand?.allDays!![i] = event?.color as Int
            }
        }
    }

    // This processes all the segments, sorts them by color, and generates a
    // list of points to draw
    private fun weaveDNAStrands(
        segments: LinkedList<DNASegment>,
        firstJulianDay: Int,
        strands: HashMap<Int, DNAStrand>,
        top: Int,
        bottom: Int,
        dayXs: IntArray
    ) {
        // First, get rid of any colors that ended up with no segments
        val strandIterator = strands.values.iterator()
        while (strandIterator.hasNext()) {
            val strand = strandIterator.next()
            if (strand.count < 1 && strand.allDays == null) {
                strandIterator.remove()
                continue
            }
            strand.points = FloatArray(strand.count * 4)
            strand.position = 0
        }
        // Go through each segment and compute its points
        for (segment in segments) {
            // Add the points to the strand of that color
            val strand: DNAStrand? = strands.get(segment.color)
            val dayIndex = segment.day - firstJulianDay
            val dayStartMinute = segment.startMinute % DAY_IN_MINUTES
            val dayEndMinute = segment.endMinute % DAY_IN_MINUTES
            val height = bottom - top
            val workDayHeight = height * 3 / 4
            val remainderHeight = (height - workDayHeight) / 2
            val x = dayXs[dayIndex]
            var y0 = 0
            var y1 = 0
            y0 = top + getPixelOffsetFromMinutes(dayStartMinute, workDayHeight, remainderHeight)
            y1 = top + getPixelOffsetFromMinutes(dayEndMinute, workDayHeight, remainderHeight)
            if (DEBUG) {
                Log.d(
                    TAG,
                    "Adding " + Integer.toHexString(segment.color).toString() + " at x,y0,y1: " + x
                        .toString() + " " + y0.toString() + " " + y1.toString() +
                        " for " + dayStartMinute.toString() + " " + dayEndMinute
                )
            }
            strand?.points!![strand.position] = x.toFloat()
            strand.position = strand.position.inc() as Int

            strand.points!![strand.position] = y0.toFloat()
            strand.position = strand.position.inc() as Int

            strand.points!![strand.position] = x.toFloat()
            strand.position = strand.position.inc() as Int

            strand.points!![strand.position] = y1.toFloat()
            strand.position = strand.position.inc() as Int
        }
    }

    /**
     * Compute a pixel offset from the top for a given minute from the work day
     * height and the height of the top area.
     */
    private fun getPixelOffsetFromMinutes(
        minute: Int,
        workDayHeight: Int,
        remainderHeight: Int
    ): Int {
        val y: Int
        if (minute < WORK_DAY_START_MINUTES) {
            y = minute * remainderHeight / WORK_DAY_START_MINUTES
        } else if (minute < WORK_DAY_END_MINUTES) {
            y = remainderHeight + (minute - WORK_DAY_START_MINUTES) *
                workDayHeight / WORK_DAY_MINUTES
        } else {
            y = remainderHeight + workDayHeight +
                (minute - WORK_DAY_END_MINUTES) * remainderHeight / WORK_DAY_END_LENGTH
        }
        return y
    }

    /**
     * Add a new segment based on the event provided. This will handle splitting
     * segments across day boundaries and ensures a minimum size for segments.
     */
    private fun addNewSegment(
        segments: LinkedList<DNASegment>,
        event: Event,
        strands: HashMap<Int, DNAStrand>,
        firstJulianDay: Int,
        minStart: Int,
        minMinutes: Int
    ) {
        var event: Event = event
        var minStart = minStart
        if (event.startDay > event.endDay) {
            Log.wtf(TAG, "Event starts after it ends: " + event.toString())
        }
        // If this is a multiday event split it up by day
        if (event.startDay !== event.endDay) {
            val lhs = Event()
            lhs.color = event.color
            lhs.startDay = event.startDay
            // the first day we want the start time to be the actual start time
            lhs.startTime = event.startTime
            lhs.endDay = lhs.startDay
            lhs.endTime = DAY_IN_MINUTES - 1
            // Nearly recursive iteration!
            while (lhs.startDay !== event.endDay) {
                addNewSegment(segments, lhs, strands, firstJulianDay, minStart, minMinutes)
                // The days in between are all day, even though that shouldn't
                // actually happen due to the allday filtering
                lhs.startDay++
                lhs.endDay = lhs.startDay
                lhs.startTime = 0
                minStart = 0
            }
            // The last day we want the end time to be the actual end time
            lhs.endTime = event.endTime
            event = lhs
        }
        // Create the new segment and compute its fields
        val segment = DNASegment()
        val dayOffset: Int = (event.startDay - firstJulianDay) * DAY_IN_MINUTES
        val endOfDay = dayOffset + DAY_IN_MINUTES - 1
        // clip the start if needed
        segment.startMinute = Math.max(dayOffset + event.startTime, minStart)
        // and extend the end if it's too small, but not beyond the end of the
        // day
        val minEnd: Int = Math.min(segment.startMinute + minMinutes, endOfDay)
        segment.endMinute = Math.max(dayOffset + event.endTime, minEnd)
        if (segment.endMinute > endOfDay) {
            segment.endMinute = endOfDay
        }
        segment.color = event.color
        segment.day = event.startDay
        segments.add(segment)
        // increment the count for the correct color or add a new strand if we
        // don't have that color yet
        val strand = getOrCreateStrand(strands, segment.color)
        strand?.count
        strand?.count = strand?.count?.inc() as Int
    }

    /**
     * Try to get a strand of the given color. Create it if it doesn't exist.
     */
    private fun getOrCreateStrand(strands: HashMap<Int, DNAStrand>, color: Int): DNAStrand? {
        var strand: DNAStrand? = strands.get(color)
        if (strand == null) {
            strand = DNAStrand()
            strand.color = color
            strand.count = 0
            strands.put(strand.color, strand)
        }
        return strand
    }

    /**
     * Sends an intent to launch the top level Calendar view.
     *
     * @param context
     */
    @JvmStatic fun returnToCalendarHome(context: Context) {
        val launchIntent = Intent(context, AllInOneActivity::class.java)
        launchIntent.setAction(Intent.ACTION_DEFAULT)
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launchIntent.putExtra(INTENT_KEY_HOME, true)
        context.startActivity(launchIntent)
    }

    /**
     * Given a context and a time in millis since unix epoch figures out the
     * correct week of the year for that time.
     *
     * @param millisSinceEpoch
     * @return
     */
    @JvmStatic fun getWeekNumberFromTime(millisSinceEpoch: Long, context: Context?): Int {
        val weekTime = Time(getTimeZone(context, null))
        weekTime.set(millisSinceEpoch)
        weekTime.normalize(true)
        val firstDayOfWeek = getFirstDayOfWeek(context)
        // if the date is on Saturday or Sunday and the start of the week
        // isn't Monday we may need to shift the date to be in the correct
        // week
        if (weekTime.weekDay === Time.SUNDAY &&
            (firstDayOfWeek == Time.SUNDAY || firstDayOfWeek == Time.SATURDAY)
        ) {
            weekTime.monthDay++
            weekTime.normalize(true)
        } else if (weekTime.weekDay === Time.SATURDAY && firstDayOfWeek == Time.SATURDAY) {
            weekTime.monthDay += 2
            weekTime.normalize(true)
        }
        return weekTime.getWeekNumber()
    }

    /**
     * Formats a day of the week string. This is either just the name of the day
     * or a combination of yesterday/today/tomorrow and the day of the week.
     *
     * @param julianDay The julian day to get the string for
     * @param todayJulianDay The julian day for today's date
     * @param millis A utc millis since epoch time that falls on julian day
     * @param context The calling context, used to get the timezone and do the
     * formatting
     * @return
     */
    @JvmStatic fun getDayOfWeekString(
        julianDay: Int,
        todayJulianDay: Int,
        millis: Long,
        context: Context
    ): String {
        getTimeZone(context, null)
        val flags: Int = DateUtils.FORMAT_SHOW_WEEKDAY
        var dayViewText: String
        dayViewText = if (julianDay == todayJulianDay) {
            context.getString(
                R.string.agenda_today,
                mTZUtils?.formatDateRange(context, millis, millis, flags)
                    .toString()
            )
        } else if (julianDay == todayJulianDay - 1) {
            context.getString(
                R.string.agenda_yesterday,
                mTZUtils?.formatDateRange(context, millis, millis, flags)
                    .toString()
            )
        } else if (julianDay == todayJulianDay + 1) {
            context.getString(
                R.string.agenda_tomorrow,
                mTZUtils?.formatDateRange(context, millis, millis, flags)
                    .toString()
            )
        } else {
            mTZUtils?.formatDateRange(context, millis, millis, flags)
                .toString()
        }
        dayViewText = dayViewText.toUpperCase()
        return dayViewText
    }

    // Calculate the time until midnight + 1 second and set the handler to
    // do run the runnable
    @JvmStatic fun setMidnightUpdater(h: Handler?, r: Runnable?, timezone: String?) {
        if (h == null || r == null || timezone == null) {
            return
        }
        val now: Long = System.currentTimeMillis()
        val time = Time(timezone)
        time.set(now)
        val runInMillis: Long = ((24 * 3600 - time.hour * 3600 - time.minute * 60 -
            time.second + 1) * 1000).toLong()
        h.removeCallbacks(r)
        h.postDelayed(r, runInMillis)
    }

    // Stop the midnight update thread
    @JvmStatic fun resetMidnightUpdater(h: Handler?, r: Runnable?) {
        if (h == null || r == null) {
            return
        }
        h.removeCallbacks(r)
    }

    /**
     * Returns a string description of the specified time interval.
     */
    @JvmStatic fun getDisplayedDatetime(
        startMillis: Long,
        endMillis: Long,
        currentMillis: Long,
        localTimezone: String,
        allDay: Boolean,
        context: Context
    ): String? {
        // Configure date/time formatting.
        val flagsDate: Int = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY
        var flagsTime: Int = DateUtils.FORMAT_SHOW_TIME
        if (DateFormat.is24HourFormat(context)) {
            flagsTime = flagsTime or DateUtils.FORMAT_24HOUR
        }
        val currentTime = Time(localTimezone)
        currentTime.set(currentMillis)
        val resources: Resources = context.getResources()
        var datetimeString: String? = null
        if (allDay) {
            // All day events require special timezone adjustment.
            val localStartMillis = convertAlldayUtcToLocal(null, startMillis, localTimezone)
            val localEndMillis = convertAlldayUtcToLocal(null, endMillis, localTimezone)
            if (singleDayEvent(localStartMillis, localEndMillis, currentTime.gmtoff)) {
                // If possible, use "Today" or "Tomorrow" instead of a full date string.
                val todayOrTomorrow = isTodayOrTomorrow(
                    context.getResources(),
                    localStartMillis, currentMillis, currentTime.gmtoff
                )
                if (TODAY == todayOrTomorrow) {
                    datetimeString = resources.getString(R.string.today)
                } else if (TOMORROW == todayOrTomorrow) {
                    datetimeString = resources.getString(R.string.tomorrow)
                }
            }
            if (datetimeString == null) {
                // For multi-day allday events or single-day all-day events that are not
                // today or tomorrow, use framework formatter.
                val f = Formatter(StringBuilder(50), Locale.getDefault())
                datetimeString = DateUtils.formatDateRange(
                    context, f, startMillis,
                    endMillis, flagsDate, Time.TIMEZONE_UTC
                ).toString()
            }
        } else {
            datetimeString = if (singleDayEvent(startMillis, endMillis, currentTime.gmtoff)) {
                // Format the time.
                val timeString = formatDateRange(
                    context, startMillis, endMillis,
                    flagsTime
                )

                // If possible, use "Today" or "Tomorrow" instead of a full date string.
                val todayOrTomorrow = isTodayOrTomorrow(
                    context.getResources(), startMillis,
                    currentMillis, currentTime.gmtoff
                )
                if (TODAY == todayOrTomorrow) {
                    // Example: "Today at 1:00pm - 2:00 pm"
                    resources.getString(
                        R.string.today_at_time_fmt,
                        timeString
                    )
                } else if (TOMORROW == todayOrTomorrow) {
                    // Example: "Tomorrow at 1:00pm - 2:00 pm"
                    resources.getString(
                        R.string.tomorrow_at_time_fmt,
                        timeString
                    )
                } else {
                    // Format the full date. Example: "Thursday, April 12, 1:00pm - 2:00pm"
                    val dateString = formatDateRange(
                        context, startMillis, endMillis,
                        flagsDate
                    )
                    resources.getString(
                        R.string.date_time_fmt, dateString,
                        timeString
                    )
                }
            } else {
                // For multiday events, shorten day/month names.
                // Example format: "Fri Apr 6, 5:00pm - Sun, Apr 8, 6:00pm"
                val flagsDatetime = flagsDate or flagsTime or DateUtils.FORMAT_ABBREV_MONTH or
                    DateUtils.FORMAT_ABBREV_WEEKDAY
                formatDateRange(
                    context, startMillis, endMillis,
                    flagsDatetime
                )
            }
        }
        return datetimeString
    }

    /**
     * Returns the timezone to display in the event info, if the local timezone is different
     * from the event timezone.  Otherwise returns null.
     */
    @JvmStatic fun getDisplayedTimezone(
        startMillis: Long,
        localTimezone: String?,
        eventTimezone: String?
    ): String? {
        var tzDisplay: String? = null
        if (!TextUtils.equals(localTimezone, eventTimezone)) {
            // Figure out if this is in DST
            val tz: TimeZone = TimeZone.getTimeZone(localTimezone)
            tzDisplay = if (tz == null || tz.getID().equals("GMT")) {
                localTimezone
            } else {
                val startTime = Time(localTimezone)
                startTime.set(startMillis)
                tz.getDisplayName(startTime.isDst !== 0, TimeZone.SHORT)
            }
        }
        return tzDisplay
    }

    /**
     * Returns whether the specified time interval is in a single day.
     */
    private fun singleDayEvent(startMillis: Long, endMillis: Long, localGmtOffset: Long): Boolean {
        if (startMillis == endMillis) {
            return true
        }

        // An event ending at midnight should still be a single-day event, so check
        // time end-1.
        val startDay: Int = Time.getJulianDay(startMillis, localGmtOffset)
        val endDay: Int = Time.getJulianDay(endMillis - 1, localGmtOffset)
        return startDay == endDay
    }

    // Using int constants as a return value instead of an enum to minimize resources.
    private const val TODAY = 1
    private const val TOMORROW = 2
    private const val NONE = 0

    /**
     * Returns TODAY or TOMORROW if applicable.  Otherwise returns NONE.
     */
    private fun isTodayOrTomorrow(
        r: Resources,
        dayMillis: Long,
        currentMillis: Long,
        localGmtOffset: Long
    ): Int {
        val startDay: Int = Time.getJulianDay(dayMillis, localGmtOffset)
        val currentDay: Int = Time.getJulianDay(currentMillis, localGmtOffset)
        val days = startDay - currentDay
        return if (days == 1) {
            TOMORROW
        } else if (days == 0) {
            TODAY
        } else {
            NONE
        }
    }

    /**
     * Inserts a drawable with today's day into the today's icon in the option menu
     * @param icon - today's icon from the options menu
     */
    @JvmStatic fun setTodayIcon(icon: LayerDrawable, c: Context?, timezone: String?) {
        val today: DayOfMonthDrawable

        // Reuse current drawable if possible
        val currentDrawable: Drawable? = icon.findDrawableByLayerId(R.id.today_icon_day)
        if (currentDrawable != null && currentDrawable is DayOfMonthDrawable) {
            today = currentDrawable as DayOfMonthDrawable
        } else {
            today = DayOfMonthDrawable(c as Context)
        }
        // Set the day and update the icon
        val now = Time(timezone)
        now.setToNow()
        now.normalize(false)
        today.setDayOfMonth(now.monthDay)
        icon.mutate()
        icon.setDrawableByLayerId(R.id.today_icon_day, today)
    }

    /**
     * Get a list of quick responses used for emailing guests from the
     * SharedPreferences. If not are found, get the hard coded ones that shipped
     * with the app
     *
     * @param context
     * @return a list of quick responses.
     */
    fun getQuickResponses(context: Context): Array<String> {
        var s = getSharedPreference(context, KEY_QUICK_RESPONSES, null as Array<String>?)
        if (s == null) {
            s = context.getResources().getStringArray(R.array.quick_response_defaults)
        }
        return s
    }

    /**
     * Return the app version code.
     */
    fun getVersionCode(context: Context): String? {
        if (sVersion == null) {
            try {
                sVersion = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0
                ).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                // Can't find version; just leave it blank.
                Log.e(TAG, "Error finding package " + context.getApplicationInfo().packageName)
            }
        }
        return sVersion
    }

    // A single strand represents one color of events. Events are divided up by
    // color to make them convenient to draw. The black strand is special in
    // that it holds conflicting events as well as color settings for allday on
    // each day.
    class DNAStrand {
        @JvmField var points: FloatArray? = null
        @JvmField var allDays: IntArray? = null // color for the allday, 0 means no event
        @JvmField var position = 0
        @JvmField var color = 0
        @JvmField var count = 0
    }

    // A segment is a single continuous length of time occupied by a single
    // color. Segments should never span multiple days.
    private class DNASegment {
        var startMinute = 0 // in minutes since the start of the week =
        var endMinute = 0
        var color = 0 // Calendar color or black for conflicts =
        var day = 0 // quick reference to the day this segment is on =
    }
}