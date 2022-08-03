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
import android.app.Service
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Paint.Style
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.text.format.Time
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import java.security.InvalidParameterException
import java.util.HashMap

/**
 *
 *
 * This is a dynamic view for drawing a single week. It can be configured to
 * display the week number, start the week on a given day, or show a reduced
 * number of days. It is intended for use as a single view within a ListView.
 * See [SimpleWeeksAdapter] for usage.
 *
 */
open class SimpleWeekView(context: Context) : View(context) {
    // affects the padding on the sides of this view
    @JvmField protected var mPadding = 0
    @JvmField protected var r: Rect = Rect()
    @JvmField protected var p: Paint = Paint()
    @JvmField protected var mMonthNumPaint: Paint = Paint()
    @JvmField protected var mSelectedDayLine: Drawable

    // Cache the number strings so we don't have to recompute them each time
    @JvmField protected var mDayNumbers: Array<String?>? = null

    // How many days to display
    @JvmField protected var mNumDays = DEFAULT_NUM_DAYS

    // The number of days + a spot for week number if it is displayed
    @JvmField protected var mNumCells = mNumDays

    // Quick lookup for checking which days are in the focus month
    @JvmField protected var mFocusDay: BooleanArray = BooleanArray(mNumCells)

    // Quick lookup for checking which days are in an odd month (to set a different background)
    @JvmField protected var mOddMonth: BooleanArray = BooleanArray(mNumCells)

    // The Julian day of the first day displayed by this item
    @JvmField protected var mFirstJulianDay = -1

    // The month of the first day in this week
    @JvmField protected var firstMonth = -1

    // The month of the last day in this week
    @JvmField protected var lastMonth = -1

    // The position of this week, equivalent to weeks since the week of Jan 1st,
    // 1970
    @JvmField var mWeek = -1

    // Quick reference to the width of this view, matches parent
    @JvmField protected var mWidth = 0

    // The height this view should draw at in pixels, set by height param
    @JvmField protected var mHeight = DEFAULT_HEIGHT

    // Whether the week number should be shown
    @JvmField protected var mShowWeekNum = false

    // If this view contains the selected day
    @JvmField protected var mHasSelectedDay = false

    // If this view contains the today
    open protected var mHasToday = false

    // Which day is selected [0-6] or -1 if no day is selected
    @JvmField protected var mSelectedDay = DEFAULT_SELECTED_DAY

    // Which day is today [0-6] or -1 if no day is today
    @JvmField protected var mToday: Int = DEFAULT_SELECTED_DAY

    // Which day of the week to start on [0-6]
    @JvmField protected var mWeekStart = DEFAULT_WEEK_START

    // The left edge of the selected day
    @JvmField protected var mSelectedLeft = -1

    // The right edge of the selected day
    @JvmField protected var mSelectedRight = -1

    // The timezone to display times/dates in (used for determining when Today
    // is)
    @JvmField protected var mTimeZone: String = Time.getCurrentTimezone()
    @JvmField protected var mBGColor: Int
    @JvmField protected var mSelectedWeekBGColor: Int
    @JvmField protected var mFocusMonthColor: Int
    @JvmField protected var mOtherMonthColor: Int
    @JvmField protected var mDaySeparatorColor: Int
    @JvmField protected var mTodayOutlineColor: Int
    @JvmField protected var mWeekNumColor: Int

