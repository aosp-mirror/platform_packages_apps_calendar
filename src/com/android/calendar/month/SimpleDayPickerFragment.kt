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

import com.android.calendar.R
import com.android.calendar.Utils
import android.app.Activity
import android.app.ListFragment
import android.content.Context
import android.content.res.Resources
import android.database.DataSetObserver
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Time
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.AbsListView
import android.widget.AbsListView.OnScrollListener
import android.widget.ListView
import android.widget.TextView
import java.util.Calendar
import java.util.HashMap
import java.util.Locale

/**
 *
 *
 * This displays a titled list of weeks with selectable days. It can be
 * configured to display the week number, start the week on a given day, show a
 * reduced number of days, or display an arbitrary number of weeks at a time. By
 * overriding methods and changing variables this fragment can be customized to
 * easily display a month selection component in a given style.
 *
 */
open class SimpleDayPickerFragment(initialTime: Long) : ListFragment(), OnScrollListener {
    protected var WEEK_MIN_VISIBLE_HEIGHT = 12
    protected var BOTTOM_BUFFER = 20
    protected var mSaturdayColor = 0
    protected var mSundayColor = 0
    protected var mDayNameColor = 0

    // You can override these numbers to get a different appearance
    @JvmField protected var mNumWeeks = 6
    @JvmField protected var mShowWeekNumber = false
    @JvmField protected var mDaysPerWeek = 7

    // These affect the scroll speed and feel
    protected var mFriction = 1.0f
    @JvmField protected var mContext: Context? = null
    @JvmField protected var mHandler: Handler = Handler()
    protected var mMinimumFlingVelocity = 0f

    // highlighted time
    @JvmField protected var mSelectedDay: Time = Time()
    @JvmField protected var mAdapter: SimpleWeeksAdapter? = null
    @JvmField protected var mListView: ListView? = null
    @JvmField protected var mDayNamesHeader: ViewGroup? = null
    @JvmField protected var mDayLabels: Array<String?> = arrayOfNulls(7)

    // disposable variable used for time calculations
    @JvmField protected var mTempTime: Time = Time()

    // When the week starts; numbered like Time.<WEEKDAY> (e.g. SUNDAY=0).
    @JvmField protected var mFirstDayOfWeek = 0

    // The first day of the focus month
    @JvmField protected var mFirstDayOfMonth: Time = Time()

    // The first day that is visible in the view
    @JvmField protected var mFirstVisibleDay: Time = Time()

    // The name of the month to display
    protected var mMonthName: TextView? = null

    // The last name announced by accessibility
    protected var mPrevMonthName: CharSequence? = null

    // which month should be displayed/highlighted [0-11]
    protected var mCurrentMonthDisplayed = 0

    // used for tracking during a scroll
    protected var mPreviousScrollPosition: Long = 0

    // used for tracking which direction the view is scrolling
    protected var mIsScrollingUp = false

    // used for tracking what state listview is in
    protected var mPreviousScrollState: Int = OnScrollListener.SCROLL_STATE_IDLE

    // used for tracking what state listview is in
    protected var mCurrentScrollState: Int = OnScrollListener.SCROLL_STATE_IDLE

    // This causes an update of the view at midnight
    @JvmField protected var mTodayUpdater: Runnable = object : Runnable {
        @Override
        override fun run() {
            val midnight = Time(mFirstVisibleDay.timezone)
            midnight.setToNow()
            val currentMillis: Long = midnight.toMillis(true)
            midnight.hour = 0
            midnight.minute = 0
            midnight.second = 0
            midnight.monthDay++
            val millisToMidnight: Long = midnight.normalize(true) - currentMillis
            mHandler.postDelayed(this, millisToMidnight)
            if (mAdapter != null) {
                mAdapter?.notifyDataSetChanged()
            }
        }
    }

