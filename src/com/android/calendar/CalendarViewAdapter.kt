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

import com.android.calendar.CalendarController.ViewType
import android.content.Context
import android.os.Handler
import android.text.format.DateUtils
import android.text.format.Time
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import java.util.Formatter
import java.util.Locale

/*
 * The MenuSpinnerAdapter defines the look of the ActionBar's pull down menu
 * for small screen layouts. The pull down menu replaces the tabs uses for big screen layouts
 *
 * The MenuSpinnerAdapter responsible for creating the views used for in the pull down menu.
 */
class CalendarViewAdapter(context: Context, viewType: Int, showDate: Boolean) : BaseAdapter() {
    private val mButtonNames: Array<String> // Text on buttons

    // Used to define the look of the menu button according to the current view:
    // Day view: show day of the week + full date underneath
    // Week view: show the month + year
    // Month view: show the month + year
    // Agenda view: show day of the week + full date underneath
    private var mCurrentMainView: Int
    private val mInflater: LayoutInflater

    // The current selected event's time, used to calculate the date and day of the week
    // for the buttons.
    private var mMilliTime: Long = 0
    private var mTimeZone: String? = null
    private var mTodayJulianDay: Long = 0
    private val mContext: Context = context
    private val mFormatter: Formatter
    private val mStringBuilder: StringBuilder
    private var mMidnightHandler: Handler? = null // Used to run a time update every midnight
    private val mShowDate: Boolean // Spinner mode indicator (view name or view name with date)

    // Updates time specific variables (time-zone, today's Julian day).
    private val mTimeUpdater: Runnable = object : Runnable {
        @Override
        override fun run() {
            refresh(mContext)
        }
    }

    // Sets the time zone and today's Julian day to be used by the adapter.
    // Also, notify listener on the change and resets the midnight update thread.
    fun refresh(context: Context?) {
        mTimeZone = Utils.getTimeZone(context, mTimeUpdater)
        val time = Time(mTimeZone)
        val now: Long = System.currentTimeMillis()
        time.set(now)
        mTodayJulianDay = Time.getJulianDay(now, time.gmtoff).toLong()
        notifyDataSetChanged()
        setMidnightHandler()
    }

    // Sets a thread to run 1 second after midnight and update the current date
    // This is used to display correctly the date of yesterday/today/tomorrow
    private fun setMidnightHandler() {
        mMidnightHandler?.removeCallbacks(mTimeUpdater)
        // Set the time updater to run at 1 second after midnight
        val now: Long = System.currentTimeMillis()
        val time = Time(mTimeZone)
        time.set(now)
        val runInMillis: Long = ((24 * 3600 - time.hour * 3600 - time.minute * 60 -
                time.second + 1) * 1000).toLong()
        mMidnightHandler?.postDelayed(mTimeUpdater, runInMillis)
    }

    // Stops the midnight update thread, called by the activity when it is paused.
    fun onPause() {
        mMidnightHandler?.removeCallbacks(mTimeUpdater)
    }

    // Returns the amount of buttons in the menu
    @Override
    override fun getCount(): Int {
        return mButtonNames.size
    }

    @Override
    override fun getItem(position: Int): Any? {
        return if (position < mButtonNames.size) {
            mButtonNames[position]
        } else null
    }

    @Override
    override fun getItemId(position: Int): Long {
        // Item ID is its location in the list
        return position.toLong()
    }

    @Override
    override fun hasStableIds(): Boolean {
        return false
    }