    /**
     * Sets all the parameters for displaying this week. The only required
     * parameter is the week number. Other parameters have a default value and
     * will only update if a new value is included, except for focus month,
     * which will always default to no focus month if no value is passed in. See
     * [.VIEW_PARAMS_HEIGHT] for more info on parameters.
     *
     * @param params A map of the new parameters, see
     * [.VIEW_PARAMS_HEIGHT]
     * @param tz The time zone this view should reference times in
     */
    open fun setWeekParams(params: HashMap<String?, Int?>, tz: String) {
        if (!params.containsKey(VIEW_PARAMS_WEEK)) {
            throw InvalidParameterException("You must specify the week number for this view")
        }
        setTag(params)
        mTimeZone = tz
        // We keep the current value for any params not present
        if (params.containsKey(VIEW_PARAMS_HEIGHT)) {
            mHeight = (params.get(VIEW_PARAMS_HEIGHT))!!.toInt()
            if (mHeight < MIN_HEIGHT) {
                mHeight = MIN_HEIGHT
            }
        }
        if (params.containsKey(VIEW_PARAMS_SELECTED_DAY)) {
            mSelectedDay = (params.get(VIEW_PARAMS_SELECTED_DAY))!!.toInt()
        }
        mHasSelectedDay = mSelectedDay != -1
        if (params.containsKey(VIEW_PARAMS_NUM_DAYS)) {
            mNumDays = (params.get(VIEW_PARAMS_NUM_DAYS))!!.toInt()
        }
        if (params.containsKey(VIEW_PARAMS_SHOW_WK_NUM)) {
            mShowWeekNum =
                    if (params.get(VIEW_PARAMS_SHOW_WK_NUM) != 0) {
                        true
                    } else {
                        false
                    }
        }
        mNumCells = if (mShowWeekNum) mNumDays + 1 else mNumDays

        // Allocate space for caching the day numbers and focus values
        mDayNumbers = arrayOfNulls(mNumCells)
        mFocusDay = BooleanArray(mNumCells)
        mOddMonth = BooleanArray(mNumCells)
        mWeek = (params.get(VIEW_PARAMS_WEEK))!!.toInt()
        val julianMonday: Int = Utils.getJulianMondayFromWeeksSinceEpoch(mWeek)
        val time = Time(tz)
        time.setJulianDay(julianMonday)

        // If we're showing the week number calculate it based on Monday
        var i = 0
        if (mShowWeekNum) {
            mDayNumbers!![0] = Integer.toString(time.getWeekNumber())
            i++
        }
        if (params.containsKey(VIEW_PARAMS_WEEK_START)) {
            mWeekStart = (params.get(VIEW_PARAMS_WEEK_START))!!.toInt()
        }

        // Now adjust our starting day based on the start day of the week
        // If the week is set to start on a Saturday the first week will be
        // Dec 27th 1969 -Jan 2nd, 1970
        if (time.weekDay !== mWeekStart) {
            var diff: Int = time.weekDay - mWeekStart
            if (diff < 0) {
                diff += 7
            }
            time.monthDay -= diff
            time.normalize(true)
        }
        mFirstJulianDay = Time.getJulianDay(time.toMillis(true), time.gmtoff)
        firstMonth = time.month

        // Figure out what day today is
        val today = Time(tz)
        today.setToNow()
        mHasToday = false
        mToday = -1
        val focusMonth = if (params.containsKey(VIEW_PARAMS_FOCUS_MONTH)) params.get(
                VIEW_PARAMS_FOCUS_MONTH
        ) else DEFAULT_FOCUS_MONTH
        while (i < mNumCells) {
            if (time.monthDay === 1) {
                firstMonth = time.month
            }
            mOddMonth[i] = time.month % 2 === 1
            if (time.month === focusMonth) {
                mFocusDay[i] = true
            } else {
                mFocusDay[i] = false
            }
            if (time.year === today.year && time.yearDay === today.yearDay) {
                mHasToday = true
                mToday = i
            }
            mDayNumbers!![i] = Integer.toString(time.monthDay++)
            time.normalize(true)
            i++
        }
        // We do one extra add at the end of the loop, if that pushed us to a
        // new month undo it
        if (time.monthDay === 1) {
            time.monthDay--
            time.normalize(true)
        }
        lastMonth = time.month
        updateSelectionPositions()
    }