    // This allows us to update our position when a day is tapped
    @JvmField protected var mObserver: DataSetObserver = object : DataSetObserver() {
        @Override
        override fun onChanged() {
            val day: Time? = mAdapter!!.getSelectedDay()
            if (day!!.year !== mSelectedDay.year || day!!.yearDay !== mSelectedDay.yearDay) {
                goTo(day!!.toMillis(true), true, true, false)
            }
        }
    }

    @Override
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        mContext = activity
        val tz: String = Time.getCurrentTimezone()
        val viewConfig: ViewConfiguration = ViewConfiguration.get(activity)
        mMinimumFlingVelocity = (viewConfig.getScaledMinimumFlingVelocity()).toFloat()

        // Ensure we're in the correct time zone
        mSelectedDay.switchTimezone(tz)
        mSelectedDay.normalize(true)
        mFirstDayOfMonth.timezone = tz
        mFirstDayOfMonth.normalize(true)
        mFirstVisibleDay.timezone = tz
        mFirstVisibleDay.normalize(true)
        mTempTime.timezone = tz
        val res: Resources = activity.getResources()
        mSaturdayColor = res.getColor(R.color.month_saturday)
        mSundayColor = res.getColor(R.color.month_sunday)
        mDayNameColor = res.getColor(R.color.month_day_names_color)