    @Override
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View? {
        var v: View?
        if (mShowDate) {
            // Check if can recycle the view
            if (convertView == null || (convertView.getTag() as Int)
                    != R.layout.actionbar_pulldown_menu_top_button as Int) {
                v = mInflater.inflate(R.layout.actionbar_pulldown_menu_top_button, parent, false)
                // Set the tag to make sure you can recycle it when you get it
                // as a convert view
                v.setTag(Integer(R.layout.actionbar_pulldown_menu_top_button))
            } else {
                v = convertView
            }
            val weekDay: TextView = v?.findViewById(R.id.top_button_weekday) as TextView
            val date: TextView = v.findViewById(R.id.top_button_date) as TextView
            when (mCurrentMainView) {
                ViewType.DAY -> {
                    weekDay.setVisibility(View.VISIBLE)
                    weekDay.setText(buildDayOfWeek())
                    date.setText(buildFullDate())
                }
                ViewType.WEEK -> {
                    if (Utils.getShowWeekNumber(mContext)) {
                        weekDay.setVisibility(View.VISIBLE)
                        weekDay.setText(buildWeekNum())
                    } else {
                        weekDay.setVisibility(View.GONE)
                    }
                    date.setText(buildMonthYearDate())
                }
                ViewType.MONTH -> {
                    weekDay.setVisibility(View.GONE)
                    date.setText(buildMonthYearDate())
                }
                else -> v = null
            }
        } else {
            if (convertView == null || (convertView.getTag() as Int)
                    != R.layout.actionbar_pulldown_menu_top_button_no_date as Int) {
                v = mInflater.inflate(
                        R.layout.actionbar_pulldown_menu_top_button_no_date, parent, false)
                // Set the tag to make sure you can recycle it when you get it
                // as a convert view
                v.setTag(Integer(R.layout.actionbar_pulldown_menu_top_button_no_date))
            } else {
                v = convertView
            }
            val title: TextView? = v as TextView?
            when (mCurrentMainView) {
                ViewType.DAY -> title?.setText(mButtonNames[DAY_BUTTON_INDEX])
                ViewType.WEEK -> title?.setText(mButtonNames[WEEK_BUTTON_INDEX])
                ViewType.MONTH -> title?.setText(mButtonNames[MONTH_BUTTON_INDEX])
                else -> v = null
            }
        }
        return v
    }

    @Override
    override fun getItemViewType(position: Int): Int {
        // Only one kind of view is used
        return BUTTON_VIEW_TYPE
    }

    @Override
    override fun getViewTypeCount(): Int {
        return VIEW_TYPE_NUM
    }

    @Override
    override fun isEmpty(): Boolean {
        return mButtonNames.size == 0
    }

    @Override
    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View? {
        var v: View? = mInflater.inflate(R.layout.actionbar_pulldown_menu_button, parent, false)
        val viewType: TextView? = v?.findViewById(R.id.button_view) as? TextView
        val date: TextView? = v?.findViewById(R.id.button_date) as? TextView
        when (position) {
            DAY_BUTTON_INDEX -> {
                viewType?.setText(mButtonNames[DAY_BUTTON_INDEX])
                if (mShowDate) {
                    date?.setText(buildMonthDayDate())
                }
            }
            WEEK_BUTTON_INDEX -> {
                viewType?.setText(mButtonNames[WEEK_BUTTON_INDEX])
                if (mShowDate) {
                    date?.setText(buildWeekDate())
                }
            }
            MONTH_BUTTON_INDEX -> {
                viewType?.setText(mButtonNames[MONTH_BUTTON_INDEX])
                if (mShowDate) {
                    date?.setText(buildMonthDate())
                }
            }
            else -> v = convertView
        }
        return v
    }

    // Updates the current viewType
    // Used to match the label on the menu button with the calendar view
    fun setMainView(viewType: Int) {
        mCurrentMainView = viewType
        notifyDataSetChanged()
    }

    // Update the date that is displayed on buttons
    // Used when the user selects a new day/week/month to watch
    fun setTime(time: Long) {
        mMilliTime = time
        notifyDataSetChanged()
    }

    // Builds a string with the day of the week and the word yesterday/today/tomorrow
    // before it if applicable.
    private fun buildDayOfWeek(): String {
        val t = Time(mTimeZone)
        t.set(mMilliTime)
        val julianDay: Long = Time.getJulianDay(mMilliTime, t.gmtoff).toLong()
        var dayOfWeek: String? = null
        mStringBuilder.setLength(0)
        dayOfWeek = if (julianDay == mTodayJulianDay) {
            mContext.getString(R.string.agenda_today,
                    DateUtils.formatDateRange(mContext, mFormatter, mMilliTime, mMilliTime,
                            DateUtils.FORMAT_SHOW_WEEKDAY, mTimeZone).toString())
        } else if (julianDay == mTodayJulianDay - 1) {
            mContext.getString(R.string.agenda_yesterday,
                    DateUtils.formatDateRange(mContext, mFormatter, mMilliTime, mMilliTime,
                            DateUtils.FORMAT_SHOW_WEEKDAY, mTimeZone).toString())
        } else if (julianDay == mTodayJulianDay + 1) {
            mContext.getString(R.string.agenda_tomorrow,
                    DateUtils.formatDateRange(mContext, mFormatter, mMilliTime, mMilliTime,
                            DateUtils.FORMAT_SHOW_WEEKDAY, mTimeZone).toString())
        } else {
            DateUtils.formatDateRange(mContext, mFormatter, mMilliTime, mMilliTime,
                    DateUtils.FORMAT_SHOW_WEEKDAY, mTimeZone).toString()
        }
        return dayOfWeek.toUpperCase()
    }