    /**
     * Sets up the text and style properties for painting. Override this if you
     * want to use a different paint.
     */
    protected open fun initView() {
        p.setFakeBoldText(false)
        p.setAntiAlias(true)
        p.setTextSize(MINI_DAY_NUMBER_TEXT_SIZE.toFloat())
        p.setStyle(Style.FILL)
        mMonthNumPaint = Paint()
        mMonthNumPaint.setFakeBoldText(true)
        mMonthNumPaint.setAntiAlias(true)
        mMonthNumPaint.setTextSize(MINI_DAY_NUMBER_TEXT_SIZE.toFloat())
        mMonthNumPaint.setColor(mFocusMonthColor)
        mMonthNumPaint.setStyle(Style.FILL)
        mMonthNumPaint.setTextAlign(Align.CENTER)
    }

    /**
     * Returns the month of the first day in this week
     *
     * @return The month the first day of this view is in
     */
    fun getFirstMonth(): Int {
        return firstMonth
    }

    /**
     * Returns the month of the last day in this week
     *
     * @return The month the last day of this view is in
     */
    fun getLastMonth(): Int {
        return lastMonth
    }

    /**
     * Returns the julian day of the first day in this view.
     *
     * @return The julian day of the first day in the view.
     */
    fun getFirstJulianDay(): Int {
        return mFirstJulianDay
    }

    /**
     * Calculates the day that the given x position is in, accounting for week
     * number. Returns a Time referencing that day or null if
     *
     * @param x The x position of the touch event
     * @return A time object for the tapped day or null if the position wasn't
     * in a day
     */
    open fun getDayFromLocation(x: Float): Time? {
        val dayStart =
                if (mShowWeekNum) (mWidth - mPadding * 2) / mNumCells + mPadding else mPadding
        if (x < dayStart || x > mWidth - mPadding) {
            return null
        }
        // Selection is (x - start) / (pixels/day) == (x -s) * day / pixels
        val dayPosition = ((x - dayStart) * mNumDays / (mWidth - dayStart - mPadding)).toInt()
        var day = mFirstJulianDay + dayPosition
        val time = Time(mTimeZone)
        if (mWeek == 0) {
            // This week is weird...
            if (day < Time.EPOCH_JULIAN_DAY) {
                day++
            } else if (day == Time.EPOCH_JULIAN_DAY) {
                time.set(1, 0, 1970)
                time.normalize(true)
                return time
            }
        }
        time.setJulianDay(day)
        return time
    }

