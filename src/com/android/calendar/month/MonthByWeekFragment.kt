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

import android.app.Activity
import android.app.LoaderManager
import android.content.ContentUris
import android.content.CursorLoader
import android.content.Loader
import android.content.res.Resources
import android.database.Cursor
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.text.format.DateUtils
import android.text.format.Time
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AbsListView.OnScrollListener

import com.android.calendar.CalendarController
import com.android.calendar.CalendarController.EventInfo
import com.android.calendar.CalendarController.EventType
import com.android.calendar.CalendarController.ViewType
import com.android.calendar.Event
import com.android.calendar.R
import com.android.calendar.Utils

import java.util.ArrayList
import java.util.Calendar
import java.util.HashMap

class MonthByWeekFragment @JvmOverloads constructor(
    initialTime: Long = System.currentTimeMillis(),
    protected var mIsMiniMonth: Boolean = true
) : SimpleDayPickerFragment(initialTime), CalendarController.EventHandler,
        LoaderManager.LoaderCallbacks<Cursor?>, OnScrollListener, OnTouchListener {
    protected var mMinimumTwoMonthFlingVelocity = 0f
    protected var mHideDeclined = false
    protected var mFirstLoadedJulianDay = 0
    protected var mLastLoadedJulianDay = 0
    private var mLoader: CursorLoader? = null
    private var mEventUri: Uri? = null
    private val mDesiredDay: Time = Time()

    @Volatile
    private var mShouldLoad = true
    private var mUserScrolled = false
    private var mEventsLoadingDelay = 0
    private var mShowCalendarControls = false
    private var mIsDetached = false
    private val mTZUpdater: Runnable = object : Runnable {
        @Override
        override fun run() {
            val tz: String? = Utils.getTimeZone(mContext, this)
            mSelectedDay.timezone = tz
            mSelectedDay.normalize(true)
            mTempTime.timezone = tz
            mFirstDayOfMonth.timezone = tz
            mFirstDayOfMonth.normalize(true)
            mFirstVisibleDay.timezone = tz
            mFirstVisibleDay.normalize(true)
            if (mAdapter != null) {
                mAdapter?.refresh()
            }
        }
    }
    private val mUpdateLoader: Runnable = object : Runnable {
        @Override
        override fun run() {
            synchronized(this) {
                if (!mShouldLoad || mLoader == null) {
                    return
                }
                // Stop any previous loads while we update the uri
                stopLoader()

                // Start the loader again
                mEventUri = updateUri()
                mLoader?.setUri(mEventUri)
                mLoader?.startLoading()
                mLoader?.onContentChanged()
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Started loader with uri: $mEventUri")
                }
            }
        }
    }

    // Used to load the events when a delay is needed
    var mLoadingRunnable: Runnable = object : Runnable {
        @Override
        override fun run() {
            if (!mIsDetached) {
                mLoader = getLoaderManager().initLoader(
                        0, null,
                        this@MonthByWeekFragment
                ) as? CursorLoader
            }
        }
    }

    /**
     * Updates the uri used by the loader according to the current position of
     * the listview.
     *
     * @return The new Uri to use
     */
    private fun updateUri(): Uri {
        val child: SimpleWeekView? = mListView?.getChildAt(0) as? SimpleWeekView
        if (child != null) {
            val julianDay: Int = child.getFirstJulianDay()
            mFirstLoadedJulianDay = julianDay
        }
        // -1 to ensure we get all day events from any time zone
        mTempTime.setJulianDay(mFirstLoadedJulianDay - 1)
        val start: Long = mTempTime.toMillis(true)
        mLastLoadedJulianDay = mFirstLoadedJulianDay + (mNumWeeks + 2 * WEEKS_BUFFER) * 7
        // +1 to ensure we get all day events from any time zone
        mTempTime.setJulianDay(mLastLoadedJulianDay + 1)
        val end: Long = mTempTime.toMillis(true)

        // Create a new uri with the updated times
        val builder: Uri.Builder = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, end)
        return builder.build()
    }

    // Extract range of julian days from URI
    private fun updateLoadedDays() {
        val pathSegments = mEventUri?.getPathSegments()
        val size: Int = pathSegments?.size as Int
        if (size <= 2) {
            return
        }
        val first: Long = (pathSegments[size - 2])?.toLong() as Long
        val last: Long = (pathSegments[size - 1])?.toLong() as Long
        mTempTime.set(first)
        mFirstLoadedJulianDay = Time.getJulianDay(first, mTempTime.gmtoff)
        mTempTime.set(last)
        mLastLoadedJulianDay = Time.getJulianDay(last, mTempTime.gmtoff)
    }

    protected fun updateWhere(): String {
        // TODO fix selection/selection args after b/3206641 is fixed
        var where = WHERE_CALENDARS_VISIBLE
        if (mHideDeclined || !mShowDetailsInMonth) {
            where += (" AND " + Instances.SELF_ATTENDEE_STATUS.toString() + "!=" +
                    Attendees.ATTENDEE_STATUS_DECLINED)
        }
        return where
    }

    private fun stopLoader() {
        synchronized(mUpdateLoader) {
            mHandler.removeCallbacks(mUpdateLoader)
            if (mLoader != null) {
                mLoader?.stopLoading()
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Stopped loader from loading")
                }
            }
        }
    }

    @Override
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        mTZUpdater.run()
        if (mAdapter != null) {
            mAdapter?.setSelectedDay(mSelectedDay)
        }
        mIsDetached = false
        val viewConfig: ViewConfiguration = ViewConfiguration.get(activity)
        mMinimumTwoMonthFlingVelocity = viewConfig.getScaledMaximumFlingVelocity().toFloat() / 2f
        val res: Resources = activity.getResources()
        mShowCalendarControls = Utils.getConfigBool(activity, R.bool.show_calendar_controls)
        // Synchronized the loading time of the month's events with the animation of the
        // calendar controls.
        if (mShowCalendarControls) {
            mEventsLoadingDelay = res.getInteger(R.integer.calendar_controls_animation_time)
        }
        mShowDetailsInMonth = res.getBoolean(R.bool.show_details_in_month)
    }

    @Override
    override fun onDetach() {
        mIsDetached = true
        super.onDetach()
        if (mShowCalendarControls) {
            if (mListView != null) {
                mListView?.removeCallbacks(mLoadingRunnable)
            }
        }
    }

    @Override
    protected override fun setUpAdapter() {
        mFirstDayOfWeek = Utils.getFirstDayOfWeek(mContext)
        mShowWeekNumber = Utils.getShowWeekNumber(mContext)
        val weekParams = HashMap<String?, Int?>()
        weekParams.put(SimpleWeeksAdapter.WEEK_PARAMS_NUM_WEEKS, mNumWeeks)
        weekParams.put(SimpleWeeksAdapter.WEEK_PARAMS_SHOW_WEEK, if (mShowWeekNumber) 1 else 0)
        weekParams.put(SimpleWeeksAdapter.WEEK_PARAMS_WEEK_START, mFirstDayOfWeek)
        weekParams.put(MonthByWeekAdapter.WEEK_PARAMS_IS_MINI, if (mIsMiniMonth) 1 else 0)
        weekParams.put(
                SimpleWeeksAdapter.WEEK_PARAMS_JULIAN_DAY,
                Time.getJulianDay(mSelectedDay.toMillis(true), mSelectedDay.gmtoff)
        )
        weekParams.put(SimpleWeeksAdapter.WEEK_PARAMS_DAYS_PER_WEEK, mDaysPerWeek)
        if (mAdapter == null) {
            mAdapter = MonthByWeekAdapter(getActivity(), weekParams) as SimpleWeeksAdapter?
            mAdapter?.registerDataSetObserver(mObserver)
        } else {
            mAdapter?.updateParams(weekParams)
        }
        mAdapter?.notifyDataSetChanged()
    }

    @Override
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v: View
        v = if (mIsMiniMonth) {
            inflater.inflate(R.layout.month_by_week, container, false)
        } else {
            inflater.inflate(R.layout.full_month_by_week, container, false)
        }
        mDayNamesHeader = v.findViewById(R.id.day_names) as? ViewGroup
        return v
    }

    @Override
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        mListView?.setSelector(StateListDrawable())
        mListView?.setOnTouchListener(this)
        if (!mIsMiniMonth) {
            mListView?.setBackgroundColor(getResources().getColor(R.color.month_bgcolor))
        }

        // To get a smoother transition when showing this fragment, delay loading of events until
        // the fragment is expended fully and the calendar controls are gone.
        if (mShowCalendarControls) {
            mListView?.postDelayed(mLoadingRunnable, mEventsLoadingDelay.toLong())
        } else {
            mLoader = getLoaderManager().initLoader(0, null, this) as? CursorLoader
        }
        mAdapter?.setListView(mListView)
    }

    @Override
    protected override fun setUpHeader() {
        if (mIsMiniMonth) {
            super.setUpHeader()
            return
        }
        mDayLabels = arrayOfNulls<String>(7)
        for (i in Calendar.SUNDAY..Calendar.SATURDAY) {
            mDayLabels[i - Calendar.SUNDAY] = DateUtils.getDayOfWeekString(
                    i,
                    DateUtils.LENGTH_MEDIUM
            ).toUpperCase()
        }
    }

    // TODO
    @Override
    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor?>? {
        if (mIsMiniMonth) {
            return null
        }
        var loader: CursorLoader?
        synchronized(mUpdateLoader) {
            mFirstLoadedJulianDay =
                    (Time.getJulianDay(mSelectedDay.toMillis(true), mSelectedDay.gmtoff) -
                            mNumWeeks * 7 / 2)
            mEventUri = updateUri()
            val where = updateWhere()
            loader = CursorLoader(
                    getActivity(), mEventUri, Event.EVENT_PROJECTION, where,
                    null /* WHERE_CALENDARS_SELECTED_ARGS */, INSTANCES_SORT_ORDER
            )
            loader?.setUpdateThrottle(LOADER_THROTTLE_DELAY.toLong())
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Returning new loader with uri: $mEventUri")
        }
        return loader
    }

    @Override
    override fun doResumeUpdates() {
        mFirstDayOfWeek = Utils.getFirstDayOfWeek(mContext)
        mShowWeekNumber = Utils.getShowWeekNumber(mContext)
        val prevHideDeclined = mHideDeclined
        mHideDeclined = Utils.getHideDeclinedEvents(mContext)
        if (prevHideDeclined != mHideDeclined && mLoader != null) {
            mLoader?.setSelection(updateWhere())
        }
        mDaysPerWeek = Utils.getDaysPerWeek(mContext)
        updateHeader()
        mAdapter?.setSelectedDay(mSelectedDay)
        mTZUpdater.run()
        mTodayUpdater.run()
        goTo(mSelectedDay.toMillis(true), false, true, false)
    }

    @Override
    override fun onLoadFinished(loader: Loader<Cursor?>?, data: Cursor?) {
        synchronized(mUpdateLoader) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                        TAG,
                        "Found " + data?.getCount()?.toString() + " cursor entries for uri " +
                            mEventUri
                )
            }
            val cLoader: CursorLoader = loader as CursorLoader
            if (mEventUri == null) {
                mEventUri = cLoader.getUri()
                updateLoadedDays()
            }
            if (cLoader.getUri().compareTo(mEventUri) !== 0) {
                // We've started a new query since this loader ran so ignore the
                // result
                return
            }
            val events: ArrayList<Event?>? = ArrayList<Event?>()
            Event.buildEventsFromCursor(
                    events, data, mContext, mFirstLoadedJulianDay, mLastLoadedJulianDay
            )
            (mAdapter as MonthByWeekAdapter).setEvents(
                    mFirstLoadedJulianDay,
                    mLastLoadedJulianDay - mFirstLoadedJulianDay + 1, events as ArrayList<Event>?
            )
        }
    }

    @Override
    override fun onLoaderReset(loader: Loader<Cursor?>?) {
    }

    @Override
    override fun eventsChanged() {
        // TODO remove this after b/3387924 is resolved
        if (mLoader != null) {
            mLoader?.forceLoad()
        }
    }

    @get:Override override val supportedEventTypes: Long
        get() = EventType.GO_TO or EventType.EVENTS_CHANGED

    @Override
    override fun handleEvent(event: CalendarController.EventInfo?) {
        if (event?.eventType === EventType.GO_TO) {
            var animate = true
            if (mDaysPerWeek * mNumWeeks * 2 < Math.abs(
                            Time.getJulianDay(event.selectedTime?.toMillis(true) as Long,
                                    event.selectedTime?.gmtoff as Long) -
                                    Time.getJulianDay(mFirstVisibleDay.toMillis(true) as Long,
                                            mFirstVisibleDay.gmtoff as Long) -
                                    mDaysPerWeek * mNumWeeks / 2L
                    )
            ) {
                animate = false
            }
            mDesiredDay.set(event.selectedTime)
            mDesiredDay.normalize(true)
            val animateToday = event.extraLong and
                    CalendarController.EXTRA_GOTO_TODAY.toLong() != 0L
            val delayAnimation: Boolean =
                    goTo(event.selectedTime?.toMillis(true)?.toLong() as Long,
                        animate, true, false)
            if (animateToday) {
                // If we need to flash today start the animation after any
                // movement from listView has ended.
                mHandler.postDelayed(object : Runnable {
                    @Override
                    override fun run() {
                        (mAdapter as? MonthByWeekAdapter)?.animateToday()
                        mAdapter?.notifyDataSetChanged()
                    }
                }, if (delayAnimation) GOTO_SCROLL_DURATION.toLong() else 0L)
            }
        } else if (event?.eventType == EventType.EVENTS_CHANGED) {
            eventsChanged()
        }
    }

    @Override
    protected override fun setMonthDisplayed(time: Time, updateHighlight: Boolean) {
        super.setMonthDisplayed(time, updateHighlight)
        if (!mIsMiniMonth) {
            var useSelected = false
            if (time.year == mDesiredDay.year && time.month == mDesiredDay.month) {
                mSelectedDay.set(mDesiredDay)
                mAdapter?.setSelectedDay(mDesiredDay)
                useSelected = true
            } else {
                mSelectedDay.set(time)
                mAdapter?.setSelectedDay(time)
            }
            val controller: CalendarController? = CalendarController.getInstance(mContext)
            if (mSelectedDay.minute >= 30) {
                mSelectedDay.minute = 30
            } else {
                mSelectedDay.minute = 0
            }
            val newTime: Long = mSelectedDay.normalize(true)
            if (newTime != controller?.time && mUserScrolled) {
                val offset: Long =
                        if (useSelected) 0 else DateUtils.WEEK_IN_MILLIS * mNumWeeks / 3.toLong()
                controller?.time = (newTime + offset)
            }
            controller?.sendEvent(
                    this as Object?, EventType.UPDATE_TITLE, time, time, time, -1,
                    ViewType.CURRENT, DateUtils.FORMAT_SHOW_DATE.toLong() or
                    DateUtils.FORMAT_NO_MONTH_DAY.toLong() or
                    DateUtils.FORMAT_SHOW_YEAR.toLong(), null, null
            )
        }
    }

    @Override
    override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
        synchronized(mUpdateLoader) {
            if (scrollState != OnScrollListener.SCROLL_STATE_IDLE) {
                mShouldLoad = false
                stopLoader()
                mDesiredDay.setToNow()
            } else {
                mHandler.removeCallbacks(mUpdateLoader)
                mShouldLoad = true
                mHandler.postDelayed(mUpdateLoader, LOADER_DELAY.toLong())
            }
        }
        if (scrollState == OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
            mUserScrolled = true
        }
        mScrollStateChangedRunnable.doScrollStateChange(view, scrollState)
    }

    @Override
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        mDesiredDay.setToNow()
        return false
    }

    companion object {
        private const val TAG = "MonthFragment"
        private const val TAG_EVENT_DIALOG = "event_dialog"

        // Selection and selection args for adding event queries
        private val WHERE_CALENDARS_VISIBLE: String = Calendars.VISIBLE.toString() + "=1"
        private val INSTANCES_SORT_ORDER: String = (Instances.START_DAY.toString() + "," +
                Instances.START_MINUTE + "," + Instances.TITLE)
        protected var mShowDetailsInMonth = false
        private const val WEEKS_BUFFER = 1

        // How long to wait after scroll stops before starting the loader
        // Using scroll duration because scroll state changes don't update
        // correctly when a scroll is triggered programmatically.
        private const val LOADER_DELAY = 200

        // The minimum time between requeries of the data if the db is
        // changing
        private const val LOADER_THROTTLE_DELAY = 500
    }
}