        // Adjust sizes for screen density
        if (mScale == 0f) {
            mScale = activity.getResources().getDisplayMetrics().density
            if (mScale != 1f) {
                WEEK_MIN_VISIBLE_HEIGHT *= mScale.toInt()
                BOTTOM_BUFFER *= mScale.toInt()
                LIST_TOP_OFFSET *= mScale.toInt()
            }
        }
        setUpAdapter()
        setListAdapter(mAdapter)
    }

    /**
     * Creates a new adapter if necessary and sets up its parameters. Override
     * this method to provide a custom adapter.
     */
    protected open fun setUpAdapter() {
        val weekParams = HashMap<String?, Int?>()
        weekParams.put(SimpleWeeksAdapter.WEEK_PARAMS_NUM_WEEKS, mNumWeeks)
        weekParams.put(SimpleWeeksAdapter.WEEK_PARAMS_SHOW_WEEK, if (mShowWeekNumber) 1 else 0)
        weekParams.put(SimpleWeeksAdapter.WEEK_PARAMS_WEEK_START, mFirstDayOfWeek)
        weekParams.put(SimpleWeeksAdapter.WEEK_PARAMS_JULIAN_DAY,
                Time.getJulianDay(mSelectedDay.toMillis(false), mSelectedDay.gmtoff))
        if (mAdapter == null) {
            mAdapter = SimpleWeeksAdapter(getActivity(), weekParams)
            mAdapter?.registerDataSetObserver(mObserver)
        } else {
            mAdapter?.updateParams(weekParams)
        }
        // refresh the view with the new parameters
        mAdapter?.notifyDataSetChanged()
    }

    @Override
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Override
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setUpListView()
        setUpHeader()
        mMonthName = getView()?.findViewById(R.id.month_name) as? TextView
        val child = mListView?.getChildAt(0) as? SimpleWeekView
        if (child == null) {
            return
        }
        val julianDay: Int = child.getFirstJulianDay()
        mFirstVisibleDay.setJulianDay(julianDay)
        // set the title to the month of the second week
        mTempTime.setJulianDay(julianDay + DAYS_PER_WEEK)
        setMonthDisplayed(mTempTime, true)
    }

    /**
     * Sets up the strings to be used by the header. Override this method to use
     * different strings or modify the view params.
     */
    protected open fun setUpHeader() {
        mDayLabels = arrayOfNulls(7)
        for (i in Calendar.SUNDAY..Calendar.SATURDAY) {
            mDayLabels[i - Calendar.SUNDAY] = DateUtils.getDayOfWeekString(i,
                    DateUtils.LENGTH_SHORTEST).toUpperCase()
        }
    }

    /**
     * Sets all the required fields for the list view. Override this method to
     * set a different list view behavior.
     */
    protected fun setUpListView() {
        // Configure the listview
        mListView = getListView()
        // Transparent background on scroll
        mListView?.setCacheColorHint(0)
        // No dividers
        mListView?.setDivider(null)
        // Items are clickable
        mListView?.setItemsCanFocus(true)
        // The thumb gets in the way, so disable it
        mListView?.setFastScrollEnabled(false)
        mListView?.setVerticalScrollBarEnabled(false)
        mListView?.setOnScrollListener(this)
        mListView?.setFadingEdgeLength(0)
        // Make the scrolling behavior nicer
        mListView?.setFriction(ViewConfiguration.getScrollFriction() * mFriction)
    }

    @Override
    override fun onResume() {
        super.onResume()
        setUpAdapter()
        doResumeUpdates()
    }

    @Override
    override fun onPause() {
        super.onPause()
        mHandler.removeCallbacks(mTodayUpdater)
    }

    @Override
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_CURRENT_TIME, mSelectedDay.toMillis(true))
    }

    /**
     * Updates the user preference fields. Override this to use a different
     * preference space.
     */
    protected open fun doResumeUpdates() {
        // Get default week start based on locale, subtracting one for use with android Time.
        val cal: Calendar = Calendar.getInstance(Locale.getDefault())
        mFirstDayOfWeek = cal.getFirstDayOfWeek() - 1
        mShowWeekNumber = false
        updateHeader()
        goTo(mSelectedDay.toMillis(true), false, false, false)
        mAdapter?.setSelectedDay(mSelectedDay)
        mTodayUpdater.run()
    }

    /**
     * Fixes the day names header to provide correct spacing and updates the
     * label text. Override this to set up a custom header.
     */
    protected fun updateHeader() {
        var label: TextView = mDayNamesHeader!!.findViewById(R.id.wk_label) as TextView
        if (mShowWeekNumber) {
            label.setVisibility(View.VISIBLE)
        } else {
            label.setVisibility(View.GONE)
        }
        val offset = mFirstDayOfWeek - 1
        for (i in 1..7) {
            label = mDayNamesHeader!!.getChildAt(i) as TextView
            if (i < mDaysPerWeek + 1) {
                val position = (offset + i) % 7
                label.setText(mDayLabels[position])
                label.setVisibility(View.VISIBLE)
                if (position == Time.SATURDAY) {
                    label.setTextColor(mSaturdayColor)
                } else if (position == Time.SUNDAY) {
                    label.setTextColor(mSundayColor)
                } else {
                    label.setTextColor(mDayNameColor)
                }
            } else {
                label.setVisibility(View.GONE)
            }
        }
        mDayNamesHeader?.invalidate()
    }

    @Override
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v: View = inflater.inflate(R.layout.month_by_week,
                container, false)
        mDayNamesHeader = v.findViewById(R.id.day_names) as ViewGroup
        return v
    }

    /**
     * Returns the UTC millis since epoch representation of the currently
     * selected time.
     *
     * @return
     */
    val selectedTime: Long
        get() = mSelectedDay.toMillis(true)

    /**
     * This moves to the specified time in the view. If the time is not already
     * in range it will move the list so that the first of the month containing
     * the time is at the top of the view. If the new time is already in view
     * the list will not be scrolled unless forceScroll is true. This time may
     * optionally be highlighted as selected as well.
     *
     * @param time The time to move to
     * @param animate Whether to scroll to the given time or just redraw at the
     * new location
     * @param setSelected Whether to set the given time as selected
     * @param forceScroll Whether to recenter even if the time is already
     * visible
     * @return Whether or not the view animated to the new location
     */
    fun goTo(time: Long, animate: Boolean, setSelected: Boolean, forceScroll: Boolean): Boolean {
        if (time == -1L) {
            Log.e(TAG, "time is invalid")
            return false
        }

        // Set the selected day
        if (setSelected) {
            mSelectedDay.set(time)
            mSelectedDay.normalize(true)
        }

        // If this view isn't returned yet we won't be able to load the lists
        // current position, so return after setting the selected day.
        if (!isResumed()) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "We're not visible yet")
            }
            return false
        }
        mTempTime.set(time)
        var millis: Long = mTempTime.normalize(true)
        // Get the week we're going to
        // TODO push Util function into Calendar public api.
        var position: Int = Utils.getWeeksSinceEpochFromJulianDay(
                Time.getJulianDay(millis, mTempTime.gmtoff), mFirstDayOfWeek)
        var child: View?
        var i = 0
        var top = 0
        // Find a child that's completely in the view
        do {
            child = mListView?.getChildAt(i++)
            if (child == null) {
                break
            }
            top = child.getTop()
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "child at " + (i - 1) + " has top " + top)
            }
        } while (top < 0)

        // Compute the first and last position visible
        val firstPosition: Int
        firstPosition = if (child != null) {
            mListView!!.getPositionForView(child)
        } else {
            0
        }
        var lastPosition = firstPosition + mNumWeeks - 1
        if (top > BOTTOM_BUFFER) {
            lastPosition--
        }
        if (setSelected) {
            mAdapter?.setSelectedDay(mSelectedDay)
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "GoTo position $position")
        }
        // Check if the selected day is now outside of our visible range
        // and if so scroll to the month that contains it
        if (position < firstPosition || position > lastPosition || forceScroll) {
            mFirstDayOfMonth.set(mTempTime)
            mFirstDayOfMonth.monthDay = 1
            millis = mFirstDayOfMonth.normalize(true)
            setMonthDisplayed(mFirstDayOfMonth, true)
            position = Utils.getWeeksSinceEpochFromJulianDay(
                    Time.getJulianDay(millis, mFirstDayOfMonth.gmtoff), mFirstDayOfWeek)
            mPreviousScrollState = OnScrollListener.SCROLL_STATE_FLING
            if (animate) {
                mListView?.smoothScrollToPositionFromTop(
                        position, LIST_TOP_OFFSET, GOTO_SCROLL_DURATION)
                return true
            } else {
                mListView?.setSelectionFromTop(position, LIST_TOP_OFFSET)
                // Perform any after scroll operations that are needed
                onScrollStateChanged(mListView, OnScrollListener.SCROLL_STATE_IDLE)
            }
        } else if (setSelected) {
            // Otherwise just set the selection
            setMonthDisplayed(mSelectedDay, true)
        }
        return false
    }

    /**
     * Updates the title and selected month if the view has moved to a new
     * month.
     */
    @Override
    override fun onScroll(
        view: AbsListView,
        firstVisibleItem: Int,
        visibleItemCount: Int,
        totalItemCount: Int
    ) {
        val child = view.getChildAt(0) as? SimpleWeekView
        if (child == null) {
            return
        }

        // Figure out where we are
        val currScroll: Long = (view.getFirstVisiblePosition() * child.getHeight() -
                                child.getBottom()).toLong()
        mFirstVisibleDay.setJulianDay(child.getFirstJulianDay())

        // If we have moved since our last call update the direction
        mIsScrollingUp = if (currScroll < mPreviousScrollPosition) {
            true
        } else if (currScroll > mPreviousScrollPosition) {
            false
        } else {
            return
        }
        mPreviousScrollPosition = currScroll
        mPreviousScrollState = mCurrentScrollState
        updateMonthHighlight(mListView as? AbsListView)
    }

    /**
     * Figures out if the month being shown has changed and updates the
     * highlight if needed
     *
     * @param view The ListView containing the weeks
     */
    private fun updateMonthHighlight(view: AbsListView?) {
        var child = view?.getChildAt(0) as? SimpleWeekView
        if (child == null) {
            return
        }

        // Figure out where we are
        val offset = if (child.getBottom() < WEEK_MIN_VISIBLE_HEIGHT) 1 else 0
        // Use some hysteresis for checking which month to highlight. This
        // causes the month to transition when two full weeks of a month are
        // visible.
        child = view?.getChildAt(SCROLL_HYST_WEEKS + offset) as? SimpleWeekView
        if (child == null) {
            return
        }

        // Find out which month we're moving into
        val month: Int
        month = if (mIsScrollingUp) {
            child.getFirstMonth()
        } else {
            child.getLastMonth()
        }

        // And how it relates to our current highlighted month
        val monthDiff: Int
        monthDiff = if (mCurrentMonthDisplayed == 11 && month == 0) {
            1
        } else if (mCurrentMonthDisplayed == 0 && month == 11) {
            -1
        } else {
            month - mCurrentMonthDisplayed
        }

        // Only switch months if we're scrolling away from the currently
        // selected month
        if (monthDiff != 0) {
            var julianDay: Int = child.getFirstJulianDay()
            if (mIsScrollingUp) {
                // Takes the start of the week
            } else {
                // Takes the start of the following week
                julianDay += DAYS_PER_WEEK
            }
            mTempTime.setJulianDay(julianDay)
            setMonthDisplayed(mTempTime, false)
        }
    }

    /**
     * Sets the month displayed at the top of this view based on time. Override
     * to add custom events when the title is changed.
     *
     * @param time A day in the new focus month.
     * @param updateHighlight TODO(epastern):
     */
    protected open fun setMonthDisplayed(time: Time, updateHighlight: Boolean) {
        val oldMonth: CharSequence = mMonthName!!.getText()
        mMonthName?.setText(Utils.formatMonthYear(mContext, time))
        mMonthName?.invalidate()
        if (!TextUtils.equals(oldMonth, mMonthName?.getText())) {
            mMonthName?.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
        mCurrentMonthDisplayed = time.month
        if (updateHighlight) {
            mAdapter?.updateFocusMonth(mCurrentMonthDisplayed)
        }
    }

    @Override
    override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
        // use a post to prevent re-entering onScrollStateChanged before it
        // exits
        mScrollStateChangedRunnable.doScrollStateChange(view, scrollState)
    }

    @JvmField protected var mScrollStateChangedRunnable: ScrollStateRunnable = ScrollStateRunnable()

    protected inner class ScrollStateRunnable : Runnable {
        private var mNewState = 0

        /**
         * Sets up the runnable with a short delay in case the scroll state
         * immediately changes again.
         *
         * @param view The list view that changed state
         * @param scrollState The new state it changed to
         */
        fun doScrollStateChange(view: AbsListView?, scrollState: Int) {
            mHandler.removeCallbacks(this)
            mNewState = scrollState
            mHandler.postDelayed(this, SCROLL_CHANGE_DELAY.toLong())
        }

        override fun run() {
            mCurrentScrollState = mNewState
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG,
                        "new scroll state: $mNewState old state: $mPreviousScrollState")
            }
            // Fix the position after a scroll or a fling ends
            if (mNewState == OnScrollListener.SCROLL_STATE_IDLE &&
                    mPreviousScrollState != OnScrollListener.SCROLL_STATE_IDLE) {
                mPreviousScrollState = mNewState
                mAdapter?.updateFocusMonth(mCurrentMonthDisplayed)
            } else {
                mPreviousScrollState = mNewState
            }
        }
    }

    companion object {
        private const val TAG = "MonthFragment"
        private const val KEY_CURRENT_TIME = "current_time"

        // Affects when the month selection will change while scrolling up
        protected const val SCROLL_HYST_WEEKS = 2

        // How long the GoTo fling animation should last
        @JvmStatic protected val GOTO_SCROLL_DURATION = 500

        // How long to wait after receiving an onScrollStateChanged notification
        // before acting on it
        protected const val SCROLL_CHANGE_DELAY = 40

        // The number of days to display in each week
        const val DAYS_PER_WEEK = 7

        // The size of the month name displayed above the week list
        protected const val MINI_MONTH_NAME_TEXT_SIZE = 18
        var LIST_TOP_OFFSET = -1 // so that the top line will be under the separator
        private var mScale = 0f
    }

    init {
        goTo(initialTime, false, true, true)
        mHandler = Handler()
    }
}