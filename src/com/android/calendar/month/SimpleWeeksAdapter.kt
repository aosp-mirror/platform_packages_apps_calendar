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
package com.android.calendar.month
// TODO Remove calendar imports when the required methods have been
// refactored into the public api
import com.android.calendar.CalendarController
import com.android.calendar.Utils
import android.content.Context
import android.text.format.Time
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.widget.AbsListView.LayoutParams
import android.widget.BaseAdapter
import android.widget.ListView
import java.util.Calendar
import java.util.HashMap
import java.util.Locale

/**
 *
 *
 * This is a specialized adapter for creating a list of weeks with selectable
 * days. It can be configured to display the week number, start the week on a
 * given day, show a reduced number of days, or display an arbitrary number of
 * weeks at a time. See [SimpleDayPickerFragment] for usage.
 *
 */
open class SimpleWeeksAdapter(context: Context, params: HashMap<String?, Int?>?) : BaseAdapter(),
    OnTouchListener {
    protected var mContext: Context

    // The day to highlight as selected
    protected var mSelectedDay: Time? = null

    // The week since 1970 that the selected day is in
    protected var mSelectedWeek = 0

    // When the week starts; numbered like Time.<WEEKDAY> (e.g. SUNDAY=0).
    protected var mFirstDayOfWeek: Int
    protected var mShowWeekNumber = false
    protected var mGestureDetector: GestureDetector? = null
    protected var mNumWeeks = DEFAULT_NUM_WEEKS
    protected var mDaysPerWeek = DEFAULT_DAYS_PER_WEEK
    protected var mFocusMonth = DEFAULT_MONTH_FOCUS

    /**
     * Set up the gesture detector and selected time
     */
    protected open fun init() {
        mGestureDetector = GestureDetector(mContext, CalendarGestureListener())
        mSelectedDay = Time()
        mSelectedDay?.setToNow()
    }

    /**
     * Parse the parameters and set any necessary fields. See
     * [.WEEK_PARAMS_NUM_WEEKS] for parameter details.
     *
     * @param params A list of parameters for this adapter
     */
    fun updateParams(params: HashMap<String?, Int?>?) {
        if (params == null) {
            Log.e(TAG, "WeekParameters are null! Cannot update adapter.")
            return
        }
        if (params.containsKey(WEEK_PARAMS_FOCUS_MONTH)) {
            // Casting from Int? --> Int
            mFocusMonth = params.get(WEEK_PARAMS_FOCUS_MONTH) as Int
        }
        if (params.containsKey(WEEK_PARAMS_FOCUS_MONTH)) {
            // Casting from Int? --> Int
            mNumWeeks = params.get(WEEK_PARAMS_NUM_WEEKS) as Int
        }
        if (params.containsKey(WEEK_PARAMS_SHOW_WEEK)) {
            // Casting from Int? --> Int
            mShowWeekNumber = params.get(WEEK_PARAMS_SHOW_WEEK) as Int != 0
        }
        if (params.containsKey(WEEK_PARAMS_WEEK_START)) {
            // Casting from Int? --> Int
            mFirstDayOfWeek = params.get(WEEK_PARAMS_WEEK_START) as Int
        }
        if (params.containsKey(WEEK_PARAMS_JULIAN_DAY)) {
            // Casting from Int? --> Int
            val julianDay: Int = params.get(WEEK_PARAMS_JULIAN_DAY) as Int
            mSelectedDay?.setJulianDay(julianDay)
            mSelectedWeek = Utils.getWeeksSinceEpochFromJulianDay(julianDay, mFirstDayOfWeek)
        }
        if (params.containsKey(WEEK_PARAMS_DAYS_PER_WEEK)) {
            // Casting from Int? --> Int
            mDaysPerWeek = params.get(WEEK_PARAMS_DAYS_PER_WEEK) as Int
        }
        refresh()
    }

    /**
     * Updates the selected day and related parameters.
     *
     * @param selectedTime The time to highlight
     */
    open fun setSelectedDay(selectedTime: Time?) {
        mSelectedDay?.set(selectedTime)
        val millis: Long = mSelectedDay!!.normalize(true)
        mSelectedWeek = Utils.getWeeksSinceEpochFromJulianDay(
            Time.getJulianDay(millis, mSelectedDay!!.gmtoff), mFirstDayOfWeek
        )
        notifyDataSetChanged()
    }

    /**
     * Returns the currently highlighted day
     *
     * @return
     */
    fun getSelectedDay(): Time? {
        return mSelectedDay
    }

    /**
     * updates any config options that may have changed and refreshes the view
     */
    internal open fun refresh() {
        notifyDataSetChanged()
    }

    @Override
    override fun getCount(): Int {
        return WEEK_COUNT
    }

    @Override
    override fun getItem(position: Int): Any? {
        return null
    }

    @Override
    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    @SuppressWarnings("unchecked")
    @Override
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v: SimpleWeekView
        var drawingParams: HashMap<String?, Int?>? = null
        if (convertView != null) {
            v = convertView as SimpleWeekView
            // We store the drawing parameters in the view so it can be recycled
            drawingParams = v.getTag() as HashMap<String?, Int?>
        } else {
            v = SimpleWeekView(mContext)
            // Set up the new view
            val params = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
            )
            v.setLayoutParams(params)
            v.setClickable(true)
            v.setOnTouchListener(this)
        }
        if (drawingParams == null) {
            drawingParams = HashMap<String?, Int?>()
        }
        drawingParams.clear()
        var selectedDay = -1
        if (mSelectedWeek == position) {
            selectedDay = mSelectedDay!!.weekDay
        }

        // pass in all the view parameters
        drawingParams.put(
            SimpleWeekView.VIEW_PARAMS_HEIGHT,
            (parent.getHeight() - WEEK_7_OVERHANG_HEIGHT) / mNumWeeks
        )
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_SELECTED_DAY, selectedDay)
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_SHOW_WK_NUM, if (mShowWeekNumber) 1 else 0)
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_WEEK_START, mFirstDayOfWeek)
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_NUM_DAYS, mDaysPerWeek)
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_WEEK, position)
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_FOCUS_MONTH, mFocusMonth)
        v.setWeekParams(drawingParams, mSelectedDay!!.timezone)
        v.invalidate()
        return v
    }

    /**
     * Changes which month is in focus and updates the view.
     *
     * @param month The month to show as in focus [0-11]
     */
    fun updateFocusMonth(month: Int) {
        mFocusMonth = month
        notifyDataSetChanged()
    }

    @Override
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (mGestureDetector!!.onTouchEvent(event)) {
            val view: SimpleWeekView = v as SimpleWeekView
            val day: Time? = (v as SimpleWeekView).getDayFromLocation(event.getX())
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Touched day at Row=" + view.mWeek.toString() + " day=" +
                    day?.toString())
            }
            if (day != null) {
                onDayTapped(day)
            }
            return true
        }
        return false
    }

    /**
     * Maintains the same hour/min/sec but moves the day to the tapped day.
     *
     * @param day The day that was tapped
     */
    protected open fun onDayTapped(day: Time) {
        day.hour = mSelectedDay!!.hour
        day.minute = mSelectedDay!!.minute
        day.second = mSelectedDay!!.second
        setSelectedDay(day)
    }

    /**
     * This is here so we can identify single tap events and set the selected
     * day correctly
     */
    protected inner class CalendarGestureListener : GestureDetector.SimpleOnGestureListener() {
        @Override
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            return true
        }
    }

    var mListView: ListView? = null
    fun setListView(lv: ListView?) {
        mListView = lv
    }

    companion object {
        private const val TAG = "MonthByWeek"

        /**
         * The number of weeks to display at a time.
         */
        const val WEEK_PARAMS_NUM_WEEKS = "num_weeks"

        /**
         * Which month should be in focus currently.
         */
        const val WEEK_PARAMS_FOCUS_MONTH = "focus_month"

        /**
         * Whether the week number should be shown. Non-zero to show them.
         */
        const val WEEK_PARAMS_SHOW_WEEK = "week_numbers"

        /**
         * Which day the week should start on. [Time.SUNDAY] through
         * [Time.SATURDAY].
         */
        const val WEEK_PARAMS_WEEK_START = "week_start"

        /**
         * The Julian day to highlight as selected.
         */
        const val WEEK_PARAMS_JULIAN_DAY = "selected_day"

        /**
         * How many days of the week to display [1-7].
         */
        const val WEEK_PARAMS_DAYS_PER_WEEK = "days_per_week"
        protected const val WEEK_COUNT = CalendarController.MAX_CALENDAR_WEEK -
            CalendarController.MIN_CALENDAR_WEEK
        protected var DEFAULT_NUM_WEEKS = 6
        protected var DEFAULT_MONTH_FOCUS = 0
        protected var DEFAULT_DAYS_PER_WEEK = 7
        protected var DEFAULT_WEEK_HEIGHT = 32
        protected var WEEK_7_OVERHANG_HEIGHT = 7
        protected var mScale = 0f
    }

    init {
        mContext = context

        // Get default week start based on locale, subtracting one for use with android Time.
        val cal: Calendar = Calendar.getInstance(Locale.getDefault())
        mFirstDayOfWeek = cal.getFirstDayOfWeek() - 1
        if (mScale == 0f) {
            mScale = context.getResources().getDisplayMetrics().density
            if (mScale != 1f) {
                WEEK_7_OVERHANG_HEIGHT *= mScale.toInt()
            }
        }
        init()
        updateParams(params)
    }
}