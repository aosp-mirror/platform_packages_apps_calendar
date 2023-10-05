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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Paint.Style
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.text.Layout.Alignment
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Time
import android.text.style.StyleSpan
import android.util.Log
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.Interpolator
import android.view.animation.TranslateAnimation
import android.widget.EdgeEffect
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.ViewSwitcher
import com.android.calendar.CalendarController.EventType
import com.android.calendar.CalendarController.ViewType
import java.util.ArrayList
import java.util.Arrays
import java.util.Calendar
import java.util.Formatter
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * View for multi-day view. So far only 1 and 7 day have been tested.
 */
class DayView(
    context: Context?,
    controller: CalendarController?,
    viewSwitcher: ViewSwitcher?,
    eventLoader: EventLoader?,
    numDays: Int
) : View(context), View.OnCreateContextMenuListener, ScaleGestureDetector.OnScaleGestureListener,
    View.OnClickListener, View.OnLongClickListener {
    private var mOnFlingCalled = false
    private var mStartingScroll = false
    protected var mPaused = true
    private var mHandler: Handler? = null

    /**
     * ID of the last event which was displayed with the toast popup.
     *
     * This is used to prevent popping up multiple quick views for the same event, especially
     * during calendar syncs. This becomes valid when an event is selected, either by default
     * on starting calendar or by scrolling to an event. It becomes invalid when the user
     * explicitly scrolls to an empty time slot, changes views, or deletes the event.
     */
    private var mLastPopupEventID: Long
    protected var mContext: Context? = null
    private val mContinueScroll: ContinueScroll = ContinueScroll()

    // Make this visible within the package for more informative debugging
    var mBaseDate: Time? = null
    private var mCurrentTime: Time? = null
    private val mUpdateCurrentTime: UpdateCurrentTime = UpdateCurrentTime()
    private var mTodayJulianDay = 0
    private val mBold: Typeface = Typeface.DEFAULT_BOLD
    private var mFirstJulianDay = 0
    private var mLoadedFirstJulianDay = -1
    private var mLastJulianDay = 0
    private var mMonthLength = 0
    private var mFirstVisibleDate = 0
    private var mFirstVisibleDayOfWeek = 0
    private var mEarliestStartHour: IntArray? = null // indexed by the week day offset
    private var mHasAllDayEvent: BooleanArray? = null // indexed by the week day offset
    private var mEventCountTemplate: String? = null
    private var mClickedEvent: Event? = null // The event the user clicked on
    private var mSavedClickedEvent: Event? = null
    private var mClickedYLocation = 0
    private var mDownTouchTime: Long = 0
    private var mEventsAlpha = 255
    private var mEventsCrossFadeAnimation: ObjectAnimator? = null
    private val mTZUpdater: Runnable = object : Runnable {
        @Override
        override fun run() {
            val tz: String? = Utils.getTimeZone(mContext, this)
            mBaseDate!!.timezone = tz
            mBaseDate?.normalize(true)
            mCurrentTime?.switchTimezone(tz)
            invalidate()
        }
    }

    // Sets the "clicked" color from the clicked event
    private val mSetClick: Runnable = object : Runnable {
        @Override
        override fun run() {
            mClickedEvent = mSavedClickedEvent
            mSavedClickedEvent = null
            this@DayView.invalidate()
        }
    }

    // Clears the "clicked" color from the clicked event and launch the event
    private val mClearClick: Runnable = object : Runnable {
        @Override
        override fun run() {
            if (mClickedEvent != null) {
                mController.sendEventRelatedEvent(
                    this as Object?, EventType.VIEW_EVENT, mClickedEvent!!.id,
                    mClickedEvent!!.startMillis, mClickedEvent!!.endMillis,
                    this@DayView.getWidth() / 2, mClickedYLocation,
                    selectedTimeInMillis
                )
            }
            mClickedEvent = null
            this@DayView.invalidate()
        }
    }
    private val mTodayAnimatorListener: TodayAnimatorListener = TodayAnimatorListener()

    internal inner class TodayAnimatorListener : AnimatorListenerAdapter() {
        @Volatile
        private var mAnimator: Animator? = null

        @Volatile
        private var mFadingIn = false
        @Override
        override fun onAnimationEnd(animation: Animator) {
            synchronized(this) {
                if (mAnimator !== animation) {
                    animation.removeAllListeners()
                    animation.cancel()
                    return
                }
                if (mFadingIn) {
                    if (mTodayAnimator != null) {
                        mTodayAnimator?.removeAllListeners()
                        mTodayAnimator?.cancel()
                    }
                    mTodayAnimator = ObjectAnimator
                        .ofInt(this@DayView, "animateTodayAlpha", 255, 0)
                    mAnimator = mTodayAnimator
                    mFadingIn = false
                    mTodayAnimator?.addListener(this)
                    mTodayAnimator?.setDuration(600)
                    mTodayAnimator?.start()
                } else {
                    mAnimateToday = false
                    mAnimateTodayAlpha = 0
                    mAnimator?.removeAllListeners()
                    mAnimator = null
                    mTodayAnimator = null
                    invalidate()
                }
            }
        }

        fun setAnimator(animation: Animator?) {
            mAnimator = animation
        }

        fun setFadingIn(fadingIn: Boolean) {
            mFadingIn = fadingIn
        }
    }

    var mAnimatorListener: AnimatorListenerAdapter = object : AnimatorListenerAdapter() {
        @Override
        override fun onAnimationStart(animation: Animator) {
            mScrolling = true
        }

        @Override
        override fun onAnimationCancel(animation: Animator) {
            mScrolling = false
        }

        @Override
        override fun onAnimationEnd(animation: Animator) {
            mScrolling = false
            resetSelectedHour()
            invalidate()
        }
    }

    /**
     * This variable helps to avoid unnecessarily reloading events by keeping
     * track of the start millis parameter used for the most recent loading
     * of events.  If the next reload matches this, then the events are not
     * reloaded.  To force a reload, set this to zero (this is set to zero
     * in the method clearCachedEvents()).
     */
    private var mLastReloadMillis: Long = 0
    private var mEvents: ArrayList<Event> = ArrayList<Event>()
    private var mAllDayEvents: ArrayList<Event>? = ArrayList<Event>()
    private var mLayouts: Array<StaticLayout?>? = null
    private var mAllDayLayouts: Array<StaticLayout?>? = null
    private var mSelectionDay = 0 // Julian day
    private var mSelectionHour = 0
    var mSelectionAllday = false

    // Current selection info for accessibility
    private var mSelectionDayForAccessibility = 0 // Julian day
    private var mSelectionHourForAccessibility = 0
    private var mSelectedEventForAccessibility: Event? = null

    // Last selection info for accessibility
    private var mLastSelectionDayForAccessibility = 0
    private var mLastSelectionHourForAccessibility = 0
    private var mLastSelectedEventForAccessibility: Event? = null

    /** Width of a day or non-conflicting event  */
    private var mCellWidth = 0

    // Pre-allocate these objects and re-use them
    private val mRect: Rect = Rect()
    private val mDestRect: Rect = Rect()
    private val mSelectionRect: Rect = Rect()

    // This encloses the more allDay events icon
    private val mExpandAllDayRect: Rect = Rect()

    // TODO Clean up paint usage
    private val mPaint: Paint = Paint()
    private val mEventTextPaint: Paint = Paint()
    private val mSelectionPaint: Paint = Paint()
    private var mLines: FloatArray = emptyArray<Float>().toFloatArray()
    private var mFirstDayOfWeek = 0 // First day of the week
    private var mPopup: PopupWindow? = null
    private var mPopupView: View? = null
    private val mDismissPopup: DismissPopup = DismissPopup()
    private var mRemeasure = true
    private val mEventLoader: EventLoader
    protected val mEventGeometry: EventGeometry
    private var mAnimationDistance = 0f
    private var mViewStartX = 0
    private var mViewStartY = 0
    private var mMaxViewStartY = 0
    private var mViewHeight = 0
    private var mViewWidth = 0
    private var mGridAreaHeight = -1
    private var mScrollStartY = 0
    private var mPreviousDirection = 0

    /**
     * Vertical distance or span between the two touch points at the start of a
     * scaling gesture
     */
    private var mStartingSpanY = 0f

    /** Height of 1 hour in pixels at the start of a scaling gesture  */
    private var mCellHeightBeforeScaleGesture = 0

    /** The hour at the center two touch points  */
    private var mGestureCenterHour = 0f
    private var mRecalCenterHour = false

    /**
     * Flag to decide whether to handle the up event. Cases where up events
     * should be ignored are 1) right after a scale gesture and 2) finger was
     * down before app launch
     */
    private var mHandleActionUp = true
    private var mHoursTextHeight = 0

    /**
     * The height of the area used for allday events
     */
    private var mAlldayHeight = 0

    /**
     * The height of the allday event area used during animation
     */
    private var mAnimateDayHeight = 0

    /**
     * The height of an individual allday event during animation
     */
    private var mAnimateDayEventHeight = MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT.toInt()

    /**
     * Max of all day events in a given day in this view.
     */
    private var mMaxAlldayEvents = 0

    /**
     * A count of the number of allday events that were not drawn for each day
     */
    private var mSkippedAlldayEvents: IntArray? = null

    /**
     * The number of allDay events at which point we start hiding allDay events.
     */
    private var mMaxUnexpandedAlldayEventCount = 4
    protected var mNumDays = 7
    private var mNumHours = 10

    /** Width of the time line (list of hours) to the left.  */
    private var mHoursWidth = 0
    private var mDateStrWidth = 0

    /** Top of the scrollable region i.e. below date labels and all day events  */
    private var mFirstCell = 0

    /** First fully visible hour  */
    private var mFirstHour = -1

    /** Distance between the mFirstCell and the top of first fully visible hour.  */
    private var mFirstHourOffset = 0
    private var mHourStrs: Array<String>? = null
    private var mDayStrs: Array<String?>? = null
    private var mDayStrs2Letter: Array<String?>? = null
    private var mIs24HourFormat = false
    private val mSelectedEvents: ArrayList<Event> = ArrayList<Event>()
    private var mComputeSelectedEvents = false
    private var mUpdateToast = false
    private var mSelectedEvent: Event? = null
    private var mPrevSelectedEvent: Event? = null
    private val mPrevBox: Rect = Rect()
    protected val mResources: Resources
    protected val mCurrentTimeLine: Drawable
    protected val mCurrentTimeAnimateLine: Drawable
    protected val mTodayHeaderDrawable: Drawable
    protected val mExpandAlldayDrawable: Drawable
    protected val mCollapseAlldayDrawable: Drawable
    protected var mAcceptedOrTentativeEventBoxDrawable: Drawable
    private var mAmString: String? = null
    private var mPmString: String? = null
    var mScaleGestureDetector: ScaleGestureDetector
    private var mTouchMode = TOUCH_MODE_INITIAL_STATE
    private var mSelectionMode = SELECTION_HIDDEN
    private var mScrolling = false

    // Pixels scrolled
    private var mInitialScrollX = 0f
    private var mInitialScrollY = 0f
    private var mAnimateToday = false
    private var mAnimateTodayAlpha = 0

    // Animates the height of the allday region
    var mAlldayAnimator: ObjectAnimator? = null

    // Animates the height of events in the allday region
    var mAlldayEventAnimator: ObjectAnimator? = null

    // Animates the transparency of the more events text
    var mMoreAlldayEventsAnimator: ObjectAnimator? = null

    // Animates the current time marker when Today is pressed
    var mTodayAnimator: ObjectAnimator? = null

    // whether or not an event is stopping because it was cancelled
    private var mCancellingAnimations = false

    // tracks whether a touch originated in the allday area
    private var mTouchStartedInAlldayArea = false
    private val mController: CalendarController
    private val mViewSwitcher: ViewSwitcher
    private val mGestureDetector: GestureDetector
    private val mScroller: OverScroller
    private val mEdgeEffectTop: EdgeEffect
    private val mEdgeEffectBottom: EdgeEffect
    private var mCallEdgeEffectOnAbsorb = false
    private val OVERFLING_DISTANCE: Int
    private var mLastVelocity = 0f
    private val mHScrollInterpolator: ScrollInterpolator
    private var mAccessibilityMgr: AccessibilityManager? = null
    private var mIsAccessibilityEnabled = false
    private var mTouchExplorationEnabled = false
    private val mNewEventHintString: String
    @Override
    protected override fun onAttachedToWindow() {
        if (mHandler == null) {
            mHandler = getHandler()
            mHandler?.post(mUpdateCurrentTime)
        }
    }

    private fun init(context: Context) {
        setFocusable(true)

        // Allow focus in touch mode so that we can do keyboard shortcuts
        // even after we've entered touch mode.
        setFocusableInTouchMode(true)
        setClickable(true)
        setOnCreateContextMenuListener(this)
        mFirstDayOfWeek = Utils.getFirstDayOfWeek(context)
        mCurrentTime = Time(Utils.getTimeZone(context, mTZUpdater))
        val currentTime: Long = System.currentTimeMillis()
        mCurrentTime?.set(currentTime)
        mTodayJulianDay = Time.getJulianDay(currentTime, mCurrentTime!!.gmtoff)
        mWeek_saturdayColor = mResources.getColor(R.color.week_saturday)
        mWeek_sundayColor = mResources.getColor(R.color.week_sunday)
        mCalendarDateBannerTextColor = mResources.getColor(R.color.calendar_date_banner_text_color)
        mFutureBgColorRes = mResources.getColor(R.color.calendar_future_bg_color)
        mBgColor = mResources.getColor(R.color.calendar_hour_background)
        mCalendarAmPmLabel = mResources.getColor(R.color.calendar_ampm_label)
        mCalendarGridAreaSelected = mResources.getColor(R.color.calendar_grid_area_selected)
        mCalendarGridLineInnerHorizontalColor = mResources
            .getColor(R.color.calendar_grid_line_inner_horizontal_color)
        mCalendarGridLineInnerVerticalColor = mResources
            .getColor(R.color.calendar_grid_line_inner_vertical_color)
        mCalendarHourLabelColor = mResources.getColor(R.color.calendar_hour_label)
        mEventTextColor = mResources.getColor(R.color.calendar_event_text_color)
        mMoreEventsTextColor = mResources.getColor(R.color.month_event_other_color)
        mEventTextPaint.setTextSize(EVENT_TEXT_FONT_SIZE)
        mEventTextPaint.setTextAlign(Paint.Align.LEFT)
        mEventTextPaint.setAntiAlias(true)
        val gridLineColor: Int = mResources.getColor(R.color.calendar_grid_line_highlight_color)
        var p: Paint = mSelectionPaint
        p.setColor(gridLineColor)
        p.setStyle(Style.FILL)
        p.setAntiAlias(false)
        p = mPaint
        p.setAntiAlias(true)

        // Allocate space for 2 weeks worth of weekday names so that we can
        // easily start the week display at any week day.
        mDayStrs = arrayOfNulls(14)

        // Also create an array of 2-letter abbreviations.
        mDayStrs2Letter = arrayOfNulls(14)
        for (i in Calendar.SUNDAY..Calendar.SATURDAY) {
            val index: Int = i - Calendar.SUNDAY
            // e.g. Tue for Tuesday
            mDayStrs!![index] = DateUtils.getDayOfWeekString(i, DateUtils.LENGTH_MEDIUM)
                .toUpperCase()
            mDayStrs!![index + 7] = mDayStrs!![index]
            // e.g. Tu for Tuesday
            mDayStrs2Letter!![index] = DateUtils.getDayOfWeekString(i, DateUtils.LENGTH_SHORT)
                .toUpperCase()

            // If we don't have 2-letter day strings, fall back to 1-letter.
            if (mDayStrs2Letter!![index]!!.equals(mDayStrs!![index])) {
                mDayStrs2Letter!![index] = DateUtils.getDayOfWeekString(i,
                DateUtils.LENGTH_SHORTEST)
            }
            mDayStrs2Letter!![index + 7] = mDayStrs2Letter!![index]
        }

        // Figure out how much space we need for the 3-letter abbrev names
        // in the worst case.
        p.setTextSize(DATE_HEADER_FONT_SIZE)
        p.setTypeface(mBold)
        val dateStrs = arrayOf<String?>(" 28", " 30")
        mDateStrWidth = computeMaxStringWidth(0, dateStrs, p)
        p.setTextSize(DAY_HEADER_FONT_SIZE)
        mDateStrWidth += computeMaxStringWidth(0, mDayStrs as Array<String?>, p)
        p.setTextSize(HOURS_TEXT_SIZE)
        p.setTypeface(null)
        handleOnResume()
        mAmString = DateUtils.getAMPMString(Calendar.AM).toUpperCase()
        mPmString = DateUtils.getAMPMString(Calendar.PM).toUpperCase()
        val ampm = arrayOf(mAmString, mPmString)
        p.setTextSize(AMPM_TEXT_SIZE)
        mHoursWidth = Math.max(
            HOURS_MARGIN, computeMaxStringWidth(mHoursWidth, ampm, p) +
                HOURS_RIGHT_MARGIN
        )
        mHoursWidth = Math.max(MIN_HOURS_WIDTH, mHoursWidth)
        val inflater: LayoutInflater
        inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mPopupView = inflater.inflate(R.layout.bubble_event, null)
        mPopupView?.setLayoutParams(
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        mPopup = PopupWindow(context)
        mPopup?.setContentView(mPopupView)
        val dialogTheme: Resources.Theme = getResources().newTheme()
        dialogTheme.applyStyle(android.R.style.Theme_Dialog, true)
        val ta: TypedArray = dialogTheme.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.windowBackground
            )
        )
        mPopup?.setBackgroundDrawable(ta.getDrawable(0))
        ta.recycle()

        // Enable touching the popup window
        mPopupView?.setOnClickListener(this)
        // Catch long clicks for creating a new event
        setOnLongClickListener(this)
        mBaseDate = Time(Utils.getTimeZone(context, mTZUpdater))
        val millis: Long = System.currentTimeMillis()
        mBaseDate?.set(millis)
        mEarliestStartHour = IntArray(mNumDays)
        mHasAllDayEvent = BooleanArray(mNumDays)

        // mLines is the array of points used with Canvas.drawLines() in
        // drawGridBackground() and drawAllDayEvents().  Its size depends
        // on the max number of lines that can ever be drawn by any single
        // drawLines() call in either of those methods.
        val maxGridLines = (24 + 1 + // max horizontal lines we might draw
            (mNumDays + 1)) // max vertical lines we might draw
        mLines = FloatArray(maxGridLines * 4)
    }

    /**
     * This is called when the popup window is pressed.
     */
    override fun onClick(v: View) {
        if (v === mPopupView) {
            // Pretend it was a trackball click because that will always
            // jump to the "View event" screen.
            switchViews(true /* trackball */)
        }
    }

    fun handleOnResume() {
        initAccessibilityVariables()
        if (Utils.getSharedPreference(mContext, OtherPreferences.KEY_OTHER_1, false)) {
            mFutureBgColor = 0
        } else {
            mFutureBgColor = mFutureBgColorRes
        }
        mIs24HourFormat = DateFormat.is24HourFormat(mContext)
        mHourStrs = if (mIs24HourFormat) CalendarData.s24Hours else CalendarData.s12HoursNoAmPm
        mFirstDayOfWeek = Utils.getFirstDayOfWeek(mContext)
        mLastSelectionDayForAccessibility = 0
        mLastSelectionHourForAccessibility = 0
        mLastSelectedEventForAccessibility = null
        mSelectionMode = SELECTION_HIDDEN
    }

    private fun initAccessibilityVariables() {
        mAccessibilityMgr = mContext
            ?.getSystemService(Service.ACCESSIBILITY_SERVICE) as AccessibilityManager
        mIsAccessibilityEnabled = mAccessibilityMgr != null && mAccessibilityMgr!!.isEnabled()
        mTouchExplorationEnabled = isTouchExplorationEnabled
    } /* ignore isDst */ // We ignore the "isDst" field because we want normalize() to figure
    // out the correct DST value and not adjust the selected time based
    // on the current setting of DST.
    /**
     * Returns the start of the selected time in milliseconds since the epoch.
     *
     * @return selected time in UTC milliseconds since the epoch.
     */
    val selectedTimeInMillis: Long
        get() {
            val time = Time(mBaseDate)
            time.setJulianDay(mSelectionDay)
            time.hour = mSelectionHour

            // We ignore the "isDst" field because we want normalize() to figure
            // out the correct DST value and not adjust the selected time based
            // on the current setting of DST.
            return time.normalize(true /* ignore isDst */)
        } /* ignore isDst */

    // We ignore the "isDst" field because we want normalize() to figure
    // out the correct DST value and not adjust the selected time based
    // on the current setting of DST.
    val selectedTime: Time
        get() {
            val time = Time(mBaseDate)
            time.setJulianDay(mSelectionDay)
            time.hour = mSelectionHour

            // We ignore the "isDst" field because we want normalize() to figure
            // out the correct DST value and not adjust the selected time based
            // on the current setting of DST.
            time.normalize(true /* ignore isDst */)
            return time
        } /* ignore isDst */

    // We ignore the "isDst" field because we want normalize() to figure
    // out the correct DST value and not adjust the selected time based
    // on the current setting of DST.
    val selectedTimeForAccessibility: Time
        get() {
            val time = Time(mBaseDate)
            time.setJulianDay(mSelectionDayForAccessibility)
            time.hour = mSelectionHourForAccessibility

            // We ignore the "isDst" field because we want normalize() to figure
            // out the correct DST value and not adjust the selected time based
            // on the current setting of DST.
            time.normalize(true /* ignore isDst */)
            return time
        }

    /**
     * Returns the start of the selected time in minutes since midnight,
     * local time.  The derived class must ensure that this is consistent
     * with the return value from getSelectedTimeInMillis().
     */
    val selectedMinutesSinceMidnight: Int
        get() = mSelectionHour * MINUTES_PER_HOUR
    var firstVisibleHour: Int
        get() = mFirstHour
        set(firstHour) {
            mFirstHour = firstHour
            mFirstHourOffset = 0
        }

    fun setSelected(time: Time?, ignoreTime: Boolean, animateToday: Boolean) {
        mBaseDate?.set(time)
        setSelectedHour(mBaseDate!!.hour)
        setSelectedEvent(null)
        mPrevSelectedEvent = null
        val millis: Long = mBaseDate!!.toMillis(false /* use isDst */)
        setSelectedDay(Time.getJulianDay(millis, mBaseDate!!.gmtoff))
        mSelectedEvents.clear()
        mComputeSelectedEvents = true
        var gotoY: Int = Integer.MIN_VALUE
        if (!ignoreTime && mGridAreaHeight != -1) {
            var lastHour = 0
            if (mBaseDate!!.hour < mFirstHour) {
                // Above visible region
                gotoY = mBaseDate!!.hour * (mCellHeight + HOUR_GAP)
            } else {
                lastHour = ((mGridAreaHeight - mFirstHourOffset) / (mCellHeight + HOUR_GAP) +
                    mFirstHour)
                if (mBaseDate!!.hour >= lastHour) {
                    // Below visible region

                    // target hour + 1 (to give it room to see the event) -
                    // grid height (to get the y of the top of the visible
                    // region)
                    gotoY = ((mBaseDate!!.hour + 1 + mBaseDate!!.minute / 60.0f) *
                        (mCellHeight + HOUR_GAP) - mGridAreaHeight).toInt()
                }
            }
            if (DEBUG) {
                Log.e(
                    TAG, "Go " + gotoY + " 1st " + mFirstHour + ":" + mFirstHourOffset + "CH " +
                        (mCellHeight + HOUR_GAP) + " lh " + lastHour + " gh " + mGridAreaHeight +
                        " ymax " + mMaxViewStartY
                )
            }
            if (gotoY > mMaxViewStartY) {
                gotoY = mMaxViewStartY
            } else if (gotoY < 0 && gotoY != Integer.MIN_VALUE) {
                gotoY = 0
            }
        }
        recalc()
        mRemeasure = true
        invalidate()
        var delayAnimateToday = false
        if (gotoY != Integer.MIN_VALUE) {
            val scrollAnim: ValueAnimator =
                ObjectAnimator.ofInt(this, "viewStartY", mViewStartY, gotoY)
            scrollAnim.setDuration(GOTO_SCROLL_DURATION.toLong())
            scrollAnim.setInterpolator(AccelerateDecelerateInterpolator())
            scrollAnim.addListener(mAnimatorListener)
            scrollAnim.start()
            delayAnimateToday = true
        }
        if (animateToday) {
            synchronized(mTodayAnimatorListener) {
                if (mTodayAnimator != null) {
                    mTodayAnimator?.removeAllListeners()
                    mTodayAnimator?.cancel()
                }
                mTodayAnimator = ObjectAnimator.ofInt(
                    this, "animateTodayAlpha",
                    mAnimateTodayAlpha, 255
                )
                mAnimateToday = true
                mTodayAnimatorListener.setFadingIn(true)
                mTodayAnimatorListener.setAnimator(mTodayAnimator)
                mTodayAnimator?.addListener(mTodayAnimatorListener)
                mTodayAnimator?.setDuration(150)
                if (delayAnimateToday) {
                    mTodayAnimator?.setStartDelay(GOTO_SCROLL_DURATION.toLong())
                }
                mTodayAnimator?.start()
            }
        }
        sendAccessibilityEventAsNeeded(false)
    }

    // Called from animation framework via reflection. Do not remove
    fun setViewStartY(viewStartY: Int) {
        var viewStartY = viewStartY
        if (viewStartY > mMaxViewStartY) {
            viewStartY = mMaxViewStartY
        }
        mViewStartY = viewStartY
        computeFirstHour()
        invalidate()
    }

    fun setAnimateTodayAlpha(todayAlpha: Int) {
        mAnimateTodayAlpha = todayAlpha
        invalidate()
    } /* ignore isDst */

    fun getSelectedDay(): Time {
        val time = Time(mBaseDate)
        time.setJulianDay(mSelectionDay)
        time.hour = mSelectionHour

        // We ignore the "isDst" field because we want normalize() to figure
        // out the correct DST value and not adjust the selected time based
        // on the current setting of DST.
        time.normalize(true /* ignore isDst */)
        return time
    }

    fun updateTitle() {
        val start = Time(mBaseDate)
        start.normalize(true)
        val end = Time(start)
        end.monthDay += mNumDays - 1
        // Move it forward one minute so the formatter doesn't lose a day
        end.minute += 1
        end.normalize(true)
        var formatFlags: Long = DateUtils.FORMAT_SHOW_DATE.toLong() or
            DateUtils.FORMAT_SHOW_YEAR.toLong()
        if (mNumDays != 1) {
            // Don't show day of the month if for multi-day view
            formatFlags = formatFlags or DateUtils.FORMAT_NO_MONTH_DAY.toLong()

            // Abbreviate the month if showing multiple months
            if (start.month !== end.month) {
                formatFlags = formatFlags or DateUtils.FORMAT_ABBREV_MONTH.toLong()
            }
        }
        mController.sendEvent(
            this as Object?, EventType.UPDATE_TITLE, start, end, null, -1, ViewType.CURRENT,
            formatFlags, null, null
        )
    }

    /**
     * return a negative number if "time" is comes before the visible time
     * range, a positive number if "time" is after the visible time range, and 0
     * if it is in the visible time range.
     */
    fun compareToVisibleTimeRange(time: Time): Int {
        val savedHour: Int = mBaseDate!!.hour
        val savedMinute: Int = mBaseDate!!.minute
        val savedSec: Int = mBaseDate!!.second
        mBaseDate!!.hour = 0
        mBaseDate!!.minute = 0
        mBaseDate!!.second = 0
        if (DEBUG) {
            Log.d(TAG, "Begin " + mBaseDate.toString())
            Log.d(TAG, "Diff  " + time.toString())
        }

        // Compare beginning of range
        var diff: Int = Time.compare(time, mBaseDate)
        if (diff > 0) {
            // Compare end of range
            mBaseDate!!.monthDay += mNumDays
            mBaseDate?.normalize(true)
            diff = Time.compare(time, mBaseDate)
            if (DEBUG) Log.d(TAG, "End   " + mBaseDate.toString())
            mBaseDate!!.monthDay -= mNumDays
            mBaseDate?.normalize(true)
            if (diff < 0) {
                // in visible time
                diff = 0
            } else if (diff == 0) {
                // Midnight of following day
                diff = 1
            }
        }
        if (DEBUG) Log.d(TAG, "Diff: $diff")
        mBaseDate!!.hour = savedHour
        mBaseDate!!.minute = savedMinute
        mBaseDate!!.second = savedSec
        return diff
    }

    private fun recalc() {
        // Set the base date to the beginning of the week if we are displaying
        // 7 days at a time.
        if (mNumDays == 7) {
            adjustToBeginningOfWeek(mBaseDate)
        }
        val start: Long = mBaseDate!!.toMillis(false /* use isDst */)
        mFirstJulianDay = Time.getJulianDay(start, mBaseDate!!.gmtoff)
        mLastJulianDay = mFirstJulianDay + mNumDays - 1
        mMonthLength = mBaseDate!!.getActualMaximum(Time.MONTH_DAY)
        mFirstVisibleDate = mBaseDate!!.monthDay
        mFirstVisibleDayOfWeek = mBaseDate!!.weekDay
    }

    private fun adjustToBeginningOfWeek(time: Time?) {
        val dayOfWeek: Int = time!!.weekDay
        var diff = dayOfWeek - mFirstDayOfWeek
        if (diff != 0) {
            if (diff < 0) {
                diff += 7
            }
            time.monthDay -= diff
            time.normalize(true /* ignore isDst */)
        }
    }

    @Override
    protected override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        mViewWidth = width
        mViewHeight = height
        mEdgeEffectTop.setSize(mViewWidth, mViewHeight)
        mEdgeEffectBottom.setSize(mViewWidth, mViewHeight)
        val gridAreaWidth = width - mHoursWidth
        mCellWidth = (gridAreaWidth - mNumDays * DAY_GAP) / mNumDays

        // This would be about 1 day worth in a 7 day view
        mHorizontalSnapBackThreshold = width / 7
        val p = Paint()
        p.setTextSize(HOURS_TEXT_SIZE)
        mHoursTextHeight = Math.abs(p.ascent()).toInt()
        remeasure(width, height)
    }

    /**
     * Measures the space needed for various parts of the view after
     * loading new events.  This can change if there are all-day events.
     */
    private fun remeasure(width: Int, height: Int) {
        // Shrink to fit available space but make sure we can display at least two events
        MAX_UNEXPANDED_ALLDAY_HEIGHT = (MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT * 4).toInt()
        MAX_UNEXPANDED_ALLDAY_HEIGHT = Math.min(MAX_UNEXPANDED_ALLDAY_HEIGHT, height / 6)
        MAX_UNEXPANDED_ALLDAY_HEIGHT = Math.max(
            MAX_UNEXPANDED_ALLDAY_HEIGHT,
            MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT.toInt() * 2
        )
        mMaxUnexpandedAlldayEventCount =
            (MAX_UNEXPANDED_ALLDAY_HEIGHT / MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT).toInt()

        // First, clear the array of earliest start times, and the array
        // indicating presence of an all-day event.
        for (day in 0 until mNumDays) {
            mEarliestStartHour!![day] = 25 // some big number
            mHasAllDayEvent!![day] = false
        }
        val maxAllDayEvents = mMaxAlldayEvents

        // The min is where 24 hours cover the entire visible area
        mMinCellHeight = Math.max((height - DAY_HEADER_HEIGHT) / 24, MIN_EVENT_HEIGHT.toInt())
        if (mCellHeight < mMinCellHeight) {
            mCellHeight = mMinCellHeight
        }

        // Calculate mAllDayHeight
        mFirstCell = DAY_HEADER_HEIGHT
        var allDayHeight = 0
        if (maxAllDayEvents > 0) {
            val maxAllAllDayHeight = height - DAY_HEADER_HEIGHT - MIN_HOURS_HEIGHT
            // If there is at most one all-day event per day, then use less
            // space (but more than the space for a single event).
            if (maxAllDayEvents == 1) {
                allDayHeight = SINGLE_ALLDAY_HEIGHT
            } else if (maxAllDayEvents <= mMaxUnexpandedAlldayEventCount) {
                // Allow the all-day area to grow in height depending on the
                // number of all-day events we need to show, up to a limit.
                allDayHeight = maxAllDayEvents * MAX_HEIGHT_OF_ONE_ALLDAY_EVENT
                if (allDayHeight > MAX_UNEXPANDED_ALLDAY_HEIGHT) {
                    allDayHeight = MAX_UNEXPANDED_ALLDAY_HEIGHT
                }
            } else {
                // if we have more than the magic number, check if we're animating
                // and if not adjust the sizes appropriately
                if (mAnimateDayHeight != 0) {
                    // Don't shrink the space past the final allDay space. The animation
                    // continues to hide the last event so the more events text can
                    // fade in.
                    allDayHeight = Math.max(mAnimateDayHeight, MAX_UNEXPANDED_ALLDAY_HEIGHT)
                } else {
                    // Try to fit all the events in
                    allDayHeight = (maxAllDayEvents * MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT).toInt()
                    // But clip the area depending on which mode we're in
                    if (!mShowAllAllDayEvents && allDayHeight > MAX_UNEXPANDED_ALLDAY_HEIGHT) {
                        allDayHeight = (mMaxUnexpandedAlldayEventCount *
                            MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT).toInt()
                    } else if (allDayHeight > maxAllAllDayHeight) {
                        allDayHeight = maxAllAllDayHeight
                    }
                }
            }
            mFirstCell = DAY_HEADER_HEIGHT + allDayHeight + ALLDAY_TOP_MARGIN
        } else {
            mSelectionAllday = false
        }
        mAlldayHeight = allDayHeight
        mGridAreaHeight = height - mFirstCell

        // Set up the expand icon position
        val allDayIconWidth: Int = mExpandAlldayDrawable.getIntrinsicWidth()
        mExpandAllDayRect.left = Math.max(
            (mHoursWidth - allDayIconWidth) / 2,
            EVENT_ALL_DAY_TEXT_LEFT_MARGIN
        )
        mExpandAllDayRect.right = Math.min(
            mExpandAllDayRect.left + allDayIconWidth, mHoursWidth -
                EVENT_ALL_DAY_TEXT_RIGHT_MARGIN
        )
        mExpandAllDayRect.bottom = mFirstCell - EXPAND_ALL_DAY_BOTTOM_MARGIN
        mExpandAllDayRect.top = (mExpandAllDayRect.bottom -
            mExpandAlldayDrawable.getIntrinsicHeight())
        mNumHours = mGridAreaHeight / (mCellHeight + HOUR_GAP)
        mEventGeometry.setHourHeight(mCellHeight.toFloat())
        val minimumDurationMillis =
            (MIN_EVENT_HEIGHT * DateUtils.MINUTE_IN_MILLIS / (mCellHeight / 60.0f)).toLong()
        Event.computePositions(mEvents, minimumDurationMillis)

        // Compute the top of our reachable view
        mMaxViewStartY = HOUR_GAP + 24 * (mCellHeight + HOUR_GAP) - mGridAreaHeight
        if (DEBUG) {
            Log.e(TAG, "mViewStartY: $mViewStartY")
            Log.e(TAG, "mMaxViewStartY: $mMaxViewStartY")
        }
        if (mViewStartY > mMaxViewStartY) {
            mViewStartY = mMaxViewStartY
            computeFirstHour()
        }
        if (mFirstHour == -1) {
            initFirstHour()
            mFirstHourOffset = 0
        }

        // When we change the base date, the number of all-day events may
        // change and that changes the cell height.  When we switch dates,
        // we use the mFirstHourOffset from the previous view, but that may
        // be too large for the new view if the cell height is smaller.
        if (mFirstHourOffset >= mCellHeight + HOUR_GAP) {
            mFirstHourOffset = mCellHeight + HOUR_GAP - 1
        }
        mViewStartY = mFirstHour * (mCellHeight + HOUR_GAP) - mFirstHourOffset
        val eventAreaWidth = mNumDays * (mCellWidth + DAY_GAP)
        // When we get new events we don't want to dismiss the popup unless the event changes
        if (mSelectedEvent != null && mLastPopupEventID != mSelectedEvent!!.id) {
            mPopup?.dismiss()
        }
        mPopup?.setWidth(eventAreaWidth - 20)
        mPopup?.setHeight(WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Initialize the state for another view.  The given view is one that has
     * its own bitmap and will use an animation to replace the current view.
     * The current view and new view are either both Week views or both Day
     * views.  They differ in their base date.
     *
     * @param view the view to initialize.
     */
    private fun initView(view: DayView) {
        view.setSelectedHour(mSelectionHour)
        view.mSelectedEvents.clear()
        view.mComputeSelectedEvents = true
        view.mFirstHour = mFirstHour
        view.mFirstHourOffset = mFirstHourOffset
        view.remeasure(getWidth(), getHeight())
        view.initAllDayHeights()
        view.setSelectedEvent(null)
        view.mPrevSelectedEvent = null
        view.mFirstDayOfWeek = mFirstDayOfWeek
        if (view.mEvents.size > 0) {
            view.mSelectionAllday = mSelectionAllday
        } else {
            view.mSelectionAllday = false
        }

        // Redraw the screen so that the selection box will be redrawn.  We may
        // have scrolled to a different part of the day in some other view
        // so the selection box in this view may no longer be visible.
        view.recalc()
    }

    /**
     * Switch to another view based on what was selected (an event or a free
     * slot) and how it was selected (by touch or by trackball).
     *
     * @param trackBallSelection true if the selection was made using the
     * trackball.
     */
    private fun switchViews(trackBallSelection: Boolean) {
        val selectedEvent: Event? = mSelectedEvent
        mPopup?.dismiss()
        mLastPopupEventID = INVALID_EVENT_ID
        if (mNumDays > 1) {
            // This is the Week view.
            // With touch, we always switch to Day/Agenda View
            // With track ball, if we selected a free slot, then create an event.
            // If we selected a specific event, switch to EventInfo view.
            if (trackBallSelection) {
                if (selectedEvent != null) {
                    if (mIsAccessibilityEnabled) {
                        mAccessibilityMgr?.interrupt()
                    }
                }
            }
        }
    }

    @Override
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        mScrolling = false
        return super.onKeyUp(keyCode, event)
    }

    @Override
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    @Override
    override fun onHoverEvent(event: MotionEvent?): Boolean {
        return true
    }

    private val isTouchExplorationEnabled: Boolean
        private get() = mIsAccessibilityEnabled && mAccessibilityMgr!!.isTouchExplorationEnabled()

    private fun sendAccessibilityEventAsNeeded(speakEvents: Boolean) {
        if (!mIsAccessibilityEnabled) {
            return
        }
        val dayChanged = mLastSelectionDayForAccessibility != mSelectionDayForAccessibility
        val hourChanged = mLastSelectionHourForAccessibility != mSelectionHourForAccessibility
        if (dayChanged || hourChanged || mLastSelectedEventForAccessibility !==
            mSelectedEventForAccessibility) {
            mLastSelectionDayForAccessibility = mSelectionDayForAccessibility
            mLastSelectionHourForAccessibility = mSelectionHourForAccessibility
            mLastSelectedEventForAccessibility = mSelectedEventForAccessibility
            val b = StringBuilder()

            // Announce only the changes i.e. day or hour or both
            if (dayChanged) {
                b.append(selectedTimeForAccessibility.format("%A "))
            }
            if (hourChanged) {
                b.append(selectedTimeForAccessibility.format(if (mIs24HourFormat) "%k" else "%l%p"))
            }
            if (dayChanged || hourChanged) {
                b.append(PERIOD_SPACE)
            }
            if (speakEvents) {
                if (mEventCountTemplate == null) {
                    mEventCountTemplate = mContext?.getString(R.string.template_announce_item_index)
                }

                // Read out the relevant event(s)
                val numEvents: Int = mSelectedEvents.size
                if (numEvents > 0) {
                    if (mSelectedEventForAccessibility == null) {
                        // Read out all the events
                        var i = 1
                        for (calEvent in mSelectedEvents) {
                            if (numEvents > 1) {
                                // Read out x of numEvents if there are more than one event
                                mStringBuilder.setLength(0)
                                b.append(mFormatter.format(mEventCountTemplate, i++, numEvents))
                                b.append(" ")
                            }
                            appendEventAccessibilityString(b, calEvent)
                        }
                    } else {
                        if (numEvents > 1) {
                            // Read out x of numEvents if there are more than one event
                            mStringBuilder.setLength(0)
                            b.append(
                                mFormatter.format(
                                    mEventCountTemplate, mSelectedEvents
                                        .indexOf(mSelectedEventForAccessibility) + 1, numEvents
                                )
                            )
                            b.append(" ")
                        }
                        appendEventAccessibilityString(b, mSelectedEventForAccessibility)
                    }
                }
            }
            if (dayChanged || hourChanged || speakEvents) {
                val event: AccessibilityEvent = AccessibilityEvent
                    .obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED)
                val msg: CharSequence = b.toString()
                event.getText().add(msg)
                event.setAddedCount(msg.length)
                sendAccessibilityEventUnchecked(event)
            }
        }
    }

    /**
     * @param b
     * @param calEvent
     */
    private fun appendEventAccessibilityString(b: StringBuilder, calEvent: Event?) {
        b.append(calEvent!!.titleAndLocation)
        b.append(PERIOD_SPACE)
        val `when`: String?
        var flags: Int = DateUtils.FORMAT_SHOW_DATE
        if (calEvent.allDay) {
            flags = flags or (DateUtils.FORMAT_UTC or DateUtils.FORMAT_SHOW_WEEKDAY)
        } else {
            flags = flags or DateUtils.FORMAT_SHOW_TIME
            if (DateFormat.is24HourFormat(mContext)) {
                flags = flags or DateUtils.FORMAT_24HOUR
            }
        }
        `when` = Utils.formatDateRange(mContext, calEvent.startMillis, calEvent.endMillis,
            flags)
        b.append(`when`)
        b.append(PERIOD_SPACE)
    }

    private inner class GotoBroadcaster(start: Time, end: Time) : Animation.AnimationListener {
        private val mCounter: Int
        private val mStart: Time
        private val mEnd: Time
        @Override
        override fun onAnimationEnd(animation: Animation) {
            var view = mViewSwitcher.getCurrentView() as DayView
            view.mViewStartX = 0
            view = mViewSwitcher.getNextView() as DayView
            view.mViewStartX = 0
            if (mCounter == sCounter) {
                mController.sendEvent(
                    this as Object?, EventType.GO_TO, mStart, mEnd, null, -1,
                    ViewType.CURRENT, CalendarController.EXTRA_GOTO_DATE, null, null
                )
            }
        }

        @Override
        override fun onAnimationRepeat(animation: Animation) {
        }

        @Override
        override fun onAnimationStart(animation: Animation) {
        }

        init {
            mCounter = ++sCounter
            mStart = start
            mEnd = end
        }
    }

    private fun switchViews(forward: Boolean, xOffSet: Float, width: Float, velocity: Float): View {
        mAnimationDistance = width - xOffSet
        if (DEBUG) {
            Log.d(TAG, "switchViews($forward) O:$xOffSet Dist:$mAnimationDistance")
        }
        var progress: Float = Math.abs(xOffSet) / width
        if (progress > 1.0f) {
            progress = 1.0f
        }
        val inFromXValue: Float
        val inToXValue: Float
        val outFromXValue: Float
        val outToXValue: Float
        if (forward) {
            inFromXValue = 1.0f - progress
            inToXValue = 0.0f
            outFromXValue = -progress
            outToXValue = -1.0f
        } else {
            inFromXValue = progress - 1.0f
            inToXValue = 0.0f
            outFromXValue = progress
            outToXValue = 1.0f
        }
        val start = Time(mBaseDate!!.timezone)
        start.set(mController.time as Long)
        if (forward) {
            start.monthDay += mNumDays
        } else {
            start.monthDay -= mNumDays
        }
        mController.time = start.normalize(true)
        var newSelected: Time? = start
        if (mNumDays == 7) {
            newSelected = Time(start)
            adjustToBeginningOfWeek(start)
        }
        val end = Time(start)
        end.monthDay += mNumDays - 1

        // We have to allocate these animation objects each time we switch views
        // because that is the only way to set the animation parameters.
        val inAnimation = TranslateAnimation(
            Animation.RELATIVE_TO_SELF, inFromXValue,
            Animation.RELATIVE_TO_SELF, inToXValue,
            Animation.ABSOLUTE, 0.0f,
            Animation.ABSOLUTE, 0.0f
        )
        val outAnimation = TranslateAnimation(
            Animation.RELATIVE_TO_SELF, outFromXValue,
            Animation.RELATIVE_TO_SELF, outToXValue,
            Animation.ABSOLUTE, 0.0f,
            Animation.ABSOLUTE, 0.0f
        )
        val duration = calculateDuration(width - Math.abs(xOffSet), width, velocity)
        inAnimation.setDuration(duration)
        inAnimation.setInterpolator(mHScrollInterpolator)
        outAnimation.setInterpolator(mHScrollInterpolator)
        outAnimation.setDuration(duration)
        outAnimation.setAnimationListener(GotoBroadcaster(start, end))
        mViewSwitcher.setInAnimation(inAnimation)
        mViewSwitcher.setOutAnimation(outAnimation)
        var view = mViewSwitcher.getCurrentView() as DayView
        view.cleanup()
        mViewSwitcher.showNext()
        view = mViewSwitcher.getCurrentView() as DayView
        view.setSelected(newSelected, true, false)
        view.requestFocus()
        view.reloadEvents()
        view.updateTitle()
        view.restartCurrentTimeUpdates()
        return view
    }

    // This is called after scrolling stops to move the selected hour
    // to the visible part of the screen.
    private fun resetSelectedHour() {
        if (mSelectionHour < mFirstHour + 1) {
            setSelectedHour(mFirstHour + 1)
            setSelectedEvent(null)
            mSelectedEvents.clear()
            mComputeSelectedEvents = true
        } else if (mSelectionHour > mFirstHour + mNumHours - 3) {
            setSelectedHour(mFirstHour + mNumHours - 3)
            setSelectedEvent(null)
            mSelectedEvents.clear()
            mComputeSelectedEvents = true
        }
    }

    private fun initFirstHour() {
        mFirstHour = mSelectionHour - mNumHours / 5
        if (mFirstHour < 0) {
            mFirstHour = 0
        } else if (mFirstHour + mNumHours > 24) {
            mFirstHour = 24 - mNumHours
        }
    }

    /**
     * Recomputes the first full hour that is visible on screen after the
     * screen is scrolled.
     */
    private fun computeFirstHour() {
        // Compute the first full hour that is visible on screen
        mFirstHour = (mViewStartY + mCellHeight + HOUR_GAP - 1) / (mCellHeight + HOUR_GAP)
        mFirstHourOffset = mFirstHour * (mCellHeight + HOUR_GAP) - mViewStartY
    }

    private fun adjustHourSelection() {
        if (mSelectionHour < 0) {
            setSelectedHour(0)
            if (mMaxAlldayEvents > 0) {
                mPrevSelectedEvent = null
                mSelectionAllday = true
            }
        }
        if (mSelectionHour > 23) {
            setSelectedHour(23)
        }

        // If the selected hour is at least 2 time slots from the top and
        // bottom of the screen, then don't scroll the view.
        if (mSelectionHour < mFirstHour + 1) {
            // If there are all-days events for the selected day but there
            // are no more normal events earlier in the day, then jump to
            // the all-day event area.
            // Exception 1: allow the user to scroll to 8am with the trackball
            // before jumping to the all-day event area.
            // Exception 2: if 12am is on screen, then allow the user to select
            // 12am before going up to the all-day event area.
            val daynum = mSelectionDay - mFirstJulianDay
            if (daynum < mEarliestStartHour!!.size && daynum >= 0 && mMaxAlldayEvents > 0 &&
                mEarliestStartHour!![daynum] > mSelectionHour &&
                mFirstHour > 0 && mFirstHour < 8) {
                mPrevSelectedEvent = null
                mSelectionAllday = true
                setSelectedHour(mFirstHour + 1)
                return
            }
            if (mFirstHour > 0) {
                mFirstHour -= 1
                mViewStartY -= mCellHeight + HOUR_GAP
                if (mViewStartY < 0) {
                    mViewStartY = 0
                }
                return
            }
        }
        if (mSelectionHour > mFirstHour + mNumHours - 3) {
            if (mFirstHour < 24 - mNumHours) {
                mFirstHour += 1
                mViewStartY += mCellHeight + HOUR_GAP
                if (mViewStartY > mMaxViewStartY) {
                    mViewStartY = mMaxViewStartY
                }
                return
            } else if (mFirstHour == 24 - mNumHours && mFirstHourOffset > 0) {
                mViewStartY = mMaxViewStartY
            }
        }
    }

    fun clearCachedEvents() {
        mLastReloadMillis = 0
    }

    private val mCancelCallback: Runnable = object : Runnable {
        override fun run() {
            clearCachedEvents()
        }
    }

    /* package */
    fun reloadEvents() {
        // Protect against this being called before this view has been
        // initialized.
//        if (mContext == null) {
//            return;
//        }

        // Make sure our time zones are up to date
        mTZUpdater.run()
        setSelectedEvent(null)
        mPrevSelectedEvent = null
        mSelectedEvents.clear()

        // The start date is the beginning of the week at 12am
        val weekStart = Time(Utils.getTimeZone(mContext, mTZUpdater))
        weekStart.set(mBaseDate)
        weekStart.hour = 0
        weekStart.minute = 0
        weekStart.second = 0
        val millis: Long = weekStart.normalize(true /* ignore isDst */)

        // Avoid reloading events unnecessarily.
        if (millis == mLastReloadMillis) {
            return
        }
        mLastReloadMillis = millis

        // load events in the background
        // mContext.startProgressSpinner();
        val events: ArrayList<Event> = ArrayList<Event>()
        mEventLoader.loadEventsInBackground(mNumDays, events as ArrayList<Event?>, mFirstJulianDay,
            object : Runnable {
            override fun run() {
                val fadeinEvents = mFirstJulianDay != mLoadedFirstJulianDay
                mEvents = events
                mLoadedFirstJulianDay = mFirstJulianDay
                if (mAllDayEvents == null) {
                    mAllDayEvents = ArrayList<Event>()
                } else {
                    mAllDayEvents?.clear()
                }

                // Create a shorter array for all day events
                for (e in events) {
                    if (e.drawAsAllday()) {
                        mAllDayEvents?.add(e)
                    }
                }

                // New events, new layouts
                if (mLayouts == null || mLayouts!!.size < events.size) {
                    mLayouts = arrayOfNulls<StaticLayout>(events.size)
                } else {
                    Arrays.fill(mLayouts, null)
                }
                if (mAllDayLayouts == null || mAllDayLayouts!!.size < mAllDayEvents!!.size) {
                    mAllDayLayouts = arrayOfNulls<StaticLayout>(events.size)
                } else {
                    Arrays.fill(mAllDayLayouts, null)
                }
                computeEventRelations()
                mRemeasure = true
                mComputeSelectedEvents = true
                recalc()

                // Start animation to cross fade the events
                if (fadeinEvents) {
                    if (mEventsCrossFadeAnimation == null) {
                        mEventsCrossFadeAnimation =
                            ObjectAnimator.ofInt(this@DayView, "EventsAlpha", 0, 255)
                        mEventsCrossFadeAnimation?.setDuration(EVENTS_CROSS_FADE_DURATION.toLong())
                    }
                    mEventsCrossFadeAnimation?.start()
                } else {
                    invalidate()
                }
            }
        }, mCancelCallback)
    }

    var eventsAlpha: Int
        get() = mEventsAlpha
        set(alpha) {
            mEventsAlpha = alpha
            invalidate()
        }

    fun stopEventsAnimation() {
        if (mEventsCrossFadeAnimation != null) {
            mEventsCrossFadeAnimation?.cancel()
        }
        mEventsAlpha = 255
    }

    private fun computeEventRelations() {
        // Compute the layout relation between each event before measuring cell
        // width, as the cell width should be adjusted along with the relation.
        //
        // Examples: A (1:00pm - 1:01pm), B (1:02pm - 2:00pm)
        // We should mark them as "overwapped". Though they are not overwapped logically, but
        // minimum cell height implicitly expands the cell height of A and it should look like
        // (1:00pm - 1:15pm) after the cell height adjustment.

        // Compute the space needed for the all-day events, if any.
        // Make a pass over all the events, and keep track of the maximum
        // number of all-day events in any one day.  Also, keep track of
        // the earliest event in each day.
        var maxAllDayEvents = 0
        val events: ArrayList<Event> = mEvents
        val len: Int = events.size
        // Num of all-day-events on each day.
        val eventsCount = IntArray(mLastJulianDay - mFirstJulianDay + 1)
        Arrays.fill(eventsCount, 0)
        for (ii in 0 until len) {
            val event: Event = events.get(ii)
            if (event.startDay > mLastJulianDay || event.endDay < mFirstJulianDay) {
                continue
            }
            if (event.drawAsAllday()) {
                // Count all the events being drawn as allDay events
                val firstDay: Int = Math.max(event.startDay, mFirstJulianDay)
                val lastDay: Int = Math.min(event.endDay, mLastJulianDay)
                for (day in firstDay..lastDay) {
                    val count = ++eventsCount[day - mFirstJulianDay]
                    if (maxAllDayEvents < count) {
                        maxAllDayEvents = count
                    }
                }
                var daynum: Int = event.startDay - mFirstJulianDay
                var durationDays: Int = event.endDay - event.startDay + 1
                if (daynum < 0) {
                    durationDays += daynum
                    daynum = 0
                }
                if (daynum + durationDays > mNumDays) {
                    durationDays = mNumDays - daynum
                }
                var day = daynum
                while (durationDays > 0) {
                    mHasAllDayEvent!![day] = true
                    day++
                    durationDays--
                }
            } else {
                var daynum: Int = event.startDay - mFirstJulianDay
                var hour: Int = event.startTime / 60
                if (daynum >= 0 && hour < mEarliestStartHour!![daynum]) {
                    mEarliestStartHour!![daynum] = hour
                }

                // Also check the end hour in case the event spans more than
                // one day.
                daynum = event.endDay - mFirstJulianDay
                hour = event.endTime / 60
                if (daynum < mNumDays && hour < mEarliestStartHour!![daynum]) {
                    mEarliestStartHour!![daynum] = hour
                }
            }
        }
        mMaxAlldayEvents = maxAllDayEvents
        initAllDayHeights()
    }

    @Override
    protected override fun onDraw(canvas: Canvas) {
        if (mRemeasure) {
            remeasure(getWidth(), getHeight())
            mRemeasure = false
        }
        canvas.save()
        val yTranslate = (-mViewStartY + DAY_HEADER_HEIGHT + mAlldayHeight).toFloat()
        // offset canvas by the current drag and header position
        canvas.translate(-mViewStartX.toFloat(), yTranslate)
        // clip to everything below the allDay area
        val dest: Rect = mDestRect
        dest.top = (mFirstCell - yTranslate).toInt()
        dest.bottom = (mViewHeight - yTranslate).toInt()
        dest.left = 0
        dest.right = mViewWidth
        canvas.save()
        canvas.clipRect(dest)
        // Draw the movable part of the view
        doDraw(canvas)
        // restore to having no clip
        canvas.restore()
        if (mTouchMode and TOUCH_MODE_HSCROLL != 0) {
            val xTranslate: Float
            xTranslate = if (mViewStartX > 0) {
                mViewWidth.toFloat()
            } else {
                -mViewWidth.toFloat()
            }
            // Move the canvas around to prep it for the next view
            // specifically, shift it by a screen and undo the
            // yTranslation which will be redone in the nextView's onDraw().
            canvas.translate(xTranslate, -yTranslate)
            val nextView = mViewSwitcher.getNextView() as DayView

            // Prevent infinite recursive calls to onDraw().
            nextView.mTouchMode = TOUCH_MODE_INITIAL_STATE
            nextView.onDraw(canvas)
            // Move it back for this view
            canvas.translate(-xTranslate, 0f)
        } else {
            // If we drew another view we already translated it back
            // If we didn't draw another view we should be at the edge of the
            // screen
            canvas.translate(mViewStartX.toFloat(), -yTranslate)
        }

        // Draw the fixed areas (that don't scroll) directly to the canvas.
        drawAfterScroll(canvas)
        if (mComputeSelectedEvents && mUpdateToast) {
            mUpdateToast = false
        }
        mComputeSelectedEvents = false

        // Draw overscroll glow
        if (!mEdgeEffectTop.isFinished()) {
            if (DAY_HEADER_HEIGHT != 0) {
                canvas.translate(0f, DAY_HEADER_HEIGHT.toFloat())
            }
            if (mEdgeEffectTop.draw(canvas)) {
                invalidate()
            }
            if (DAY_HEADER_HEIGHT != 0) {
                canvas.translate(0f, -DAY_HEADER_HEIGHT.toFloat())
            }
        }
        if (!mEdgeEffectBottom.isFinished()) {
            canvas.rotate(180f, mViewWidth.toFloat() / 2f, mViewHeight.toFloat() / 2f)
            if (mEdgeEffectBottom.draw(canvas)) {
                invalidate()
            }
        }
        canvas.restore()
    }

    private fun drawAfterScroll(canvas: Canvas) {
        val p: Paint = mPaint
        val r: Rect = mRect
        drawAllDayHighlights(r, canvas, p)
        if (mMaxAlldayEvents != 0) {
            drawAllDayEvents(mFirstJulianDay, mNumDays, canvas, p)
            drawUpperLeftCorner(r, canvas, p)
        }
        drawScrollLine(r, canvas, p)
        drawDayHeaderLoop(r, canvas, p)

        // Draw the AM and PM indicators if we're in 12 hour mode
        if (!mIs24HourFormat) {
            drawAmPm(canvas, p)
        }
    }

    // This isn't really the upper-left corner. It's the square area just
    // below the upper-left corner, above the hours and to the left of the
    // all-day area.
    private fun drawUpperLeftCorner(r: Rect, canvas: Canvas, p: Paint) {
        setupHourTextPaint(p)
        if (mMaxAlldayEvents > mMaxUnexpandedAlldayEventCount) {
            // Draw the allDay expand/collapse icon
            if (mUseExpandIcon) {
                mExpandAlldayDrawable.setBounds(mExpandAllDayRect)
                mExpandAlldayDrawable.draw(canvas)
            } else {
                mCollapseAlldayDrawable.setBounds(mExpandAllDayRect)
                mCollapseAlldayDrawable.draw(canvas)
            }
        }
    }

    private fun drawScrollLine(r: Rect, canvas: Canvas, p: Paint) {
        val right = computeDayLeftPosition(mNumDays)
        val y = mFirstCell - 1
        p.setAntiAlias(false)
        p.setStyle(Style.FILL)
        p.setColor(mCalendarGridLineInnerHorizontalColor)
        p.setStrokeWidth(GRID_LINE_INNER_WIDTH)
        canvas.drawLine(GRID_LINE_LEFT_MARGIN, y.toFloat(), right.toFloat(), y.toFloat(), p)
        p.setAntiAlias(true)
    }

    // Computes the x position for the left side of the given day (base 0)
    private fun computeDayLeftPosition(day: Int): Int {
        val effectiveWidth = mViewWidth - mHoursWidth
        return day * effectiveWidth / mNumDays + mHoursWidth
    }

    private fun drawAllDayHighlights(r: Rect, canvas: Canvas, p: Paint) {
        if (mFutureBgColor != 0) {
            // First, color the labels area light gray
            r.top = 0
            r.bottom = DAY_HEADER_HEIGHT
            r.left = 0
            r.right = mViewWidth
            p.setColor(mBgColor)
            p.setStyle(Style.FILL)
            canvas.drawRect(r, p)
            // and the area that says All day
            r.top = DAY_HEADER_HEIGHT
            r.bottom = mFirstCell - 1
            r.left = 0
            r.right = mHoursWidth
            canvas.drawRect(r, p)
            var startIndex = -1
            val todayIndex = mTodayJulianDay - mFirstJulianDay
            if (todayIndex < 0) {
                // Future
                startIndex = 0
            } else if (todayIndex >= 1 && todayIndex + 1 < mNumDays) {
                // Multiday - tomorrow is visible.
                startIndex = todayIndex + 1
            }
            if (startIndex >= 0) {
                // Draw the future highlight
                r.top = 0
                r.bottom = mFirstCell - 1
                r.left = computeDayLeftPosition(startIndex) + 1
                r.right = computeDayLeftPosition(mNumDays)
                p.setColor(mFutureBgColor)
                p.setStyle(Style.FILL)
                canvas.drawRect(r, p)
            }
        }
    }

    private fun drawDayHeaderLoop(r: Rect, canvas: Canvas, p: Paint) {
        // Draw the horizontal day background banner
        // p.setColor(mCalendarDateBannerBackground);
        // r.top = 0;
        // r.bottom = DAY_HEADER_HEIGHT;
        // r.left = 0;
        // r.right = mHoursWidth + mNumDays * (mCellWidth + DAY_GAP);
        // canvas.drawRect(r, p);
        //
        // Fill the extra space on the right side with the default background
        // r.left = r.right;
        // r.right = mViewWidth;
        // p.setColor(mCalendarGridAreaBackground);
        // canvas.drawRect(r, p);
        if (mNumDays == 1 && ONE_DAY_HEADER_HEIGHT == 0) {
            return
        }
        p.setTypeface(mBold)
        p.setTextAlign(Paint.Align.RIGHT)
        var cell = mFirstJulianDay
        val dayNames: Array<String?>?
        dayNames = if (mDateStrWidth < mCellWidth) {
            mDayStrs
        } else {
            mDayStrs2Letter
        }
        p.setAntiAlias(true)
        var day = 0
        while (day < mNumDays) {
            var dayOfWeek = day + mFirstVisibleDayOfWeek
            if (dayOfWeek >= 14) {
                dayOfWeek -= 14
            }
            var color = mCalendarDateBannerTextColor
            if (mNumDays == 1) {
                if (dayOfWeek == Time.SATURDAY) {
                    color = mWeek_saturdayColor
                } else if (dayOfWeek == Time.SUNDAY) {
                    color = mWeek_sundayColor
                }
            } else {
                val column = day % 7
                if (Utils.isSaturday(column, mFirstDayOfWeek)) {
                    color = mWeek_saturdayColor
                } else if (Utils.isSunday(column, mFirstDayOfWeek)) {
                    color = mWeek_sundayColor
                }
            }
            p.setColor(color)
            drawDayHeader(dayNames!![dayOfWeek], day, cell, canvas, p)
            day++
            cell++
        }
        p.setTypeface(null)
    }

    private fun drawAmPm(canvas: Canvas, p: Paint) {
        p.setColor(mCalendarAmPmLabel)
        p.setTextSize(AMPM_TEXT_SIZE)
        p.setTypeface(mBold)
        p.setAntiAlias(true)
        p.setTextAlign(Paint.Align.RIGHT)
        var text = mAmString
        if (mFirstHour >= 12) {
            text = mPmString
        }
        var y = mFirstCell + mFirstHourOffset + 2 * mHoursTextHeight + HOUR_GAP
        canvas.drawText(text as String, HOURS_LEFT_MARGIN.toFloat(), y.toFloat(), p)
        if (mFirstHour < 12 && mFirstHour + mNumHours > 12) {
            // Also draw the "PM"
            text = mPmString
            y =
                mFirstCell + mFirstHourOffset + (12 - mFirstHour) * (mCellHeight + HOUR_GAP) +
                    2 * mHoursTextHeight + HOUR_GAP
            canvas.drawText(text as String, HOURS_LEFT_MARGIN.toFloat(), y.toFloat(), p)
        }
    }

    private fun drawCurrentTimeLine(
        r: Rect,
        day: Int,
        top: Int,
        canvas: Canvas,
        p: Paint
    ) {
        r.left = computeDayLeftPosition(day) - CURRENT_TIME_LINE_SIDE_BUFFER + 1
        r.right = computeDayLeftPosition(day + 1) + CURRENT_TIME_LINE_SIDE_BUFFER + 1
        r.top = top - CURRENT_TIME_LINE_TOP_OFFSET
        r.bottom = r.top + mCurrentTimeLine.getIntrinsicHeight()
        mCurrentTimeLine.setBounds(r)
        mCurrentTimeLine.draw(canvas)
        if (mAnimateToday) {
            mCurrentTimeAnimateLine.setBounds(r)
            mCurrentTimeAnimateLine.setAlpha(mAnimateTodayAlpha)
            mCurrentTimeAnimateLine.draw(canvas)
        }
    }

    private fun doDraw(canvas: Canvas) {
        val p: Paint = mPaint
        val r: Rect = mRect
        if (mFutureBgColor != 0) {
            drawBgColors(r, canvas, p)
        }
        drawGridBackground(r, canvas, p)
        drawHours(r, canvas, p)

        // Draw each day
        var cell = mFirstJulianDay
        p.setAntiAlias(false)
        val alpha: Int = p.getAlpha()
        p.setAlpha(mEventsAlpha)
        var day = 0
        while (day < mNumDays) {

            // TODO Wow, this needs cleanup. drawEvents loop through all the
            // events on every call.
            drawEvents(cell, day, HOUR_GAP, canvas, p)
            // If this is today
            if (cell == mTodayJulianDay) {
                val lineY: Int =
                    mCurrentTime!!.hour * (mCellHeight + HOUR_GAP) + mCurrentTime!!.minute *
                        mCellHeight / 60 + 1

                // And the current time shows up somewhere on the screen
                if (lineY >= mViewStartY && lineY < mViewStartY + mViewHeight - 2) {
                    drawCurrentTimeLine(r, day, lineY, canvas, p)
                }
            }
            day++
            cell++
        }
        p.setAntiAlias(true)
        p.setAlpha(alpha)
    }

    private fun drawHours(r: Rect, canvas: Canvas, p: Paint) {
        setupHourTextPaint(p)
        var y = HOUR_GAP + mHoursTextHeight + HOURS_TOP_MARGIN
        for (i in 0..23) {
            val time = mHourStrs!![i]
            canvas.drawText(time, HOURS_LEFT_MARGIN.toFloat(), y.toFloat(), p)
            y += mCellHeight + HOUR_GAP
        }
    }

    private fun setupHourTextPaint(p: Paint) {
        p.setColor(mCalendarHourLabelColor)
        p.setTextSize(HOURS_TEXT_SIZE)
        p.setTypeface(Typeface.DEFAULT)
        p.setTextAlign(Paint.Align.RIGHT)
        p.setAntiAlias(true)
    }

    private fun drawDayHeader(dayStr: String?, day: Int, cell: Int, canvas: Canvas, p: Paint) {
        var dateNum = mFirstVisibleDate + day
        var x: Int
        if (dateNum > mMonthLength) {
            dateNum -= mMonthLength
        }
        p.setAntiAlias(true)
        val todayIndex = mTodayJulianDay - mFirstJulianDay
        // Draw day of the month
        val dateNumStr: String = dateNum.toString()
        if (mNumDays > 1) {
            val y = (DAY_HEADER_HEIGHT - DAY_HEADER_BOTTOM_MARGIN).toFloat()

            // Draw day of the month
            x = computeDayLeftPosition(day + 1) - DAY_HEADER_RIGHT_MARGIN
            p.setTextAlign(Align.RIGHT)
            p.setTextSize(DATE_HEADER_FONT_SIZE)
            p.setTypeface(if (todayIndex == day) mBold else Typeface.DEFAULT)
            canvas.drawText(dateNumStr as String, x.toFloat(), y, p)

            // Draw day of the week
            x -= (p.measureText(" $dateNumStr")).toInt()
            p.setTextSize(DAY_HEADER_FONT_SIZE)
            p.setTypeface(Typeface.DEFAULT)
            canvas.drawText(dayStr as String, x.toFloat(), y, p)
        } else {
            val y = (ONE_DAY_HEADER_HEIGHT - DAY_HEADER_ONE_DAY_BOTTOM_MARGIN).toFloat()
            p.setTextAlign(Align.LEFT)

            // Draw day of the week
            x = computeDayLeftPosition(day) + DAY_HEADER_ONE_DAY_LEFT_MARGIN
            p.setTextSize(DAY_HEADER_FONT_SIZE)
            p.setTypeface(Typeface.DEFAULT)
            canvas.drawText(dayStr as String, x.toFloat(), y, p)

            // Draw day of the month
            x += (p.measureText(dayStr) + DAY_HEADER_ONE_DAY_RIGHT_MARGIN).toInt()
            p.setTextSize(DATE_HEADER_FONT_SIZE)
            p.setTypeface(if (todayIndex == day) mBold else Typeface.DEFAULT)
            canvas.drawText(dateNumStr, x.toFloat(), y, p)
        }
    }

    private fun drawGridBackground(r: Rect, canvas: Canvas, p: Paint) {
        val savedStyle: Style = p.getStyle()
        val stopX = computeDayLeftPosition(mNumDays).toFloat()
        var y = 0f
        val deltaY = (mCellHeight + HOUR_GAP).toFloat()
        var linesIndex = 0
        val startY = 0f
        val stopY = (HOUR_GAP + 24 * (mCellHeight + HOUR_GAP)).toFloat()
        var x = mHoursWidth.toFloat()

        // Draw the inner horizontal grid lines
        p.setColor(mCalendarGridLineInnerHorizontalColor)
        p.setStrokeWidth(GRID_LINE_INNER_WIDTH)
        p.setAntiAlias(false)
        y = 0f
        linesIndex = 0
        for (hour in 0..24) {
            mLines[linesIndex++] = GRID_LINE_LEFT_MARGIN
            mLines[linesIndex++] = y
            mLines[linesIndex++] = stopX
            mLines[linesIndex++] = y
            y += deltaY
        }
        if (mCalendarGridLineInnerVerticalColor != mCalendarGridLineInnerHorizontalColor) {
            canvas.drawLines(mLines, 0, linesIndex, p)
            linesIndex = 0
            p.setColor(mCalendarGridLineInnerVerticalColor)
        }

        // Draw the inner vertical grid lines
        for (day in 0..mNumDays) {
            x = computeDayLeftPosition(day).toFloat()
            mLines[linesIndex++] = x
            mLines[linesIndex++] = startY
            mLines[linesIndex++] = x
            mLines[linesIndex++] = stopY
        }
        canvas.drawLines(mLines, 0, linesIndex, p)

        // Restore the saved style.
        p.setStyle(savedStyle)
        p.setAntiAlias(true)
    }

    /**
     * @param r
     * @param canvas
     * @param p
     */
    private fun drawBgColors(r: Rect, canvas: Canvas, p: Paint) {
        val todayIndex = mTodayJulianDay - mFirstJulianDay
        // Draw the hours background color
        r.top = mDestRect.top
        r.bottom = mDestRect.bottom
        r.left = 0
        r.right = mHoursWidth
        p.setColor(mBgColor)
        p.setStyle(Style.FILL)
        p.setAntiAlias(false)
        canvas.drawRect(r, p)

        // Draw background for grid area
        if (mNumDays == 1 && todayIndex == 0) {
            // Draw a white background for the time later than current time
            var lineY: Int =
                mCurrentTime!!.hour * (mCellHeight + HOUR_GAP) + mCurrentTime!!.minute *
                    mCellHeight / 60 + 1
            if (lineY < mViewStartY + mViewHeight) {
                lineY = Math.max(lineY, mViewStartY)
                r.left = mHoursWidth
                r.right = mViewWidth
                r.top = lineY
                r.bottom = mViewStartY + mViewHeight
                p.setColor(mFutureBgColor)
                canvas.drawRect(r, p)
            }
        } else if (todayIndex >= 0 && todayIndex < mNumDays) {
            // Draw today with a white background for the time later than current time
            var lineY: Int =
                mCurrentTime!!.hour * (mCellHeight + HOUR_GAP) + mCurrentTime!!.minute *
                    mCellHeight / 60 + 1
            if (lineY < mViewStartY + mViewHeight) {
                lineY = Math.max(lineY, mViewStartY)
                r.left = computeDayLeftPosition(todayIndex) + 1
                r.right = computeDayLeftPosition(todayIndex + 1)
                r.top = lineY
                r.bottom = mViewStartY + mViewHeight
                p.setColor(mFutureBgColor)
                canvas.drawRect(r, p)
            }

            // Paint Tomorrow and later days with future color
            if (todayIndex + 1 < mNumDays) {
                r.left = computeDayLeftPosition(todayIndex + 1) + 1
                r.right = computeDayLeftPosition(mNumDays)
                r.top = mDestRect.top
                r.bottom = mDestRect.bottom
                p.setColor(mFutureBgColor)
                canvas.drawRect(r, p)
            }
        } else if (todayIndex < 0) {
            // Future
            r.left = computeDayLeftPosition(0) + 1
            r.right = computeDayLeftPosition(mNumDays)
            r.top = mDestRect.top
            r.bottom = mDestRect.bottom
            p.setColor(mFutureBgColor)
            canvas.drawRect(r, p)
        }
        p.setAntiAlias(true)
    }

    private fun computeMaxStringWidth(currentMax: Int, strings: Array<String?>, p: Paint): Int {
        var maxWidthF = 0.0f
        val len = strings.size
        for (i in 0 until len) {
            val width: Float = p.measureText(strings[i])
            maxWidthF = Math.max(width, maxWidthF)
        }
        var maxWidth = (maxWidthF + 0.5).toInt()
        if (maxWidth < currentMax) {
            maxWidth = currentMax
        }
        return maxWidth
    }

    private fun saveSelectionPosition(left: Float, top: Float, right: Float, bottom: Float) {
        mPrevBox.left = left.toInt()
        mPrevBox.right = right.toInt()
        mPrevBox.top = top.toInt()
        mPrevBox.bottom = bottom.toInt()
    }

    private fun setupTextRect(r: Rect) {
        if (r.bottom <= r.top || r.right <= r.left) {
            r.bottom = r.top
            r.right = r.left
            return
        }
        if (r.bottom - r.top > EVENT_TEXT_TOP_MARGIN + EVENT_TEXT_BOTTOM_MARGIN) {
            r.top += EVENT_TEXT_TOP_MARGIN
            r.bottom -= EVENT_TEXT_BOTTOM_MARGIN
        }
        if (r.right - r.left > EVENT_TEXT_LEFT_MARGIN + EVENT_TEXT_RIGHT_MARGIN) {
            r.left += EVENT_TEXT_LEFT_MARGIN
            r.right -= EVENT_TEXT_RIGHT_MARGIN
        }
    }

    private fun setupAllDayTextRect(r: Rect) {
        if (r.bottom <= r.top || r.right <= r.left) {
            r.bottom = r.top
            r.right = r.left
            return
        }
        if (r.bottom - r.top > EVENT_ALL_DAY_TEXT_TOP_MARGIN + EVENT_ALL_DAY_TEXT_BOTTOM_MARGIN) {
            r.top += EVENT_ALL_DAY_TEXT_TOP_MARGIN
            r.bottom -= EVENT_ALL_DAY_TEXT_BOTTOM_MARGIN
        }
        if (r.right - r.left > EVENT_ALL_DAY_TEXT_LEFT_MARGIN + EVENT_ALL_DAY_TEXT_RIGHT_MARGIN) {
            r.left += EVENT_ALL_DAY_TEXT_LEFT_MARGIN
            r.right -= EVENT_ALL_DAY_TEXT_RIGHT_MARGIN
        }
    }

    /**
     * Return the layout for a numbered event. Create it if not already existing
     */
    private fun getEventLayout(
        layouts: Array<StaticLayout?>?,
        i: Int,
        event: Event,
        paint: Paint,
        r: Rect
    ): StaticLayout? {
        if (i < 0 || i >= layouts!!.size) {
            return null
        }
        var layout: StaticLayout? = layouts[i]
        // Check if we have already initialized the StaticLayout and that
        // the width hasn't changed (due to vertical resizing which causes
        // re-layout of events at min height)
        if (layout == null || r.width() !== layout.getWidth()) {
            val bob = SpannableStringBuilder()
            if (event.title != null) {
                // MAX - 1 since we add a space
                bob.append(drawTextSanitizer(event.title.toString(),
                    MAX_EVENT_TEXT_LEN - 1))
                bob.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, bob.length, 0)
                bob.append(' ')
            }
            if (event.location != null) {
                bob.append(
                    drawTextSanitizer(
                        event.location.toString(),
                        MAX_EVENT_TEXT_LEN - bob.length
                    )
                )
            }
            when (event.selfAttendeeStatus) {
                Attendees.ATTENDEE_STATUS_INVITED -> paint.setColor(event.color)
                Attendees.ATTENDEE_STATUS_DECLINED -> {
                    paint.setColor(mEventTextColor)
                    paint.setAlpha(Utils.DECLINED_EVENT_TEXT_ALPHA)
                }
                Attendees.ATTENDEE_STATUS_NONE, Attendees.ATTENDEE_STATUS_ACCEPTED,
                    Attendees.ATTENDEE_STATUS_TENTATIVE -> paint.setColor(
                    mEventTextColor
                )
                else -> paint.setColor(mEventTextColor)
            }

            // Leave a one pixel boundary on the left and right of the rectangle for the event
            layout = StaticLayout(
                bob, 0, bob.length, TextPaint(paint), r.width(),
                Alignment.ALIGN_NORMAL, 1.0f, 0.0f, true, null, r.width()
            )
            layouts[i] = layout
        }
        layout.getPaint().setAlpha(mEventsAlpha)
        return layout
    }

    private fun drawAllDayEvents(firstDay: Int, numDays: Int, canvas: Canvas, p: Paint) {
        p.setTextSize(NORMAL_FONT_SIZE)
        p.setTextAlign(Paint.Align.LEFT)
        val eventTextPaint: Paint = mEventTextPaint
        val startY = DAY_HEADER_HEIGHT.toFloat()
        val stopY = startY + mAlldayHeight + ALLDAY_TOP_MARGIN
        var x = 0f
        var linesIndex = 0

        // Draw the inner vertical grid lines
        p.setColor(mCalendarGridLineInnerVerticalColor)
        x = mHoursWidth.toFloat()
        p.setStrokeWidth(GRID_LINE_INNER_WIDTH)
        // Line bounding the top of the all day area
        mLines[linesIndex++] = GRID_LINE_LEFT_MARGIN
        mLines[linesIndex++] = startY
        mLines[linesIndex++] = computeDayLeftPosition(mNumDays).toFloat()
        mLines[linesIndex++] = startY
        for (day in 0..mNumDays) {
            x = computeDayLeftPosition(day).toFloat()
            mLines[linesIndex++] = x
            mLines[linesIndex++] = startY
            mLines[linesIndex++] = x
            mLines[linesIndex++] = stopY
        }
        p.setAntiAlias(false)
        canvas.drawLines(mLines, 0, linesIndex, p)
        p.setStyle(Style.FILL)
        val y = DAY_HEADER_HEIGHT + ALLDAY_TOP_MARGIN
        val lastDay = firstDay + numDays - 1
        val events: ArrayList<Event>? = mAllDayEvents
        val numEvents: Int = events!!.size
        // Whether or not we should draw the more events text
        var hasMoreEvents = false
        // size of the allDay area
        val drawHeight = mAlldayHeight.toFloat()
        // max number of events being drawn in one day of the allday area
        var numRectangles = mMaxAlldayEvents.toFloat()
        // Where to cut off drawn allday events
        var allDayEventClip = DAY_HEADER_HEIGHT + mAlldayHeight + ALLDAY_TOP_MARGIN
        // The number of events that weren't drawn in each day
        mSkippedAlldayEvents = IntArray(numDays)
        if (mMaxAlldayEvents > mMaxUnexpandedAlldayEventCount &&
            !mShowAllAllDayEvents && mAnimateDayHeight == 0) {
            // We draw one fewer event than will fit so that more events text
            // can be drawn
            numRectangles = (mMaxUnexpandedAlldayEventCount - 1).toFloat()
            // We also clip the events above the more events text
            allDayEventClip -= MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT.toInt()
            hasMoreEvents = true
        } else if (mAnimateDayHeight != 0) {
            // clip at the end of the animating space
            allDayEventClip = DAY_HEADER_HEIGHT + mAnimateDayHeight + ALLDAY_TOP_MARGIN
        }
        var alpha: Int = eventTextPaint.getAlpha()
        eventTextPaint.setAlpha(mEventsAlpha)
        for (i in 0 until numEvents) {
            val event: Event = events.get(i)
            var startDay: Int = event.startDay
            var endDay: Int = event.endDay
            if (startDay > lastDay || endDay < firstDay) {
                continue
            }
            if (startDay < firstDay) {
                startDay = firstDay
            }
            if (endDay > lastDay) {
                endDay = lastDay
            }
            val startIndex = startDay - firstDay
            val endIndex = endDay - firstDay
            var height =
                if (mMaxAlldayEvents > mMaxUnexpandedAlldayEventCount)
                    mAnimateDayEventHeight.toFloat() else drawHeight / numRectangles

            // Prevent a single event from getting too big
            if (height > MAX_HEIGHT_OF_ONE_ALLDAY_EVENT) {
                height = MAX_HEIGHT_OF_ONE_ALLDAY_EVENT.toFloat()
            }

            // Leave a one-pixel space between the vertical day lines and the
            // event rectangle.
            event.left = computeDayLeftPosition(startIndex).toFloat()
            event.right = computeDayLeftPosition(endIndex + 1).toFloat() - DAY_GAP
            event.top = y + height * event.getColumn()
            event.bottom = event.top + height - ALL_DAY_EVENT_RECT_BOTTOM_MARGIN
            if (mMaxAlldayEvents > mMaxUnexpandedAlldayEventCount) {
                // check if we should skip this event. We skip if it starts
                // after the clip bound or ends after the skip bound and we're
                // not animating.
                if (event.top >= allDayEventClip) {
                    incrementSkipCount(mSkippedAlldayEvents, startIndex, endIndex)
                    continue
                } else if (event.bottom > allDayEventClip) {
                    if (hasMoreEvents) {
                        incrementSkipCount(mSkippedAlldayEvents, startIndex, endIndex)
                        continue
                    }
                    event.bottom = allDayEventClip.toFloat()
                }
            }
            val r: Rect = drawEventRect(
                event, canvas, p, eventTextPaint, event.top.toInt(),
                event.bottom.toInt()
            )
            setupAllDayTextRect(r)
            val layout: StaticLayout? = getEventLayout(mAllDayLayouts, i, event, eventTextPaint, r)
            drawEventText(layout, r, canvas, r.top, r.bottom, true)

            // Check if this all-day event intersects the selected day
            if (mSelectionAllday && mComputeSelectedEvents) {
                if (startDay <= mSelectionDay && endDay >= mSelectionDay) {
                    mSelectedEvents.add(event)
                }
            }
        }
        eventTextPaint.setAlpha(alpha)
        if (mMoreAlldayEventsTextAlpha != 0 && mSkippedAlldayEvents != null) {
            // If the more allday text should be visible, draw it.
            alpha = p.getAlpha()
            p.setAlpha(mEventsAlpha)
            p.setColor(mMoreAlldayEventsTextAlpha shl 24 and mMoreEventsTextColor)
            for (i in mSkippedAlldayEvents!!.indices) {
                if (mSkippedAlldayEvents!![i] > 0) {
                    drawMoreAlldayEvents(canvas, mSkippedAlldayEvents!![i], i, p)
                }
            }
            p.setAlpha(alpha)
        }
        if (mSelectionAllday) {
            // Compute the neighbors for the list of all-day events that
            // intersect the selected day.
            computeAllDayNeighbors()

            // Set the selection position to zero so that when we move down
            // to the normal event area, we will highlight the topmost event.
            saveSelectionPosition(0f, 0f, 0f, 0f)
        }
    }

    // Helper method for counting the number of allday events skipped on each day
    private fun incrementSkipCount(counts: IntArray?, startIndex: Int, endIndex: Int) {
        if (counts == null || startIndex < 0 || endIndex > counts.size) {
            return
        }
        for (i in startIndex..endIndex) {
            counts[i]++
        }
    }

    // Draws the "box +n" text for hidden allday events
    protected fun drawMoreAlldayEvents(canvas: Canvas, remainingEvents: Int, day: Int, p: Paint) {
        var x = computeDayLeftPosition(day) + EVENT_ALL_DAY_TEXT_LEFT_MARGIN
        var y = (mAlldayHeight - .5f * MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT - (.5f *
            EVENT_SQUARE_WIDTH) + DAY_HEADER_HEIGHT + ALLDAY_TOP_MARGIN).toInt()
        val r: Rect = mRect
        r.top = y
        r.left = x
        r.bottom = y + EVENT_SQUARE_WIDTH
        r.right = x + EVENT_SQUARE_WIDTH
        p.setColor(mMoreEventsTextColor)
        p.setStrokeWidth(EVENT_RECT_STROKE_WIDTH.toFloat())
        p.setStyle(Style.STROKE)
        p.setAntiAlias(false)
        canvas.drawRect(r, p)
        p.setAntiAlias(true)
        p.setStyle(Style.FILL)
        p.setTextSize(EVENT_TEXT_FONT_SIZE)
        val text: String =
            mResources.getQuantityString(R.plurals.month_more_events, remainingEvents)
        y += EVENT_SQUARE_WIDTH
        x += EVENT_SQUARE_WIDTH + EVENT_LINE_PADDING
        canvas.drawText(String.format(text, remainingEvents), x.toFloat(), y.toFloat(), p)
    }

    private fun computeAllDayNeighbors() {
        val len: Int = mSelectedEvents.size
        if (len == 0 || mSelectedEvent != null) {
            return
        }

        // First, clear all the links
        for (ii in 0 until len) {
            val ev: Event = mSelectedEvents.get(ii)
            ev.nextUp = null
            ev.nextDown = null
            ev.nextLeft = null
            ev.nextRight = null
        }

        // For each event in the selected event list "mSelectedEvents", find
        // its neighbors in the up and down directions. This could be done
        // more efficiently by sorting on the Event.getColumn() field, but
        // the list is expected to be very small.

        // Find the event in the same row as the previously selected all-day
        // event, if any.
        var startPosition = -1
        if (mPrevSelectedEvent != null && mPrevSelectedEvent!!.drawAsAllday()) {
            startPosition = mPrevSelectedEvent?.getColumn() as Int
        }
        var maxPosition = -1
        var startEvent: Event? = null
        var maxPositionEvent: Event? = null
        for (ii in 0 until len) {
            val ev: Event = mSelectedEvents.get(ii)
            val position: Int = ev.getColumn()
            if (position == startPosition) {
                startEvent = ev
            } else if (position > maxPosition) {
                maxPositionEvent = ev
                maxPosition = position
            }
            for (jj in 0 until len) {
                if (jj == ii) {
                    continue
                }
                val neighbor: Event = mSelectedEvents.get(jj)
                val neighborPosition: Int = neighbor.getColumn()
                if (neighborPosition == position - 1) {
                    ev.nextUp = neighbor
                } else if (neighborPosition == position + 1) {
                    ev.nextDown = neighbor
                }
            }
        }
        if (startEvent != null) {
            setSelectedEvent(startEvent)
        } else {
            setSelectedEvent(maxPositionEvent)
        }
    }

    private fun drawEvents(date: Int, dayIndex: Int, top: Int, canvas: Canvas, p: Paint) {
        val eventTextPaint: Paint = mEventTextPaint
        val left = computeDayLeftPosition(dayIndex) + 1
        val cellWidth = computeDayLeftPosition(dayIndex + 1) - left + 1
        val cellHeight = mCellHeight

        // Use the selected hour as the selection region
        val selectionArea: Rect = mSelectionRect
        selectionArea.top = top + mSelectionHour * (cellHeight + HOUR_GAP)
        selectionArea.bottom = selectionArea.top + cellHeight
        selectionArea.left = left
        selectionArea.right = selectionArea.left + cellWidth
        val events: ArrayList<Event> = mEvents
        val numEvents: Int = events.size
        val geometry: EventGeometry = mEventGeometry
        val viewEndY = mViewStartY + mViewHeight - DAY_HEADER_HEIGHT - mAlldayHeight
        val alpha: Int = eventTextPaint.getAlpha()
        eventTextPaint.setAlpha(mEventsAlpha)
        for (i in 0 until numEvents) {
            val event: Event = events.get(i)
            if (!geometry.computeEventRect(date, left, top, cellWidth, event)) {
                continue
            }

            // Don't draw it if it is not visible
            if (event.bottom < mViewStartY || event.top > viewEndY) {
                continue
            }
            if (date == mSelectionDay && !mSelectionAllday && mComputeSelectedEvents &&
                geometry.eventIntersectsSelection(event, selectionArea)
            ) {
                mSelectedEvents.add(event)
            }
            val r: Rect = drawEventRect(event, canvas, p, eventTextPaint, mViewStartY, viewEndY)
            setupTextRect(r)

            // Don't draw text if it is not visible
            if (r.top > viewEndY || r.bottom < mViewStartY) {
                continue
            }
            val layout: StaticLayout? = getEventLayout(mLayouts, i, event, eventTextPaint, r)
            // TODO: not sure why we are 4 pixels off
            drawEventText(
                layout,
                r,
                canvas,
                mViewStartY + 4,
                mViewStartY + mViewHeight - DAY_HEADER_HEIGHT - mAlldayHeight,
                false
            )
        }
        eventTextPaint.setAlpha(alpha)
    }

    private fun drawEventRect(
        event: Event,
        canvas: Canvas,
        p: Paint,
        eventTextPaint: Paint,
        visibleTop: Int,
        visibleBot: Int
    ): Rect {
        // Draw the Event Rect
        val r: Rect = mRect
        r.top = Math.max(event.top.toInt() + EVENT_RECT_TOP_MARGIN, visibleTop)
        r.bottom = Math.min(event.bottom.toInt() - EVENT_RECT_BOTTOM_MARGIN, visibleBot)
        r.left = event.left.toInt() + EVENT_RECT_LEFT_MARGIN
        r.right = event.right.toInt()
        var color: Int = event.color
        when (event.selfAttendeeStatus) {
            Attendees.ATTENDEE_STATUS_INVITED -> if (event !== mClickedEvent) {
                p.setStyle(Style.STROKE)
            }
            Attendees.ATTENDEE_STATUS_DECLINED -> {
                if (event !== mClickedEvent) {
                    color = Utils.getDeclinedColorFromColor(color)
                }
                p.setStyle(Style.FILL_AND_STROKE)
            }
            Attendees.ATTENDEE_STATUS_NONE, Attendees.ATTENDEE_STATUS_ACCEPTED,
                Attendees.ATTENDEE_STATUS_TENTATIVE -> p.setStyle(
                Style.FILL_AND_STROKE
            )
            else -> p.setStyle(Style.FILL_AND_STROKE)
        }
        p.setAntiAlias(false)
        val floorHalfStroke = Math.floor(EVENT_RECT_STROKE_WIDTH.toDouble() / 2.0).toInt()
        val ceilHalfStroke = Math.ceil(EVENT_RECT_STROKE_WIDTH.toDouble() / 2.0).toInt()
        r.top = Math.max(event.top.toInt() + EVENT_RECT_TOP_MARGIN + floorHalfStroke, visibleTop)
        r.bottom = Math.min(
            event.bottom.toInt() - EVENT_RECT_BOTTOM_MARGIN - ceilHalfStroke,
            visibleBot
        )
        r.left += floorHalfStroke
        r.right -= ceilHalfStroke
        p.setStrokeWidth(EVENT_RECT_STROKE_WIDTH.toFloat())
        p.setColor(color)
        val alpha: Int = p.getAlpha()
        p.setAlpha(mEventsAlpha)
        canvas.drawRect(r, p)
        p.setAlpha(alpha)
        p.setStyle(Style.FILL)

        // Setup rect for drawEventText which follows
        r.top = event.top.toInt() + EVENT_RECT_TOP_MARGIN
        r.bottom = event.bottom.toInt() - EVENT_RECT_BOTTOM_MARGIN
        r.left = event.left.toInt() + EVENT_RECT_LEFT_MARGIN
        r.right = event.right.toInt() - EVENT_RECT_RIGHT_MARGIN
        return r
    }

    private val drawTextSanitizerFilter: Pattern = Pattern.compile("[\t\n],")

    // Sanitize a string before passing it to drawText or else we get little
    // squares. For newlines and tabs before a comma, delete the character.
    // Otherwise, just replace them with a space.
    private fun drawTextSanitizer(string: String, maxEventTextLen: Int): String {
        var string = string
        val m: Matcher = drawTextSanitizerFilter.matcher(string)
        string = m.replaceAll(",")
        var len: Int = string.length
        if (maxEventTextLen <= 0) {
            string = ""
            len = 0
        } else if (len > maxEventTextLen) {
            string = string.substring(0, maxEventTextLen)
            len = maxEventTextLen
        }
        return string.replace('\n', ' ')
    }

    private fun drawEventText(
        eventLayout: StaticLayout?,
        rect: Rect,
        canvas: Canvas,
        top: Int,
        bottom: Int,
        center: Boolean
    ) {
        // drawEmptyRect(canvas, rect, 0xFFFF00FF); // for debugging
        val width: Int = rect.right - rect.left
        val height: Int = rect.bottom - rect.top

        // If the rectangle is too small for text, then return
        if (eventLayout == null || width < MIN_CELL_WIDTH_FOR_TEXT) {
            return
        }
        var totalLineHeight = 0
        val lineCount: Int = eventLayout.getLineCount()
        for (i in 0 until lineCount) {
            val lineBottom: Int = eventLayout.getLineBottom(i)
            totalLineHeight = if (lineBottom <= height) {
                lineBottom
            } else {
                break
            }
        }

        // + 2 is small workaround when the font is slightly bigger than the rect. This will
        // still allow the text to be shown without overflowing into the other all day rects.
        if (totalLineHeight == 0 || rect.top > bottom || rect.top + totalLineHeight + 2 < top) {
            return
        }

        // Use a StaticLayout to format the string.
        canvas.save()
        //  canvas.translate(rect.left, rect.top + (rect.bottom - rect.top / 2));
        val padding = if (center) (rect.bottom - rect.top - totalLineHeight) / 2 else 0
        canvas.translate(rect.left.toFloat(), rect.top.toFloat() + padding)
        rect.left = 0
        rect.right = width
        rect.top = 0
        rect.bottom = totalLineHeight

        // There's a bug somewhere. If this rect is outside of a previous
        // cliprect, this becomes a no-op. What happens is that the text draw
        // past the event rect. The current fix is to not draw the staticLayout
        // at all if it is completely out of bound.
        canvas.clipRect(rect)
        eventLayout.draw(canvas)
        canvas.restore()
    }

    // The following routines are called from the parent activity when certain
    // touch events occur.
    private fun doDown(ev: MotionEvent) {
        mTouchMode = TOUCH_MODE_DOWN
        mViewStartX = 0
        mOnFlingCalled = false
        mHandler?.removeCallbacks(mContinueScroll)
        val x = ev.getX().toInt()
        val y = ev.getY().toInt()

        // Save selection information: we use setSelectionFromPosition to find the selected event
        // in order to show the "clicked" color. But since it is also setting the selected info
        // for new events, we need to restore the old info after calling the function.
        val oldSelectedEvent: Event? = mSelectedEvent
        val oldSelectionDay = mSelectionDay
        val oldSelectionHour = mSelectionHour
        if (setSelectionFromPosition(x, y, false)) {
            // If a time was selected (a blue selection box is visible) and the click location
            // is in the selected time, do not show a click on an event to prevent a situation
            // of both a selection and an event are clicked when they overlap.
            val pressedSelected = (mSelectionMode != SELECTION_HIDDEN &&
                oldSelectionDay == mSelectionDay && oldSelectionHour == mSelectionHour)
            if (!pressedSelected && mSelectedEvent != null) {
                mSavedClickedEvent = mSelectedEvent
                mDownTouchTime = System.currentTimeMillis()
                postDelayed(mSetClick, mOnDownDelay.toLong())
            } else {
                eventClickCleanup()
            }
        }
        mSelectedEvent = oldSelectedEvent
        mSelectionDay = oldSelectionDay
        mSelectionHour = oldSelectionHour
        invalidate()
    }

    // Kicks off all the animations when the expand allday area is tapped
    private fun doExpandAllDayClick() {
        mShowAllAllDayEvents = !mShowAllAllDayEvents
        ObjectAnimator.setFrameDelay(0)

        // Determine the starting height
        if (mAnimateDayHeight == 0) {
            mAnimateDayHeight =
                if (mShowAllAllDayEvents) mAlldayHeight - MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT.toInt()
                else mAlldayHeight
        }
        // Cancel current animations
        mCancellingAnimations = true
        if (mAlldayAnimator != null) {
            mAlldayAnimator?.cancel()
        }
        if (mAlldayEventAnimator != null) {
            mAlldayEventAnimator?.cancel()
        }
        if (mMoreAlldayEventsAnimator != null) {
            mMoreAlldayEventsAnimator?.cancel()
        }
        mCancellingAnimations = false
        // get new animators
        mAlldayAnimator = allDayAnimator
        mAlldayEventAnimator = allDayEventAnimator
        mMoreAlldayEventsAnimator = ObjectAnimator.ofInt(
            this,
            "moreAllDayEventsTextAlpha",
            if (mShowAllAllDayEvents) MORE_EVENTS_MAX_ALPHA else 0,
            if (mShowAllAllDayEvents) 0 else MORE_EVENTS_MAX_ALPHA
        )

        // Set up delays and start the animators
        mAlldayAnimator?.setStartDelay(if (mShowAllAllDayEvents) ANIMATION_SECONDARY_DURATION
            else 0)
        mAlldayAnimator?.start()
        mMoreAlldayEventsAnimator?.setStartDelay(if (mShowAllAllDayEvents) 0
            else ANIMATION_DURATION)
        mMoreAlldayEventsAnimator?.setDuration(ANIMATION_SECONDARY_DURATION)
        mMoreAlldayEventsAnimator?.start()
        if (mAlldayEventAnimator != null) {
            // This is the only animator that can return null, so check it
            mAlldayEventAnimator
                ?.setStartDelay(if (mShowAllAllDayEvents) ANIMATION_SECONDARY_DURATION else 0)
            mAlldayEventAnimator?.start()
        }
    }

    /**
     * Figures out the initial heights for allDay events and space when
     * a view is being set up.
     */
    fun initAllDayHeights() {
        if (mMaxAlldayEvents <= mMaxUnexpandedAlldayEventCount) {
            return
        }
        if (mShowAllAllDayEvents) {
            var maxADHeight = mViewHeight - DAY_HEADER_HEIGHT - MIN_HOURS_HEIGHT
            maxADHeight = Math.min(
                maxADHeight,
                (mMaxAlldayEvents * MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT).toInt()
            )
            mAnimateDayEventHeight = maxADHeight / mMaxAlldayEvents
        } else {
            mAnimateDayEventHeight = MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT.toInt()
        }
    } // First calculate the absolute max height
    // Now expand to fit but not beyond the absolute max
    // calculate the height of individual events in order to fit
    // if there's nothing to animate just return

    // Set up the animator with the calculated values
    // Sets up an animator for changing the height of allday events
    private val allDayEventAnimator: ObjectAnimator?
        private get() {
            // First calculate the absolute max height
            var maxADHeight = mViewHeight - DAY_HEADER_HEIGHT - MIN_HOURS_HEIGHT
            // Now expand to fit but not beyond the absolute max
            maxADHeight = Math.min(
                maxADHeight,
                (mMaxAlldayEvents * MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT).toInt()
            )
            // calculate the height of individual events in order to fit
            val fitHeight = maxADHeight / mMaxAlldayEvents
            val currentHeight = mAnimateDayEventHeight
            val desiredHeight =
                if (mShowAllAllDayEvents) fitHeight else MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT.toInt()
            // if there's nothing to animate just return
            if (currentHeight == desiredHeight) {
                return null
            }

            // Set up the animator with the calculated values
            val animator: ObjectAnimator = ObjectAnimator.ofInt(
                this, "animateDayEventHeight",
                currentHeight, desiredHeight
            )
            animator.setDuration(ANIMATION_DURATION)
            return animator
        }

    // Set up the animator with the calculated values
    // Sets up an animator for changing the height of the allday area
    private val allDayAnimator: ObjectAnimator
        private get() {
            // Calculate the absolute max height
            var maxADHeight = mViewHeight - DAY_HEADER_HEIGHT - MIN_HOURS_HEIGHT
            // Find the desired height but don't exceed abs max
            maxADHeight = Math.min(
                maxADHeight,
                (mMaxAlldayEvents * MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT).toInt()
            )
            // calculate the current and desired heights
            val currentHeight = if (mAnimateDayHeight != 0) mAnimateDayHeight else mAlldayHeight
            val desiredHeight =
                if (mShowAllAllDayEvents) maxADHeight else (MAX_UNEXPANDED_ALLDAY_HEIGHT -
                    MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT - 1).toInt()

            // Set up the animator with the calculated values
            val animator: ObjectAnimator = ObjectAnimator.ofInt(
                this, "animateDayHeight",
                currentHeight, desiredHeight
            )
            animator.setDuration(ANIMATION_DURATION)
            animator.addListener(object : AnimatorListenerAdapter() {
                @Override
                override fun onAnimationEnd(animation: Animator) {
                    if (!mCancellingAnimations) {
                        // when finished, set this to 0 to signify not animating
                        mAnimateDayHeight = 0
                        mUseExpandIcon = !mShowAllAllDayEvents
                    }
                    mRemeasure = true
                    invalidate()
                }
            })
            return animator
        }

    // setter for the 'box +n' alpha text used by the animator
    fun setMoreAllDayEventsTextAlpha(alpha: Int) {
        mMoreAlldayEventsTextAlpha = alpha
        invalidate()
    }

    // setter for the height of the allday area used by the animator
    fun setAnimateDayHeight(height: Int) {
        mAnimateDayHeight = height
        mRemeasure = true
        invalidate()
    }

    // setter for the height of allday events used by the animator
    fun setAnimateDayEventHeight(height: Int) {
        mAnimateDayEventHeight = height
        mRemeasure = true
        invalidate()
    }

    private fun doSingleTapUp(ev: MotionEvent) {
        if (!mHandleActionUp || mScrolling) {
            return
        }
        val x = ev.getX().toInt()
        val y = ev.getY().toInt()
        val selectedDay = mSelectionDay
        val selectedHour = mSelectionHour
        if (mMaxAlldayEvents > mMaxUnexpandedAlldayEventCount) {
            // check if the tap was in the allday expansion area
            val bottom = mFirstCell
            if (x < mHoursWidth && y > DAY_HEADER_HEIGHT && y < DAY_HEADER_HEIGHT + mAlldayHeight ||
                !mShowAllAllDayEvents && mAnimateDayHeight == 0 && y < bottom && y >= bottom -
                MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT
            ) {
                doExpandAllDayClick()
                return
            }
        }
        val validPosition = setSelectionFromPosition(x, y, false)
        if (!validPosition) {
            if (y < DAY_HEADER_HEIGHT) {
                val selectedTime = Time(mBaseDate)
                selectedTime.setJulianDay(mSelectionDay)
                selectedTime.hour = mSelectionHour
                selectedTime.normalize(true /* ignore isDst */)
                mController.sendEvent(
                    this as? Object, EventType.GO_TO, null, null, selectedTime, -1,
                    ViewType.DAY, CalendarController.EXTRA_GOTO_DATE, null, null
                )
            }
            return
        }
        val hasSelection = mSelectionMode != SELECTION_HIDDEN
        val pressedSelected = ((hasSelection || mTouchExplorationEnabled) &&
            selectedDay == mSelectionDay && selectedHour == mSelectionHour)
        if (mSelectedEvent != null) {
            // If the tap is on an event, launch the "View event" view
            if (mIsAccessibilityEnabled) {
                mAccessibilityMgr?.interrupt()
            }
            mSelectionMode = SELECTION_HIDDEN
            var yLocation = ((mSelectedEvent!!.top + mSelectedEvent!!.bottom) / 2) as Int
            // Y location is affected by the position of the event in the scrolling
            // view (mViewStartY) and the presence of all day events (mFirstCell)
            if (!mSelectedEvent!!.allDay) {
                yLocation += mFirstCell - mViewStartY
            }
            mClickedYLocation = yLocation
            val clearDelay: Long = CLICK_DISPLAY_DURATION + mOnDownDelay -
                (System.currentTimeMillis() - mDownTouchTime)
            if (clearDelay > 0) {
                this.postDelayed(mClearClick, clearDelay)
            } else {
                this.post(mClearClick)
            }
        }
        invalidate()
    }

    private fun doLongPress(ev: MotionEvent) {
        eventClickCleanup()
        if (mScrolling) {
            return
        }

        // Scale gesture in progress
        if (mStartingSpanY != 0f) {
            return
        }
        val x = ev.getX().toInt()
        val y = ev.getY().toInt()
        val validPosition = setSelectionFromPosition(x, y, false)
        if (!validPosition) {
            // return if the touch wasn't on an area of concern
            return
        }
        invalidate()
        performLongClick()
    }

    private fun doScroll(e1: MotionEvent?, e2: MotionEvent, deltaX: Float, deltaY: Float) {
        cancelAnimation()
        if (mStartingScroll) {
            mInitialScrollX = 0f
            mInitialScrollY = 0f
            mStartingScroll = false
        }
        mInitialScrollX += deltaX
        mInitialScrollY += deltaY
        val distanceX = mInitialScrollX.toInt()
        val distanceY = mInitialScrollY.toInt()
        val focusY = getAverageY(e2)
        if (mRecalCenterHour) {
            // Calculate the hour that correspond to the average of the Y touch points
            mGestureCenterHour = ((mViewStartY + focusY - DAY_HEADER_HEIGHT - mAlldayHeight) /
                (mCellHeight + DAY_GAP))
            mRecalCenterHour = false
        }

        // If we haven't figured out the predominant scroll direction yet,
        // then do it now.
        if (mTouchMode == TOUCH_MODE_DOWN) {
            val absDistanceX: Int = Math.abs(distanceX)
            val absDistanceY: Int = Math.abs(distanceY)
            mScrollStartY = mViewStartY
            mPreviousDirection = 0
            if (absDistanceX > absDistanceY) {
                val slopFactor = if (mScaleGestureDetector.isInProgress()) 20 else 2
                if (absDistanceX > mScaledPagingTouchSlop * slopFactor) {
                    mTouchMode = TOUCH_MODE_HSCROLL
                    mViewStartX = distanceX
                    initNextView(-mViewStartX)
                }
            } else {
                mTouchMode = TOUCH_MODE_VSCROLL
            }
        } else if (mTouchMode and TOUCH_MODE_HSCROLL != 0) {
            // We are already scrolling horizontally, so check if we
            // changed the direction of scrolling so that the other week
            // is now visible.
            mViewStartX = distanceX
            if (distanceX != 0) {
                val direction = if (distanceX > 0) 1 else -1
                if (direction != mPreviousDirection) {
                    // The user has switched the direction of scrolling
                    // so re-init the next view
                    initNextView(-mViewStartX)
                    mPreviousDirection = direction
                }
            }
        }
        if (mTouchMode and TOUCH_MODE_VSCROLL != 0) {
            // Calculate the top of the visible region in the calendar grid.
            // Increasing/decrease this will scroll the calendar grid up/down.
            mViewStartY = ((mGestureCenterHour * (mCellHeight + DAY_GAP) -
                focusY) + DAY_HEADER_HEIGHT + mAlldayHeight).toInt()

            // If dragging while already at the end, do a glow
            val pulledToY = (mScrollStartY + deltaY).toInt()
            if (pulledToY < 0) {
                mEdgeEffectTop.onPull(deltaY / mViewHeight)
                if (!mEdgeEffectBottom.isFinished()) {
                    mEdgeEffectBottom.onRelease()
                }
            } else if (pulledToY > mMaxViewStartY) {
                mEdgeEffectBottom.onPull(deltaY / mViewHeight)
                if (!mEdgeEffectTop.isFinished()) {
                    mEdgeEffectTop.onRelease()
                }
            }
            if (mViewStartY < 0) {
                mViewStartY = 0
                mRecalCenterHour = true
            } else if (mViewStartY > mMaxViewStartY) {
                mViewStartY = mMaxViewStartY
                mRecalCenterHour = true
            }
            if (mRecalCenterHour) {
                // Calculate the hour that correspond to the average of the Y touch points
                mGestureCenterHour = ((mViewStartY + focusY - DAY_HEADER_HEIGHT - mAlldayHeight) /
                    (mCellHeight + DAY_GAP))
                mRecalCenterHour = false
            }
            computeFirstHour()
        }
        mScrolling = true
        mSelectionMode = SELECTION_HIDDEN
        invalidate()
    }

    private fun getAverageY(me: MotionEvent): Float {
        val count: Int = me.getPointerCount()
        var focusY = 0f
        for (i in 0 until count) {
            focusY += me.getY(i)
        }
        focusY /= count.toFloat()
        return focusY
    }

    private fun cancelAnimation() {
        val `in`: Animation? = mViewSwitcher.getInAnimation()
        if (`in` != null) {
            // cancel() doesn't terminate cleanly.
            `in`.scaleCurrentDuration(0f)
        }
        val out: Animation? = mViewSwitcher.getOutAnimation()
        if (out != null) {
            // cancel() doesn't terminate cleanly.
            out.scaleCurrentDuration(0f)
        }
    }

    private fun doFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float) {
        cancelAnimation()
        mSelectionMode = SELECTION_HIDDEN
        eventClickCleanup()
        mOnFlingCalled = true
        if (mTouchMode and TOUCH_MODE_HSCROLL != 0) {
            // Horizontal fling.
            // initNextView(deltaX);
            mTouchMode = TOUCH_MODE_INITIAL_STATE
            if (DEBUG) Log.d(TAG, "doFling: velocityX $velocityX")
            val deltaX = e2.getX().toInt() - e1!!.getX().toInt()
            switchViews(deltaX < 0, mViewStartX.toFloat(), mViewWidth.toFloat(), velocityX)
            mViewStartX = 0
            return
        }
        if (mTouchMode and TOUCH_MODE_VSCROLL == 0) {
            if (DEBUG) Log.d(TAG, "doFling: no fling")
            return
        }

        // Vertical fling.
        mTouchMode = TOUCH_MODE_INITIAL_STATE
        mViewStartX = 0
        if (DEBUG) {
            Log.d(TAG, "doFling: mViewStartY$mViewStartY velocityY $velocityY")
        }

        // Continue scrolling vertically
        mScrolling = true
        mScroller.fling(
            0 /* startX */, mViewStartY /* startY */, 0 /* velocityX */,
            (-velocityY).toInt(), 0 /* minX */, 0 /* maxX */, 0 /* minY */,
            mMaxViewStartY /* maxY */, OVERFLING_DISTANCE, OVERFLING_DISTANCE
        )

        // When flinging down, show a glow when it hits the end only if it
        // wasn't started at the top
        if (velocityY > 0 && mViewStartY != 0) {
            mCallEdgeEffectOnAbsorb = true
        } else if (velocityY < 0 && mViewStartY != mMaxViewStartY) {
            mCallEdgeEffectOnAbsorb = true
        }
        mHandler?.post(mContinueScroll)
    }

    private fun initNextView(deltaX: Int): Boolean {
        // Change the view to the previous day or week
        val view = mViewSwitcher.getNextView() as DayView
        val date: Time? = view.mBaseDate
        date?.set(mBaseDate)
        val switchForward: Boolean
        if (deltaX > 0) {
            date!!.monthDay -= mNumDays
            view.setSelectedDay(mSelectionDay - mNumDays)
            switchForward = false
        } else {
            date!!.monthDay += mNumDays
            view.setSelectedDay(mSelectionDay + mNumDays)
            switchForward = true
        }
        date?.normalize(true /* ignore isDst */)
        initView(view)
        view.layout(getLeft(), getTop(), getRight(), getBottom())
        view.reloadEvents()
        return switchForward
    }

    // ScaleGestureDetector.OnScaleGestureListener
    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        mHandleActionUp = false
        val gestureCenterInPixels: Float = detector.getFocusY() - DAY_HEADER_HEIGHT - mAlldayHeight
        mGestureCenterHour = (mViewStartY + gestureCenterInPixels) / (mCellHeight + DAY_GAP)
        mStartingSpanY = Math.max(MIN_Y_SPAN.toFloat(),
            Math.abs(detector.getCurrentSpanY().toFloat()))
        mCellHeightBeforeScaleGesture = mCellHeight
        if (DEBUG_SCALING) {
            val ViewStartHour = mViewStartY / (mCellHeight + DAY_GAP).toFloat()
            Log.d(
                TAG, "onScaleBegin: mGestureCenterHour:" + mGestureCenterHour +
                    "\tViewStartHour: " + ViewStartHour + "\tmViewStartY:" + mViewStartY +
                    "\tmCellHeight:" + mCellHeight + " SpanY:" + detector.getCurrentSpanY()
            )
        }
        return true
    }

    // ScaleGestureDetector.OnScaleGestureListener
    override fun onScale(detector: ScaleGestureDetector): Boolean {
        val spanY: Float = Math.max(MIN_Y_SPAN.toFloat(),
            Math.abs(detector.getCurrentSpanY().toFloat()))
        mCellHeight = (mCellHeightBeforeScaleGesture * spanY / mStartingSpanY).toInt()
        if (mCellHeight < mMinCellHeight) {
            // If mStartingSpanY is too small, even a small increase in the
            // gesture can bump the mCellHeight beyond MAX_CELL_HEIGHT
            mStartingSpanY = spanY
            mCellHeight = mMinCellHeight
            mCellHeightBeforeScaleGesture = mMinCellHeight
        } else if (mCellHeight > MAX_CELL_HEIGHT) {
            mStartingSpanY = spanY
            mCellHeight = MAX_CELL_HEIGHT
            mCellHeightBeforeScaleGesture = MAX_CELL_HEIGHT
        }
        val gestureCenterInPixels = detector.getFocusY().toInt() - DAY_HEADER_HEIGHT - mAlldayHeight
        mViewStartY = (mGestureCenterHour * (mCellHeight + DAY_GAP)).toInt() - gestureCenterInPixels
        mMaxViewStartY = HOUR_GAP + 24 * (mCellHeight + HOUR_GAP) - mGridAreaHeight
        if (DEBUG_SCALING) {
            val ViewStartHour = mViewStartY / (mCellHeight + DAY_GAP).toFloat()
            Log.d(
                TAG, "onScale: mGestureCenterHour:" + mGestureCenterHour + "\tViewStartHour: " +
                    ViewStartHour + "\tmViewStartY:" + mViewStartY + "\tmCellHeight:" +
                    mCellHeight + " SpanY:" + detector.getCurrentSpanY()
            )
        }
        if (mViewStartY < 0) {
            mViewStartY = 0
            mGestureCenterHour = ((mViewStartY + gestureCenterInPixels) /
                (mCellHeight + DAY_GAP).toFloat())
        } else if (mViewStartY > mMaxViewStartY) {
            mViewStartY = mMaxViewStartY
            mGestureCenterHour = ((mViewStartY + gestureCenterInPixels) /
                (mCellHeight + DAY_GAP).toFloat())
        }
        computeFirstHour()
        mRemeasure = true
        invalidate()
        return true
    }

    // ScaleGestureDetector.OnScaleGestureListener
    override fun onScaleEnd(detector: ScaleGestureDetector) {
        mScrollStartY = mViewStartY
        mInitialScrollY = 0f
        mInitialScrollX = 0f
        mStartingSpanY = 0f
    }

    @Override
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val action: Int = ev.getAction()
        if (DEBUG) Log.e(TAG, "" + action + " ev.getPointerCount() = " + ev.getPointerCount())
        if (ev.getActionMasked() === MotionEvent.ACTION_DOWN ||
            ev.getActionMasked() === MotionEvent.ACTION_UP ||
            ev.getActionMasked() === MotionEvent.ACTION_POINTER_UP ||
            ev.getActionMasked() === MotionEvent.ACTION_POINTER_DOWN
        ) {
            mRecalCenterHour = true
        }
        if (mTouchMode and TOUCH_MODE_HSCROLL == 0) {
            mScaleGestureDetector.onTouchEvent(ev)
        }
        return when (action) {
            MotionEvent.ACTION_DOWN -> {
                mStartingScroll = true
                if (DEBUG) {
                    Log.e(
                        TAG,
                        "ACTION_DOWN ev.getDownTime = " + ev.getDownTime().toString() + " Cnt=" +
                            ev.getPointerCount()
                    )
                }
                val bottom =
                    mAlldayHeight + DAY_HEADER_HEIGHT + ALLDAY_TOP_MARGIN
                mTouchStartedInAlldayArea = if (ev.getY() < bottom) {
                    true
                } else {
                    false
                }
                mHandleActionUp = true
                mGestureDetector.onTouchEvent(ev)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (DEBUG) Log.e(
                    TAG,
                    "ACTION_MOVE Cnt=" + ev.getPointerCount() + this@DayView
                )
                mGestureDetector.onTouchEvent(ev)
                true
            }
            MotionEvent.ACTION_UP -> {
                if (DEBUG) Log.e(
                    TAG,
                    "ACTION_UP Cnt=" + ev.getPointerCount() + mHandleActionUp
                )
                mEdgeEffectTop.onRelease()
                mEdgeEffectBottom.onRelease()
                mStartingScroll = false
                mGestureDetector.onTouchEvent(ev)
                if (!mHandleActionUp) {
                    mHandleActionUp = true
                    mViewStartX = 0
                    invalidate()
                    return true
                }
                if (mOnFlingCalled) {
                    return true
                }

                // If we were scrolling, then reset the selected hour so that it
                // is visible.
                if (mScrolling) {
                    mScrolling = false
                    resetSelectedHour()
                    invalidate()
                }
                if (mTouchMode and TOUCH_MODE_HSCROLL != 0) {
                    mTouchMode = TOUCH_MODE_INITIAL_STATE
                    if (Math.abs(mViewStartX) > mHorizontalSnapBackThreshold) {
                        // The user has gone beyond the threshold so switch views
                        if (DEBUG) Log.d(
                            TAG,
                            "- horizontal scroll: switch views"
                        )
                        switchViews(
                            mViewStartX > 0,
                            mViewStartX.toFloat(),
                            mViewWidth.toFloat(),
                            0f
                        )
                        mViewStartX = 0
                        return true
                    } else {
                        // Not beyond the threshold so invalidate which will cause
                        // the view to snap back. Also call recalc() to ensure
                        // that we have the correct starting date and title.
                        if (DEBUG) Log.d(
                            TAG,
                            "- horizontal scroll: snap back"
                        )
                        recalc()
                        invalidate()
                        mViewStartX = 0
                    }
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (DEBUG) Log.e(
                    TAG,
                    "ACTION_CANCEL"
                )
                mGestureDetector.onTouchEvent(ev)
                mScrolling = false
                resetSelectedHour()
                true
            }
            else -> {
                if (DEBUG) Log.e(
                    TAG,
                    "Not MotionEvent " + ev.toString()
                )
                if (mGestureDetector.onTouchEvent(ev)) {
                    true
                } else super.onTouchEvent(ev)
            }
        }
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View?, menuInfo: ContextMenuInfo?) {
        var item: MenuItem

        // If the trackball is held down, then the context menu pops up and
        // we never get onKeyUp() for the long-press. So check for it here
        // and change the selection to the long-press state.
        if (mSelectionMode != SELECTION_LONGPRESS) {
            invalidate()
        }
        val startMillis = selectedTimeInMillis
        val flags: Int = (DateUtils.FORMAT_SHOW_TIME
            or DateUtils.FORMAT_CAP_NOON_MIDNIGHT
            or DateUtils.FORMAT_SHOW_WEEKDAY)
        val title: String? = Utils.formatDateRange(mContext, startMillis, startMillis, flags)
        menu.setHeaderTitle(title)
        mPopup?.dismiss()
    }

    /**
     * Sets mSelectionDay and mSelectionHour based on the (x,y) touch position.
     * If the touch position is not within the displayed grid, then this
     * method returns false.
     *
     * @param x the x position of the touch
     * @param y the y position of the touch
     * @param keepOldSelection - do not change the selection info (used for invoking accessibility
     * messages)
     * @return true if the touch position is valid
     */
    private fun setSelectionFromPosition(x: Int, y: Int, keepOldSelection: Boolean): Boolean {
        var x = x
        var savedEvent: Event? = null
        var savedDay = 0
        var savedHour = 0
        var savedAllDay = false
        if (keepOldSelection) {
            // Store selection info and restore it at the end. This way, we can invoke the
            // right accessibility message without affecting the selection.
            savedEvent = mSelectedEvent
            savedDay = mSelectionDay
            savedHour = mSelectionHour
            savedAllDay = mSelectionAllday
        }
        if (x < mHoursWidth) {
            x = mHoursWidth
        }
        var day = (x - mHoursWidth) / (mCellWidth + DAY_GAP)
        if (day >= mNumDays) {
            day = mNumDays - 1
        }
        day += mFirstJulianDay
        setSelectedDay(day)
        if (y < DAY_HEADER_HEIGHT) {
            sendAccessibilityEventAsNeeded(false)
            return false
        }
        setSelectedHour(mFirstHour) /* First fully visible hour */
        mSelectionAllday = if (y < mFirstCell) {
            true
        } else {
            // y is now offset from top of the scrollable region
            val adjustedY = y - mFirstCell
            if (adjustedY < mFirstHourOffset) {
                setSelectedHour(mSelectionHour - 1) /* In the partially visible hour */
            } else {
                setSelectedHour(
                    mSelectionHour +
                        (adjustedY - mFirstHourOffset) / (mCellHeight + HOUR_GAP)
                )
            }
            false
        }
        findSelectedEvent(x, y)
        sendAccessibilityEventAsNeeded(true)

        // Restore old values
        if (keepOldSelection) {
            mSelectedEvent = savedEvent
            mSelectionDay = savedDay
            mSelectionHour = savedHour
            mSelectionAllday = savedAllDay
        }
        return true
    }

    private fun findSelectedEvent(x: Int, y: Int) {
        var y = y
        val date = mSelectionDay
        val cellWidth = mCellWidth
        var events: ArrayList<Event>? = mEvents
        var numEvents: Int = events!!.size
        val left = computeDayLeftPosition(mSelectionDay - mFirstJulianDay)
        val top = 0
        setSelectedEvent(null)
        mSelectedEvents.clear()
        if (mSelectionAllday) {
            var yDistance: Float
            var minYdistance = 10000.0f // any large number
            var closestEvent: Event? = null
            val drawHeight = mAlldayHeight.toFloat()
            val yOffset = DAY_HEADER_HEIGHT + ALLDAY_TOP_MARGIN
            var maxUnexpandedColumn = mMaxUnexpandedAlldayEventCount
            if (mMaxAlldayEvents > mMaxUnexpandedAlldayEventCount) {
                // Leave a gap for the 'box +n' text
                maxUnexpandedColumn--
            }
            events = mAllDayEvents
            numEvents = events!!.size
            for (i in 0 until numEvents) {
                val event: Event? = events.get(i)
                if (!event!!.drawAsAllday() ||
                    !mShowAllAllDayEvents && event.getColumn() >= maxUnexpandedColumn
                ) {
                    // Don't check non-allday events or events that aren't shown
                    continue
                }
                if (event.startDay <= mSelectionDay && event.endDay >= mSelectionDay) {
                    val numRectangles =
                        if (mShowAllAllDayEvents) mMaxAlldayEvents.toFloat()
                        else mMaxUnexpandedAlldayEventCount.toFloat()
                    var height = drawHeight / numRectangles
                    if (height > MAX_HEIGHT_OF_ONE_ALLDAY_EVENT) {
                        height = MAX_HEIGHT_OF_ONE_ALLDAY_EVENT.toFloat()
                    }
                    val eventTop: Float = yOffset + height * event.getColumn()
                    val eventBottom = eventTop + height
                    if (eventTop < y && eventBottom > y) {
                        // If the touch is inside the event rectangle, then
                        // add the event.
                        mSelectedEvents.add(event)
                        closestEvent = event
                        break
                    } else {
                        // Find the closest event
                        yDistance = if (eventTop >= y) {
                            eventTop - y
                        } else {
                            y - eventBottom
                        }
                        if (yDistance < minYdistance) {
                            minYdistance = yDistance
                            closestEvent = event
                        }
                    }
                }
            }
            setSelectedEvent(closestEvent)
            return
        }

        // Adjust y for the scrollable bitmap
        y += mViewStartY - mFirstCell

        // Use a region around (x,y) for the selection region
        val region: Rect = mRect
        region.left = x - 10
        region.right = x + 10
        region.top = y - 10
        region.bottom = y + 10
        val geometry: EventGeometry = mEventGeometry
        for (i in 0 until numEvents) {
            val event: Event? = events.get(i)
            // Compute the event rectangle.
            if (!geometry.computeEventRect(date, left, top, cellWidth, event as Event)) {
                continue
            }

            // If the event intersects the selection region, then add it to
            // mSelectedEvents.
            if (geometry.eventIntersectsSelection(event as Event, region)) {
                mSelectedEvents.add(event as Event)
            }
        }

        // If there are any events in the selected region, then assign the
        // closest one to mSelectedEvent.
        if (mSelectedEvents.size > 0) {
            val len: Int = mSelectedEvents.size
            var closestEvent: Event? = null
            var minDist = (mViewWidth + mViewHeight).toFloat() // some large distance
            for (index in 0 until len) {
                val ev: Event? = mSelectedEvents.get(index)
                val dist: Float = geometry.pointToEvent(x.toFloat(), y.toFloat(), ev as Event)
                if (dist < minDist) {
                    minDist = dist
                    closestEvent = ev
                }
            }
            setSelectedEvent(closestEvent)

            // Keep the selected hour and day consistent with the selected
            // event. They could be different if we touched on an empty hour
            // slot very close to an event in the previous hour slot. In
            // that case we will select the nearby event.
            val startDay: Int = mSelectedEvent!!.startDay
            val endDay: Int = mSelectedEvent!!.endDay
            if (mSelectionDay < startDay) {
                setSelectedDay(startDay)
            } else if (mSelectionDay > endDay) {
                setSelectedDay(endDay)
            }
            val startHour: Int = mSelectedEvent!!.startTime / 60
            val endHour: Int
            endHour = if (mSelectedEvent!!.startTime < mSelectedEvent!!.endTime) {
                (mSelectedEvent!!.endTime - 1) / 60
            } else {
                mSelectedEvent!!.endTime / 60
            }
            if (mSelectionHour < startHour && mSelectionDay == startDay) {
                setSelectedHour(startHour)
            } else if (mSelectionHour > endHour && mSelectionDay == endDay) {
                setSelectedHour(endHour)
            }
        }
    }

    // Encapsulates the code to continue the scrolling after the
    // finger is lifted. Instead of stopping the scroll immediately,
    // the scroll continues to "free spin" and gradually slows down.
    private inner class ContinueScroll : Runnable {
        override fun run() {
            mScrolling = mScrolling && mScroller.computeScrollOffset()
            if (!mScrolling || mPaused) {
                resetSelectedHour()
                invalidate()
                return
            }
            mViewStartY = mScroller.getCurrY()
            if (mCallEdgeEffectOnAbsorb) {
                if (mViewStartY < 0) {
                    mEdgeEffectTop.onAbsorb(mLastVelocity.toInt())
                    mCallEdgeEffectOnAbsorb = false
                } else if (mViewStartY > mMaxViewStartY) {
                    mEdgeEffectBottom.onAbsorb(mLastVelocity.toInt())
                    mCallEdgeEffectOnAbsorb = false
                }
                mLastVelocity = mScroller.getCurrVelocity()
            }
            if (mScrollStartY == 0 || mScrollStartY == mMaxViewStartY) {
                // Allow overscroll/springback only on a fling,
                // not a pull/fling from the end
                if (mViewStartY < 0) {
                    mViewStartY = 0
                } else if (mViewStartY > mMaxViewStartY) {
                    mViewStartY = mMaxViewStartY
                }
            }
            computeFirstHour()
            mHandler?.post(this)
            invalidate()
        }
    }

    /**
     * Cleanup the pop-up and timers.
     */
    fun cleanup() {
        // Protect against null-pointer exceptions
        if (mPopup != null) {
            mPopup?.dismiss()
        }
        mPaused = true
        mLastPopupEventID = INVALID_EVENT_ID
        if (mHandler != null) {
            mHandler?.removeCallbacks(mDismissPopup)
            mHandler?.removeCallbacks(mUpdateCurrentTime)
        }
        Utils.setSharedPreference(
            mContext, GeneralPreferences.KEY_DEFAULT_CELL_HEIGHT,
            mCellHeight
        )
        // Clear all click animations
        eventClickCleanup()
        // Turn off redraw
        mRemeasure = false
        // Turn off scrolling to make sure the view is in the correct state if we fling back to it
        mScrolling = false
    }

    private fun eventClickCleanup() {
        this.removeCallbacks(mClearClick)
        this.removeCallbacks(mSetClick)
        mClickedEvent = null
        mSavedClickedEvent = null
    }

    private fun setSelectedEvent(e: Event?) {
        mSelectedEvent = e
        mSelectedEventForAccessibility = e
    }

    private fun setSelectedHour(h: Int) {
        mSelectionHour = h
        mSelectionHourForAccessibility = h
    }

    private fun setSelectedDay(d: Int) {
        mSelectionDay = d
        mSelectionDayForAccessibility = d
    }

    /**
     * Restart the update timer
     */
    fun restartCurrentTimeUpdates() {
        mPaused = false
        if (mHandler != null) {
            mHandler?.removeCallbacks(mUpdateCurrentTime)
            mHandler?.post(mUpdateCurrentTime)
        }
    }

    @Override
    protected override fun onDetachedFromWindow() {
        cleanup()
        super.onDetachedFromWindow()
    }

    internal inner class DismissPopup : Runnable {
        override fun run() {
            // Protect against null-pointer exceptions
            if (mPopup != null) {
                mPopup?.dismiss()
            }
        }
    }

    internal inner class UpdateCurrentTime : Runnable {
        override fun run() {
            val currentTime: Long = System.currentTimeMillis()
            mCurrentTime?.set(currentTime)
            // % causes update to occur on 5 minute marks (11:10, 11:15, 11:20, etc.)
            if (!mPaused) {
                mHandler?.postDelayed(
                    mUpdateCurrentTime, UPDATE_CURRENT_TIME_DELAY -
                        currentTime % UPDATE_CURRENT_TIME_DELAY
                )
            }
            mTodayJulianDay = Time.getJulianDay(currentTime, mCurrentTime!!.gmtoff)
            invalidate()
        }
    }

    internal inner class CalendarGestureListener : GestureDetector.SimpleOnGestureListener() {
        @Override
        override fun onSingleTapUp(ev: MotionEvent): Boolean {
            if (DEBUG) Log.e(TAG, "GestureDetector.onSingleTapUp")
            doSingleTapUp(ev)
            return true
        }

        @Override
        override fun onLongPress(ev: MotionEvent) {
            if (DEBUG) Log.e(TAG, "GestureDetector.onLongPress")
            doLongPress(ev)
        }

        @Override
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            var distanceY = distanceY
            if (DEBUG) Log.e(TAG, "GestureDetector.onScroll")
            eventClickCleanup()
            if (mTouchStartedInAlldayArea) {
                if (Math.abs(distanceX) < Math.abs(distanceY)) {
                    // Make sure that click feedback is gone when you scroll from the
                    // all day area
                    invalidate()
                    return false
                }
                // don't scroll vertically if this started in the allday area
                distanceY = 0f
            }
            doScroll(e1, e2, distanceX, distanceY)
            return true
        }

        @Override
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            var velocityY = velocityY
            if (DEBUG) Log.e(TAG, "GestureDetector.onFling")
            if (mTouchStartedInAlldayArea) {
                if (Math.abs(velocityX) < Math.abs(velocityY)) {
                    return false
                }
                // don't fling vertically if this started in the allday area
                velocityY = 0f
            }
            doFling(e1, e2, velocityX, velocityY)
            return true
        }

        @Override
        override fun onDown(ev: MotionEvent): Boolean {
            if (DEBUG) Log.e(TAG, "GestureDetector.onDown")
            doDown(ev)
            return true
        }
    }

    @Override
    override fun onLongClick(v: View?): Boolean {
        return true
    }

    private inner class ScrollInterpolator : Interpolator {
        override fun getInterpolation(t: Float): Float {
            var t = t
            t -= 1.0f
            t = t * t * t * t * t + 1
            if ((1 - t) * mAnimationDistance < 1) {
                cancelAnimation()
            }
            return t
        }
    }

    private fun calculateDuration(delta: Float, width: Float, velocity: Float): Long {
        /*
         * Here we compute a "distance" that will be used in the computation of
         * the overall snap duration. This is a function of the actual distance
         * that needs to be traveled; we keep this value close to half screen
         * size in order to reduce the variance in snap duration as a function
         * of the distance the page needs to travel.
         */
        var velocity = velocity
        val halfScreenSize = width / 2
        val distanceRatio = delta / width
        val distanceInfluenceForSnapDuration = distanceInfluenceForSnapDuration(distanceRatio)
        val distance = halfScreenSize + halfScreenSize * distanceInfluenceForSnapDuration
        velocity = Math.abs(velocity)
        velocity = Math.max(MINIMUM_SNAP_VELOCITY.toFloat(), velocity)

        /*
         * we want the page's snap velocity to approximately match the velocity
         * at which the user flings, so we scale the duration by a value near to
         * the derivative of the scroll interpolator at zero, ie. 5. We use 6 to
         * make it a little slower.
         */
        val duration: Long = 6L * Math.round(1000 * Math.abs(distance / velocity))
        if (DEBUG) {
            Log.e(
                TAG, "halfScreenSize:" + halfScreenSize + " delta:" + delta + " distanceRatio:" +
                    distanceRatio + " distance:" + distance + " velocity:" + velocity +
                    " duration:" + duration + " distanceInfluenceForSnapDuration:" +
                    distanceInfluenceForSnapDuration
            )
        }
        return duration
    }

    /*
     * We want the duration of the page snap animation to be influenced by the
     * distance that the screen has to travel, however, we don't want this
     * duration to be effected in a purely linear fashion. Instead, we use this
     * method to moderate the effect that the distance of travel has on the
     * overall snap duration.
     */
    private fun distanceInfluenceForSnapDuration(f: Float): Float {
        var f = f
        f -= 0.5f // center the values about 0.
        f *= (0.3f * Math.PI / 2.0f).toFloat()
        return Math.sin(f.toDouble()).toFloat()
    }

    companion object {
        private const val TAG = "DayView"
        private const val DEBUG = false
        private const val DEBUG_SCALING = false
        private const val PERIOD_SPACE = ". "
        private var mScale = 0f // Used for supporting different screen densities
        private const val INVALID_EVENT_ID: Long = -1 // This is used for remembering a null event

        // Duration of the allday expansion
        private const val ANIMATION_DURATION: Long = 400

        // duration of the more allday event text fade
        private const val ANIMATION_SECONDARY_DURATION: Long = 200

        // duration of the scroll to go to a specified time
        private const val GOTO_SCROLL_DURATION = 200

        // duration for events' cross-fade animation
        private const val EVENTS_CROSS_FADE_DURATION = 400

        // duration to show the event clicked
        private const val CLICK_DISPLAY_DURATION = 50
        private const val MENU_DAY = 3
        private const val MENU_EVENT_VIEW = 5
        private const val MENU_EVENT_CREATE = 6
        private const val MENU_EVENT_EDIT = 7
        private const val MENU_EVENT_DELETE = 8
        private var DEFAULT_CELL_HEIGHT = 64
        private var MAX_CELL_HEIGHT = 150
        private var MIN_Y_SPAN = 100
        private val CALENDARS_PROJECTION = arrayOf<String>(
            Calendars._ID, // 0
            Calendars.CALENDAR_ACCESS_LEVEL, // 1
            Calendars.OWNER_ACCOUNT
        )
        private const val CALENDARS_INDEX_ACCESS_LEVEL = 1
        private const val CALENDARS_INDEX_OWNER_ACCOUNT = 2
        private val CALENDARS_WHERE: String = Calendars._ID.toString() + "=%d"
        private const val FROM_NONE = 0
        private const val FROM_ABOVE = 1
        private const val FROM_BELOW = 2
        private const val FROM_LEFT = 4
        private const val FROM_RIGHT = 8
        private const val ACCESS_LEVEL_NONE = 0
        private const val ACCESS_LEVEL_DELETE = 1
        private const val ACCESS_LEVEL_EDIT = 2
        private var mHorizontalSnapBackThreshold = 128

        // Update the current time line every five minutes if the window is left open that long
        private const val UPDATE_CURRENT_TIME_DELAY = 300000
        private var mOnDownDelay = 0
        protected var mStringBuilder: StringBuilder = StringBuilder(50)

        // TODO recreate formatter when locale changes
        protected var mFormatter: Formatter = Formatter(mStringBuilder, Locale.getDefault())

        // The number of milliseconds to show the popup window
        private const val POPUP_DISMISS_DELAY = 3000
        private var GRID_LINE_LEFT_MARGIN = 0f
        private const val GRID_LINE_INNER_WIDTH = 1f
        private const val DAY_GAP = 1
        private const val HOUR_GAP = 1

        // This is the standard height of an allday event with no restrictions
        private var SINGLE_ALLDAY_HEIGHT = 34

        /**
         * This is the minimum desired height of a allday event.
         * When unexpanded, allday events will use this height.
         * When expanded allDay events will attempt to grow to fit all
         * events at this height.
         */
        private var MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT = 28.0f // in pixels

        /**
         * This is how big the unexpanded allday height is allowed to be.
         * It will get adjusted based on screen size
         */
        private var MAX_UNEXPANDED_ALLDAY_HEIGHT = (MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT * 4).toInt()

        /**
         * This is the minimum size reserved for displaying regular events.
         * The expanded allDay region can't expand into this.
         */
        private const val MIN_HOURS_HEIGHT = 180
        private var ALLDAY_TOP_MARGIN = 1

        // The largest a single allDay event will become.
        private var MAX_HEIGHT_OF_ONE_ALLDAY_EVENT = 34
        private var HOURS_TOP_MARGIN = 2
        private var HOURS_LEFT_MARGIN = 2
        private var HOURS_RIGHT_MARGIN = 4
        private var HOURS_MARGIN = HOURS_LEFT_MARGIN + HOURS_RIGHT_MARGIN
        private var NEW_EVENT_MARGIN = 4
        private var NEW_EVENT_WIDTH = 2
        private var NEW_EVENT_MAX_LENGTH = 16
        private var CURRENT_TIME_LINE_SIDE_BUFFER = 4
        private var CURRENT_TIME_LINE_TOP_OFFSET = 2

        /* package */
        const val MINUTES_PER_HOUR = 60

        /* package */
        const val MINUTES_PER_DAY = MINUTES_PER_HOUR * 24

        /* package */
        const val MILLIS_PER_MINUTE = 60 * 1000

        /* package */
        const val MILLIS_PER_HOUR = 3600 * 1000

        /* package */
        const val MILLIS_PER_DAY = MILLIS_PER_HOUR * 24

        // More events text will transition between invisible and this alpha
        private const val MORE_EVENTS_MAX_ALPHA = 0x4C
        private var DAY_HEADER_ONE_DAY_LEFT_MARGIN = 0
        private var DAY_HEADER_ONE_DAY_RIGHT_MARGIN = 5
        private var DAY_HEADER_ONE_DAY_BOTTOM_MARGIN = 6
        private var DAY_HEADER_RIGHT_MARGIN = 4
        private var DAY_HEADER_BOTTOM_MARGIN = 3
        private var DAY_HEADER_FONT_SIZE = 14f
        private var DATE_HEADER_FONT_SIZE = 32f
        private var NORMAL_FONT_SIZE = 12f
        private var EVENT_TEXT_FONT_SIZE = 12f
        private var HOURS_TEXT_SIZE = 12f
        private var AMPM_TEXT_SIZE = 9f
        private var MIN_HOURS_WIDTH = 96
        private var MIN_CELL_WIDTH_FOR_TEXT = 20
        private const val MAX_EVENT_TEXT_LEN = 500

        // smallest height to draw an event with
        private var MIN_EVENT_HEIGHT = 24.0f // in pixels
        private var CALENDAR_COLOR_SQUARE_SIZE = 10
        private var EVENT_RECT_TOP_MARGIN = 1
        private var EVENT_RECT_BOTTOM_MARGIN = 0
        private var EVENT_RECT_LEFT_MARGIN = 1
        private var EVENT_RECT_RIGHT_MARGIN = 0
        private var EVENT_RECT_STROKE_WIDTH = 2
        private var EVENT_TEXT_TOP_MARGIN = 2
        private var EVENT_TEXT_BOTTOM_MARGIN = 2
        private var EVENT_TEXT_LEFT_MARGIN = 6
        private var EVENT_TEXT_RIGHT_MARGIN = 6
        private var ALL_DAY_EVENT_RECT_BOTTOM_MARGIN = 1
        private var EVENT_ALL_DAY_TEXT_TOP_MARGIN = EVENT_TEXT_TOP_MARGIN
        private var EVENT_ALL_DAY_TEXT_BOTTOM_MARGIN = EVENT_TEXT_BOTTOM_MARGIN
        private var EVENT_ALL_DAY_TEXT_LEFT_MARGIN = EVENT_TEXT_LEFT_MARGIN
        private var EVENT_ALL_DAY_TEXT_RIGHT_MARGIN = EVENT_TEXT_RIGHT_MARGIN

        // margins and sizing for the expand allday icon
        private var EXPAND_ALL_DAY_BOTTOM_MARGIN = 10

        // sizing for "box +n" in allDay events
        private var EVENT_SQUARE_WIDTH = 10
        private var EVENT_LINE_PADDING = 4
        private var NEW_EVENT_HINT_FONT_SIZE = 12
        private var mEventTextColor = 0
        private var mMoreEventsTextColor = 0
        private var mWeek_saturdayColor = 0
        private var mWeek_sundayColor = 0
        private var mCalendarDateBannerTextColor = 0
        private var mCalendarAmPmLabel = 0
        private var mCalendarGridAreaSelected = 0
        private var mCalendarGridLineInnerHorizontalColor = 0
        private var mCalendarGridLineInnerVerticalColor = 0
        private var mFutureBgColor = 0
        private var mFutureBgColorRes = 0
        private var mBgColor = 0
        private var mNewEventHintColor = 0
        private var mCalendarHourLabelColor = 0
        private var mMoreAlldayEventsTextAlpha = MORE_EVENTS_MAX_ALPHA
        private var mCellHeight = 0 // shared among all DayViews
        private var mMinCellHeight = 32
        private var mScaledPagingTouchSlop = 0

        /**
         * Whether to use the expand or collapse icon.
         */
        private var mUseExpandIcon = true

        /**
         * The height of the day names/numbers
         */
        private var DAY_HEADER_HEIGHT = 45

        /**
         * The height of the day names/numbers for multi-day views
         */
        private var MULTI_DAY_HEADER_HEIGHT = DAY_HEADER_HEIGHT

        /**
         * The height of the day names/numbers when viewing a single day
         */
        private var ONE_DAY_HEADER_HEIGHT = DAY_HEADER_HEIGHT

        /**
         * Whether or not to expand the allDay area to fill the screen
         */
        private var mShowAllAllDayEvents = false
        private var sCounter = 0

        /**
         * The initial state of the touch mode when we enter this view.
         */
        private const val TOUCH_MODE_INITIAL_STATE = 0

        /**
         * Indicates we just received the touch event and we are waiting to see if
         * it is a tap or a scroll gesture.
         */
        private const val TOUCH_MODE_DOWN = 1

        /**
         * Indicates the touch gesture is a vertical scroll
         */
        private const val TOUCH_MODE_VSCROLL = 0x20

        /**
         * Indicates the touch gesture is a horizontal scroll
         */
        private const val TOUCH_MODE_HSCROLL = 0x40

        /**
         * The selection modes are HIDDEN, PRESSED, SELECTED, and LONGPRESS.
         */
        private const val SELECTION_HIDDEN = 0
        private const val SELECTION_PRESSED = 1 // D-pad down but not up yet
        private const val SELECTION_SELECTED = 2
        private const val SELECTION_LONGPRESS = 3

        // The rest of this file was borrowed from Launcher2 - PagedView.java
        private const val MINIMUM_SNAP_VELOCITY = 2200
    }

    init {
        mContext = context
        initAccessibilityVariables()
        mResources = context!!.getResources()
        mNewEventHintString = mResources.getString(R.string.day_view_new_event_hint)
        mNumDays = numDays
        DATE_HEADER_FONT_SIZE =
            mResources.getDimension(R.dimen.date_header_text_size).toInt().toFloat()
        DAY_HEADER_FONT_SIZE =
            mResources.getDimension(R.dimen.day_label_text_size).toInt().toFloat()
        ONE_DAY_HEADER_HEIGHT = mResources.getDimension(R.dimen.one_day_header_height).toInt()
        DAY_HEADER_BOTTOM_MARGIN = mResources.getDimension(R.dimen.day_header_bottom_margin).toInt()
        EXPAND_ALL_DAY_BOTTOM_MARGIN =
            mResources.getDimension(R.dimen.all_day_bottom_margin).toInt()
        HOURS_TEXT_SIZE = mResources.getDimension(R.dimen.hours_text_size).toInt().toFloat()
        AMPM_TEXT_SIZE = mResources.getDimension(R.dimen.ampm_text_size).toInt().toFloat()
        MIN_HOURS_WIDTH = mResources.getDimension(R.dimen.min_hours_width).toInt()
        HOURS_LEFT_MARGIN = mResources.getDimension(R.dimen.hours_left_margin).toInt()
        HOURS_RIGHT_MARGIN = mResources.getDimension(R.dimen.hours_right_margin).toInt()
        MULTI_DAY_HEADER_HEIGHT = mResources.getDimension(R.dimen.day_header_height).toInt()
        val eventTextSizeId: Int
        eventTextSizeId = if (mNumDays == 1) {
            R.dimen.day_view_event_text_size
        } else {
            R.dimen.week_view_event_text_size
        }
        EVENT_TEXT_FONT_SIZE = mResources.getDimension(eventTextSizeId).toFloat()
        NEW_EVENT_HINT_FONT_SIZE = mResources.getDimension(R.dimen.new_event_hint_text_size).toInt()
        MIN_EVENT_HEIGHT = mResources.getDimension(R.dimen.event_min_height)
        MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT = MIN_EVENT_HEIGHT
        EVENT_TEXT_TOP_MARGIN = mResources.getDimension(R.dimen.event_text_vertical_margin).toInt()
        EVENT_TEXT_BOTTOM_MARGIN = EVENT_TEXT_TOP_MARGIN
        EVENT_ALL_DAY_TEXT_TOP_MARGIN = EVENT_TEXT_TOP_MARGIN
        EVENT_ALL_DAY_TEXT_BOTTOM_MARGIN = EVENT_TEXT_TOP_MARGIN
        EVENT_TEXT_LEFT_MARGIN = mResources
            .getDimension(R.dimen.event_text_horizontal_margin).toInt()
        EVENT_TEXT_RIGHT_MARGIN = EVENT_TEXT_LEFT_MARGIN
        EVENT_ALL_DAY_TEXT_LEFT_MARGIN = EVENT_TEXT_LEFT_MARGIN
        EVENT_ALL_DAY_TEXT_RIGHT_MARGIN = EVENT_TEXT_LEFT_MARGIN
        if (mScale == 0f) {
            mScale = mResources.getDisplayMetrics().density
            if (mScale != 1f) {
                SINGLE_ALLDAY_HEIGHT *= mScale.toInt()
                ALLDAY_TOP_MARGIN *= mScale.toInt()
                MAX_HEIGHT_OF_ONE_ALLDAY_EVENT *= mScale.toInt()
                NORMAL_FONT_SIZE *= mScale
                GRID_LINE_LEFT_MARGIN *= mScale
                HOURS_TOP_MARGIN *= mScale.toInt()
                MIN_CELL_WIDTH_FOR_TEXT *= mScale.toInt()
                MAX_UNEXPANDED_ALLDAY_HEIGHT *= mScale.toInt()
                mAnimateDayEventHeight = MIN_UNEXPANDED_ALLDAY_EVENT_HEIGHT.toInt()
                CURRENT_TIME_LINE_SIDE_BUFFER *= mScale.toInt()
                CURRENT_TIME_LINE_TOP_OFFSET *= mScale.toInt()
                MIN_Y_SPAN *= mScale.toInt()
                MAX_CELL_HEIGHT *= mScale.toInt()
                DEFAULT_CELL_HEIGHT *= mScale.toInt()
                DAY_HEADER_HEIGHT *= mScale.toInt()
                DAY_HEADER_RIGHT_MARGIN *= mScale.toInt()
                DAY_HEADER_ONE_DAY_LEFT_MARGIN *= mScale.toInt()
                DAY_HEADER_ONE_DAY_RIGHT_MARGIN *= mScale.toInt()
                DAY_HEADER_ONE_DAY_BOTTOM_MARGIN *= mScale.toInt()
                CALENDAR_COLOR_SQUARE_SIZE *= mScale.toInt()
                EVENT_RECT_TOP_MARGIN *= mScale.toInt()
                EVENT_RECT_BOTTOM_MARGIN *= mScale.toInt()
                ALL_DAY_EVENT_RECT_BOTTOM_MARGIN *= mScale.toInt()
                EVENT_RECT_LEFT_MARGIN *= mScale.toInt()
                EVENT_RECT_RIGHT_MARGIN *= mScale.toInt()
                EVENT_RECT_STROKE_WIDTH *= mScale.toInt()
                EVENT_SQUARE_WIDTH *= mScale.toInt()
                EVENT_LINE_PADDING *= mScale.toInt()
                NEW_EVENT_MARGIN *= mScale.toInt()
                NEW_EVENT_WIDTH *= mScale.toInt()
                NEW_EVENT_MAX_LENGTH *= mScale.toInt()
            }
        }
        HOURS_MARGIN = HOURS_LEFT_MARGIN + HOURS_RIGHT_MARGIN
        DAY_HEADER_HEIGHT = if (mNumDays == 1) ONE_DAY_HEADER_HEIGHT else MULTI_DAY_HEADER_HEIGHT
        mCurrentTimeLine = mResources.getDrawable(R.drawable.timeline_indicator_holo_light)
        mCurrentTimeAnimateLine = mResources
            .getDrawable(R.drawable.timeline_indicator_activated_holo_light)
        mTodayHeaderDrawable = mResources.getDrawable(R.drawable.today_blue_week_holo_light)
        mExpandAlldayDrawable = mResources.getDrawable(R.drawable.ic_expand_holo_light)
        mCollapseAlldayDrawable = mResources.getDrawable(R.drawable.ic_collapse_holo_light)
        mNewEventHintColor = mResources.getColor(R.color.new_event_hint_text_color)
        mAcceptedOrTentativeEventBoxDrawable = mResources
            .getDrawable(R.drawable.panel_month_event_holo_light)
        mEventLoader = eventLoader as EventLoader
        mEventGeometry = EventGeometry()
        mEventGeometry.setMinEventHeight(MIN_EVENT_HEIGHT)
        mEventGeometry.setHourGap(HOUR_GAP.toFloat())
        mEventGeometry.setCellMargin(DAY_GAP)
        mLastPopupEventID = INVALID_EVENT_ID
        mController = controller as CalendarController
        mViewSwitcher = viewSwitcher as ViewSwitcher
        mGestureDetector = GestureDetector(context, CalendarGestureListener())
        mScaleGestureDetector = ScaleGestureDetector(getContext(), this)
        if (mCellHeight == 0) {
            mCellHeight = Utils.getSharedPreference(
                mContext,
                GeneralPreferences.KEY_DEFAULT_CELL_HEIGHT, DEFAULT_CELL_HEIGHT
            )
        }
        mScroller = OverScroller(context)
        mHScrollInterpolator = ScrollInterpolator()
        mEdgeEffectTop = EdgeEffect(context)
        mEdgeEffectBottom = EdgeEffect(context)
        val vc: ViewConfiguration = ViewConfiguration.get(context)
        mScaledPagingTouchSlop = vc.getScaledPagingTouchSlop()
        mOnDownDelay = ViewConfiguration.getTapTimeout()
        OVERFLING_DISTANCE = vc.getScaledOverflingDistance()
        init(context as Context)
    }
}