    @Override
    protected override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
        drawWeekNums(canvas)
        drawDaySeparators(canvas)
    }

    /**
     * This draws the selection highlight if a day is selected in this week.
     * Override this method if you wish to have a different background drawn.
     *
     * @param canvas The canvas to draw on
     */
    protected open fun drawBackground(canvas: Canvas) {
        if (mHasSelectedDay) {
            p.setColor(mSelectedWeekBGColor)
            p.setStyle(Style.FILL)
        } else {
            return
        }
        r.top = 1
        r.bottom = mHeight - 1
        r.left = mPadding
        r.right = mSelectedLeft
        canvas.drawRect(r, p)
        r.left = mSelectedRight
        r.right = mWidth - mPadding
        canvas.drawRect(r, p)
    }

    /**
     * Draws the week and month day numbers for this week. Override this method
     * if you need different placement.
     *
     * @param canvas The canvas to draw on
     */
    protected open fun drawWeekNums(canvas: Canvas) {
        val y = (mHeight + MINI_DAY_NUMBER_TEXT_SIZE) / 2 - DAY_SEPARATOR_WIDTH
        val nDays = mNumCells
        var i = 0
        val divisor = 2 * nDays
        if (mShowWeekNum) {
            p.setTextSize(MINI_WK_NUMBER_TEXT_SIZE.toFloat())
            p.setStyle(Style.FILL)
            p.setTextAlign(Align.CENTER)
            p.setAntiAlias(true)
            p.setColor(mWeekNumColor)
            val x = (mWidth - mPadding * 2) / divisor + mPadding
            canvas.drawText(mDayNumbers!![0] as String, x.toFloat(), y.toFloat(), p)
            i++
        }
        var isFocusMonth = mFocusDay[i]
        mMonthNumPaint.setColor(if (isFocusMonth) mFocusMonthColor else mOtherMonthColor)
        mMonthNumPaint.setFakeBoldText(false)
        while (i < nDays) {
            if (mFocusDay[i] != isFocusMonth) {
                isFocusMonth = mFocusDay[i]
                mMonthNumPaint.setColor(if (isFocusMonth) mFocusMonthColor else mOtherMonthColor)
            }
            if (mHasToday && mToday == i) {
                mMonthNumPaint.setTextSize(MINI_TODAY_NUMBER_TEXT_SIZE.toFloat())
                mMonthNumPaint.setFakeBoldText(true)
            }
            val x = (2 * i + 1) * (mWidth - mPadding * 2) / divisor + mPadding
            canvas.drawText(mDayNumbers!![i] as String, x.toFloat(), y.toFloat(),
                    mMonthNumPaint as Paint)
            if (mHasToday && mToday == i) {
                mMonthNumPaint.setTextSize(MINI_DAY_NUMBER_TEXT_SIZE.toFloat())
                mMonthNumPaint.setFakeBoldText(false)
            }
            i++
        }
    }

    /**
     * Draws a horizontal line for separating the weeks. Override this method if
     * you want custom separators.
     *
     * @param canvas The canvas to draw on
     */
    protected open fun drawDaySeparators(canvas: Canvas) {
        if (mHasSelectedDay) {
            r.top = 1
            r.bottom = mHeight - 1
            r.left = mSelectedLeft + 1
            r.right = mSelectedRight - 1
            p.setStrokeWidth(MINI_TODAY_OUTLINE_WIDTH.toFloat())
            p.setStyle(Style.STROKE)
            p.setColor(mTodayOutlineColor)
            canvas.drawRect(r, p)
        }
        if (mShowWeekNum) {
            p.setColor(mDaySeparatorColor)
            p.setStrokeWidth(DAY_SEPARATOR_WIDTH.toFloat())
            val x = (mWidth - mPadding * 2) / mNumCells + mPadding
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), mHeight.toFloat(), p)
        }
    }

    @Override
    protected override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        mWidth = w
        updateSelectionPositions()
    }

    /**
     * This calculates the positions for the selected day lines.
     */
    protected open fun updateSelectionPositions() {
        if (mHasSelectedDay) {
            var selectedPosition = mSelectedDay - mWeekStart
            if (selectedPosition < 0) {
                selectedPosition += 7
            }
            if (mShowWeekNum) {
                selectedPosition++
            }
            mSelectedLeft = (selectedPosition * (mWidth - mPadding * 2) / mNumCells +
                    mPadding)
            mSelectedRight = ((selectedPosition + 1) * (mWidth - mPadding * 2) / mNumCells +
                    mPadding)
        }
    }

    @Override
    protected override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), mHeight)
    }

    @Override
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val context: Context = getContext()
        // only send accessibility events if accessibility and exploration are
        // on.
        val am: AccessibilityManager = context
                .getSystemService(Service.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled() || !am.isTouchExplorationEnabled()) {
            return super.onHoverEvent(event)
        }
        if (event.getAction() !== MotionEvent.ACTION_HOVER_EXIT) {
            val hover: Time? = getDayFromLocation(event.getX())
            if (hover != null &&
                    (mLastHoverTime == null || Time.compare(hover, mLastHoverTime) !== 0)
            ) {
                val millis: Long = hover.toMillis(true)
                val date: String? = Utils.formatDateRange(
                        context, millis, millis,
                        DateUtils.FORMAT_SHOW_DATE
                )
                val accessEvent: AccessibilityEvent =
                        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
                accessEvent.getText().add(date)
                sendAccessibilityEventUnchecked(accessEvent)
                mLastHoverTime = hover
            }
        }
        return true
    }

    @JvmField var mLastHoverTime: Time? = null

    companion object {
        private const val TAG = "MonthView"
        /**
         * These params can be passed into the view to control how it appears.
         * [.VIEW_PARAMS_WEEK] is the only required field, though the default
         * values are unlikely to fit most layouts correctly.
         */
        /**
         * This sets the height of this week in pixels
         */
        const val VIEW_PARAMS_HEIGHT = "height"

        /**
         * This specifies the position (or weeks since the epoch) of this week,
         * calculated using [Utils.getWeeksSinceEpochFromJulianDay]
         */
        const val VIEW_PARAMS_WEEK = "week"

        /**
         * This sets one of the days in this view as selected [Time.SUNDAY]
         * through [Time.SATURDAY].
         */
        const val VIEW_PARAMS_SELECTED_DAY = "selected_day"

        /**
         * Which day the week should start on. [Time.SUNDAY] through
         * [Time.SATURDAY].
         */
        const val VIEW_PARAMS_WEEK_START = "week_start"

        /**
         * How many days to display at a time. Days will be displayed starting with
         * [.mWeekStart].
         */
        const val VIEW_PARAMS_NUM_DAYS = "num_days"

        /**
         * Which month is currently in focus, as defined by [Time.month]
         * [0-11].
         */
        const val VIEW_PARAMS_FOCUS_MONTH = "focus_month"

        /**
         * If this month should display week numbers. false if 0, true otherwise.
         */
        const val VIEW_PARAMS_SHOW_WK_NUM = "show_wk_num"
        protected var DEFAULT_HEIGHT = 32
        protected var MIN_HEIGHT = 10
        protected const val DEFAULT_SELECTED_DAY = -1
        protected val DEFAULT_WEEK_START: Int = Time.SUNDAY
        protected const val DEFAULT_NUM_DAYS = 7
        protected const val DEFAULT_SHOW_WK_NUM = 0
        protected const val DEFAULT_FOCUS_MONTH = -1
        protected var DAY_SEPARATOR_WIDTH = 1
        protected var MINI_DAY_NUMBER_TEXT_SIZE = 14
        protected var MINI_WK_NUMBER_TEXT_SIZE = 12
        protected var MINI_TODAY_NUMBER_TEXT_SIZE = 18
        protected var MINI_TODAY_OUTLINE_WIDTH = 2
        protected var WEEK_NUM_MARGIN_BOTTOM = 4

        // used for scaling to the device density
        @JvmStatic protected var mScale = 0f
    }

    init {
        val res: Resources = context.getResources()
        mBGColor = res.getColor(R.color.month_bgcolor)
        mSelectedWeekBGColor = res.getColor(R.color.month_selected_week_bgcolor)
        mFocusMonthColor = res.getColor(R.color.month_mini_day_number)
        mOtherMonthColor = res.getColor(R.color.month_other_month_day_number)
        mDaySeparatorColor = res.getColor(R.color.month_grid_lines)
        mTodayOutlineColor = res.getColor(R.color.mini_month_today_outline_color)
        mWeekNumColor = res.getColor(R.color.month_week_num_color)
        mSelectedDayLine = res.getDrawable(R.drawable.dayline_minical_holo_light)
        if (mScale == 0f) {
            mScale = context.getResources().getDisplayMetrics().density
            if (mScale != 1f) {
                DEFAULT_HEIGHT *= mScale.toInt()
                MIN_HEIGHT *= mScale.toInt()
                MINI_DAY_NUMBER_TEXT_SIZE *= mScale.toInt()
                MINI_TODAY_NUMBER_TEXT_SIZE *= mScale.toInt()
                MINI_TODAY_OUTLINE_WIDTH *= mScale.toInt()
                WEEK_NUM_MARGIN_BOTTOM *= mScale.toInt()
                DAY_SEPARATOR_WIDTH *= mScale.toInt()
                MINI_WK_NUMBER_TEXT_SIZE *= mScale.toInt()
            }
        }

        // Sets up any standard paints that will be used
        initView()
    }
}