    // Builds strings with different formats:
    // Full date: Month,day Year
    // Month year
    // Month day
    // Month
    // Week:  month day-day or month day - month day
    private fun buildFullDate(): String {
        mStringBuilder.setLength(0)
        return DateUtils.formatDateRange(mContext, mFormatter, mMilliTime, mMilliTime,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR, mTimeZone).toString()
    }

    private fun buildMonthYearDate(): String {
        mStringBuilder.setLength(0)
        return DateUtils.formatDateRange(
                mContext,
                mFormatter,
                mMilliTime,
                mMilliTime,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY
                        or DateUtils.FORMAT_SHOW_YEAR, mTimeZone).toString()
    }

    private fun buildMonthDayDate(): String {
        mStringBuilder.setLength(0)
        return DateUtils.formatDateRange(mContext, mFormatter, mMilliTime, mMilliTime,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR, mTimeZone).toString()
    }

    private fun buildMonthDate(): String {
        mStringBuilder.setLength(0)
        return DateUtils.formatDateRange(
                mContext,
                mFormatter,
                mMilliTime,
                mMilliTime,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR
                        or DateUtils.FORMAT_NO_MONTH_DAY, mTimeZone).toString()
    }

    private fun buildWeekDate(): String {
        // Calculate the start of the week, taking into account the "first day of the week"
        // setting.
        val t = Time(mTimeZone)
        t.set(mMilliTime)
        val firstDayOfWeek: Int = Utils.getFirstDayOfWeek(mContext)
        val dayOfWeek: Int = t.weekDay
        var diff = dayOfWeek - firstDayOfWeek
        if (diff != 0) {
            if (diff < 0) {
                diff += 7
            }
            t.monthDay -= diff
            t.normalize(true /* ignore isDst */)
        }
        val weekStartTime: Long = t.toMillis(true)
        // The end of the week is 6 days after the start of the week
        val weekEndTime: Long = weekStartTime + DateUtils.WEEK_IN_MILLIS - DateUtils.DAY_IN_MILLIS

        // If week start and end is in 2 different months, use short months names
        val t1 = Time(mTimeZone)
        t.set(weekEndTime)
        var flags: Int = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR
        if (t.month !== t1.month) {
            flags = flags or DateUtils.FORMAT_ABBREV_MONTH
        }
        mStringBuilder.setLength(0)
        return DateUtils.formatDateRange(mContext, mFormatter, weekStartTime,
                weekEndTime, flags, mTimeZone).toString()
    }

    private fun buildWeekNum(): String {
        val week: Int = Utils.getWeekNumberFromTime(mMilliTime, mContext)
        return mContext.getResources().getQuantityString(R.plurals.weekN, week, week)
    }

    companion object {
        private const val TAG = "MenuSpinnerAdapter"

        // Defines the types of view returned by this spinner
        private const val BUTTON_VIEW_TYPE = 0
        const val VIEW_TYPE_NUM = 1 // Increase this if you add more view types
        const val DAY_BUTTON_INDEX = 0
        const val WEEK_BUTTON_INDEX = 1
        const val MONTH_BUTTON_INDEX = 2
        const val AGENDA_BUTTON_INDEX = 3
    }

    init {
        mMidnightHandler = Handler()
        mCurrentMainView = viewType
        mShowDate = showDate

        // Initialize
        mButtonNames = context.getResources().getStringArray(R.array.buttons_list)
        mInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mStringBuilder = StringBuilder(50)
        mFormatter = Formatter(mStringBuilder, Locale.getDefault())

        // Sets time specific variables and starts a thread for midnight updates
        if (showDate) {
            refresh(context)
        }
    }
}