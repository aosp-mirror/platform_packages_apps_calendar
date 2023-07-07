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

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Message
import android.text.format.Time
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView.LayoutParams
import com.android.calendar.CalendarController
import com.android.calendar.CalendarController.EventType
import com.android.calendar.CalendarController.ViewType
import com.android.calendar.Event
import com.android.calendar.R
import com.android.calendar.Utils
import java.util.ArrayList
import java.util.HashMap

class MonthByWeekAdapter(context: Context?, params: HashMap<String?, Int?>) :
    SimpleWeeksAdapter(context as Context, params) {
    protected var mController: CalendarController? = null
    protected var mHomeTimeZone: String? = null
    protected var mTempTime: Time? = null
    protected var mToday: Time? = null
    protected var mFirstJulianDay = 0
    protected var mQueryDays = 0
    protected var mIsMiniMonth = true
    protected var mOrientation: Int = Configuration.ORIENTATION_LANDSCAPE
    private val mShowAgendaWithMonth: Boolean
    protected var mEventDayList: ArrayList<ArrayList<Event>> = ArrayList<ArrayList<Event>>()
    protected var mEvents: ArrayList<Event>? = null
    private var mAnimateToday = false
    private var mAnimateTime: Long = 0
    private val mEventDialogHandler: Handler? = null
    var mClickedView: MonthWeekEventsView? = null
    var mSingleTapUpView: MonthWeekEventsView? = null
    var mLongClickedView: MonthWeekEventsView? = null
    var mClickedXLocation = 0f // Used to find which day was clicked
    var mClickTime: Long = 0 // Used to calculate minimum click animation time

    fun animateToday() {
        mAnimateToday = true
        mAnimateTime = System.currentTimeMillis()
    }

    @Override
    protected override fun init() {
        super.init()
        mGestureDetector = GestureDetector(mContext, CalendarGestureListener())
        mController = CalendarController.getInstance(mContext)
        mHomeTimeZone = Utils.getTimeZone(mContext, null)
        mSelectedDay?.switchTimezone(mHomeTimeZone)
        mToday = Time(mHomeTimeZone)
        mToday?.setToNow()
        mTempTime = Time(mHomeTimeZone)
    }

    private fun updateTimeZones() {
        mSelectedDay!!.timezone = mHomeTimeZone
        mSelectedDay?.normalize(true)
        mToday!!.timezone = mHomeTimeZone
        mToday?.setToNow()
        mTempTime?.switchTimezone(mHomeTimeZone)
    }

    @Override
    override fun setSelectedDay(selectedTime: Time?) {
        mSelectedDay?.set(selectedTime)
        val millis: Long = mSelectedDay!!.normalize(true)
        mSelectedWeek = Utils.getWeeksSinceEpochFromJulianDay(
            Time.getJulianDay(millis, mSelectedDay!!.gmtoff), mFirstDayOfWeek
        )
        notifyDataSetChanged()
    }

    fun setEvents(firstJulianDay: Int, numDays: Int, events: ArrayList<Event>?) {
        if (mIsMiniMonth) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(
                    TAG, "Attempted to set events for mini view. Events only supported in full" +
                        " view."
                )
            }
            return
        }
        mEvents = events
        mFirstJulianDay = firstJulianDay
        mQueryDays = numDays
        // Create a new list, this is necessary since the weeks are referencing
        // pieces of the old list
        val eventDayList: ArrayList<ArrayList<Event>> = ArrayList<ArrayList<Event>>()
        for (i in 0 until numDays) {
            eventDayList.add(ArrayList<Event>())
        }
        if (events == null || events.size == 0) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "No events. Returning early--go schedule something fun.")
            }
            mEventDayList = eventDayList
            refresh()
            return
        }

        // Compute the new set of days with events
        for (event in events) {
            var startDay: Int = event.startDay - mFirstJulianDay
            var endDay: Int = event.endDay - mFirstJulianDay + 1
            if (startDay < numDays || endDay >= 0) {
                if (startDay < 0) {
                    startDay = 0
                }
                if (startDay > numDays) {
                    continue
                }
                if (endDay < 0) {
                    continue
                }
                if (endDay > numDays) {
                    endDay = numDays
                }
                for (j in startDay until endDay) {
                    eventDayList.get(j).add(event)
                }
            }
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Processed " + events.size.toString() + " events.")
        }
        mEventDayList = eventDayList
        refresh()
    }

    @SuppressWarnings("unchecked")
    @Override
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        if (mIsMiniMonth) {
            return super.getView(position, convertView, parent)
        }
        var v: MonthWeekEventsView
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        var drawingParams: HashMap<String?, Int?>? = null
        var isAnimatingToday = false
        if (convertView != null) {
            v = convertView as MonthWeekEventsView
            // Checking updateToday uses the current params instead of the new
            // params, so this is assuming the view is relatively stable
            if (mAnimateToday && v.updateToday(mSelectedDay!!.timezone)) {
                val currentTime: Long = System.currentTimeMillis()
                // If it's been too long since we tried to start the animation
                // don't show it. This can happen if the user stops a scroll
                // before reaching today.
                if (currentTime - mAnimateTime > ANIMATE_TODAY_TIMEOUT) {
                    mAnimateToday = false
                    mAnimateTime = 0
                } else {
                    isAnimatingToday = true
                    // There is a bug that causes invalidates to not work some
                    // of the time unless we recreate the view.
                    v = MonthWeekEventsView(mContext)
                }
            } else {
                drawingParams = v.getTag() as HashMap<String?, Int?>
            }
        } else {
            v = MonthWeekEventsView(mContext)
        }
        if (drawingParams == null) {
            drawingParams = HashMap<String?, Int?>()
        }
        drawingParams.clear()
        v.setLayoutParams(params)
        v.setClickable(true)
        v.setOnTouchListener(this)
        var selectedDay = -1
        if (mSelectedWeek === position) {
            selectedDay = mSelectedDay!!.weekDay
        }
        drawingParams.put(
            SimpleWeekView.VIEW_PARAMS_HEIGHT,
            (parent.getHeight() + parent.getTop()) / mNumWeeks
        )
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_SELECTED_DAY, selectedDay)
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_SHOW_WK_NUM, if (mShowWeekNumber) 1 else 0)
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_WEEK_START, mFirstDayOfWeek)
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_NUM_DAYS, mDaysPerWeek)
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_WEEK, position)
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_FOCUS_MONTH, mFocusMonth)
        drawingParams.put(MonthWeekEventsView.VIEW_PARAMS_ORIENTATION, mOrientation)
        if (isAnimatingToday) {
            drawingParams.put(MonthWeekEventsView.VIEW_PARAMS_ANIMATE_TODAY, 1)
            mAnimateToday = false
        }
        v.setWeekParams(drawingParams, mSelectedDay!!.timezone)
        return v
    }

    @Override
    internal override fun refresh() {
        mFirstDayOfWeek = Utils.getFirstDayOfWeek(mContext)
        mShowWeekNumber = Utils.getShowWeekNumber(mContext)
        mHomeTimeZone = Utils.getTimeZone(mContext, null)
        mOrientation = mContext.getResources().getConfiguration().orientation
        updateTimeZones()
        notifyDataSetChanged()
    }

    @Override
    protected override fun onDayTapped(day: Time) {
        setDayParameters(day)
        if (mShowAgendaWithMonth || mIsMiniMonth) {
            // If agenda view is visible with month view , refresh the views
            // with the selected day's info
            mController?.sendEvent(
                mContext as Object?, EventType.GO_TO, day, day, -1,
                ViewType.CURRENT, CalendarController.EXTRA_GOTO_DATE, null, null
            )
        } else {
            // Else , switch to the detailed view
            mController?.sendEvent(
                mContext as Object?, EventType.GO_TO, day, day, -1,
                ViewType.DETAIL, CalendarController.EXTRA_GOTO_DATE
                    or CalendarController.EXTRA_GOTO_BACK_TO_PREVIOUS, null, null
            )
        }
    }

    private fun setDayParameters(day: Time) {
        day.timezone = mHomeTimeZone
        val currTime = Time(mHomeTimeZone)
        currTime.set(mController!!.time as Long)
        day.hour = currTime.hour
        day.minute = currTime.minute
        day.allDay = false
        day.normalize(true)
    }

    @Override
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (v !is MonthWeekEventsView) {
            return super.onTouch(v, event)
        }
        val action: Int = event.getAction()

        // Event was tapped - switch to the detailed view making sure the click animation
        // is done first.
        if (mGestureDetector!!.onTouchEvent(event)) {
            mSingleTapUpView = v as MonthWeekEventsView?
            val delay: Long = System.currentTimeMillis() - mClickTime
            // Make sure the animation is visible for at least mOnTapDelay - mOnDownDelay ms
            mListView?.postDelayed(
                mDoSingleTapUp,
                if (delay > mTotalClickDelay) 0 else mTotalClickDelay - delay
            )
            return true
        } else {
            // Animate a click - on down: show the selected day in the "clicked" color.
            // On Up/scroll/move/cancel: hide the "clicked" color.
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    mClickedView = v as MonthWeekEventsView
                    mClickedXLocation = event.getX()
                    mClickTime = System.currentTimeMillis()
                    mListView?.postDelayed(mDoClick, mOnDownDelay.toLong())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_SCROLL, MotionEvent.ACTION_CANCEL ->
                    clearClickedView(
                    v as MonthWeekEventsView?
                )
                MotionEvent.ACTION_MOVE -> // No need to cancel on vertical movement,
                    // ACTION_SCROLL will do that.
                    if (Math.abs(event.getX() - mClickedXLocation) > mMovedPixelToCancel) {
                        clearClickedView(v as MonthWeekEventsView?)
                    }
                else -> {
                }
            }
        }
        // Do not tell the frameworks we consumed the touch action so that fling actions can be
        // processed by the fragment.
        return false
    }

    /**
     * This is here so we can identify events and process them
     */
    protected inner class CalendarGestureListener : GestureDetector.SimpleOnGestureListener() {
        @Override
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            return true
        }

        @Override
        override fun onLongPress(e: MotionEvent) {
            if (mLongClickedView != null) {
                val day: Time? = mLongClickedView?.getDayFromLocation(mClickedXLocation)
                if (day != null) {
                    mLongClickedView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val message = Message()
                    message.obj = day
                }
                mLongClickedView?.clearClickedDay()
                mLongClickedView = null
            }
        }
    }

    // Clear the visual cues of the click animation and related running code.
    private fun clearClickedView(v: MonthWeekEventsView?) {
        mListView?.removeCallbacks(mDoClick)
        synchronized(v as Any) { v.clearClickedDay() }
        mClickedView = null
    }

    // Perform the tap animation in a runnable to allow a delay before showing the tap color.
    // This is done to prevent a click animation when a fling is done.
    private val mDoClick: Runnable = object : Runnable {
        @Override
        override fun run() {
            if (mClickedView != null) {
                synchronized(mClickedView as MonthWeekEventsView) {
                    mClickedView?.setClickedDay(mClickedXLocation) }
                mLongClickedView = mClickedView
                mClickedView = null
                // This is a workaround , sometimes the top item on the listview doesn't refresh on
                // invalidate, so this forces a re-draw.
                mListView?.invalidate()
            }
        }
    }

    // Performs the single tap operation: go to the tapped day.
    // This is done in a runnable to allow the click animation to finish before switching views
    private val mDoSingleTapUp: Runnable = object : Runnable {
        @Override
        override fun run() {
            if (mSingleTapUpView != null) {
                val day: Time? = mSingleTapUpView?.getDayFromLocation(mClickedXLocation)
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "Touched day at Row=" + mSingleTapUpView?.mWeek?.toString() +
                            " day=" + day?.toString()
                    )
                }
                if (day != null) {
                    onDayTapped(day)
                }
                clearClickedView(mSingleTapUpView)
                mSingleTapUpView = null
            }
        }
    }

    companion object {
        private const val TAG = "MonthByWeekAdapter"
        const val WEEK_PARAMS_IS_MINI = "mini_month"
        protected var DEFAULT_QUERY_DAYS = 7 * 8 // 8 weeks
        private const val ANIMATE_TODAY_TIMEOUT: Long = 1000

        // Used to insure minimal time for seeing the click animation before switching views
        private const val mOnTapDelay = 100

        // Minimal time for a down touch action before stating the click animation, this ensures
        // that there is no click animation on flings
        private var mOnDownDelay: Int = 0
        private var mTotalClickDelay: Int = 0

        // Minimal distance to move the finger in order to cancel the click animation
        private var mMovedPixelToCancel: Float = 0f
    }

    init {
        if (params.containsKey(WEEK_PARAMS_IS_MINI)) {
            mIsMiniMonth = params.get(WEEK_PARAMS_IS_MINI) != 0
        }
        mShowAgendaWithMonth = Utils.getConfigBool(context as Context,
            R.bool.show_agenda_with_month)
        val vc: ViewConfiguration = ViewConfiguration.get(context)
        mOnDownDelay = ViewConfiguration.getTapTimeout()
        mMovedPixelToCancel = vc.getScaledTouchSlop().toFloat()
        mTotalClickDelay = mOnDownDelay + mOnTapDelay
    }
}