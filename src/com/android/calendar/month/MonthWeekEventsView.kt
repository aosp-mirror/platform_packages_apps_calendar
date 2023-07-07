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

import com.android.calendar.Event
import com.android.calendar.R
import com.android.calendar.Utils
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Service
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Paint.Style
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.provider.CalendarContract.Attendees
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Time
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import java.util.ArrayList
import java.util.Arrays
import java.util.Formatter
import java.util.HashMap
import java.util.Iterator
import java.util.List
import java.util.Locale

class MonthWeekEventsView
/**
 * Shows up as an error if we don't include this.
 */
(context: Context) : SimpleWeekView(context) {
    // Renamed to avoid override modifier and type mismatch error
    protected val mTodayTime: Time = Time()
    override protected var mHasToday = false
    protected var mTodayIndex = -1
    protected var mOrientation: Int = Configuration.ORIENTATION_LANDSCAPE
    protected var mEvents: List<ArrayList<Event?>>? = null
    protected var mUnsortedEvents: ArrayList<Event?>? = null
    var mDna: HashMap<Int, Utils.DNAStrand>? = null

    // This is for drawing the outlines around event chips and supports up to 10
    // events being drawn on each day. The code will expand this if necessary.
    protected var mEventOutlines: FloatRef = FloatRef(10 * 4 * 4 * 7)
    protected var mMonthNamePaint: Paint? = null
    protected var mEventPaint: TextPaint = TextPaint()
    protected var mSolidBackgroundEventPaint: TextPaint? = null
    protected var mFramedEventPaint: TextPaint? = null
    protected var mDeclinedEventPaint: TextPaint? = null
    protected var mEventExtrasPaint: TextPaint = TextPaint()
    protected var mEventDeclinedExtrasPaint: TextPaint = TextPaint()
    protected var mWeekNumPaint: Paint = Paint()
    protected var mDNAAllDayPaint: Paint = Paint()
    protected var mDNATimePaint: Paint = Paint()
    protected var mEventSquarePaint: Paint = Paint()
    protected var mTodayDrawable: Drawable? = null
    protected var mMonthNumHeight = 0
    protected var mMonthNumAscentHeight = 0
    protected var mEventHeight = 0
    protected var mEventAscentHeight = 0
    protected var mExtrasHeight = 0
    protected var mExtrasAscentHeight = 0
    protected var mExtrasDescent = 0
    protected var mWeekNumAscentHeight = 0
    protected var mMonthBGColor = 0
    protected var mMonthBGOtherColor = 0
    protected var mMonthBGTodayColor = 0
    protected var mMonthNumColor = 0
    protected var mMonthNumOtherColor = 0
    protected var mMonthNumTodayColor = 0
    protected var mMonthNameColor = 0
    protected var mMonthNameOtherColor = 0
    protected var mMonthEventColor = 0
    protected var mMonthDeclinedEventColor = 0
    protected var mMonthDeclinedExtrasColor = 0
    protected var mMonthEventExtraColor = 0
    protected var mMonthEventOtherColor = 0
    protected var mMonthEventExtraOtherColor = 0
    protected var mMonthWeekNumColor = 0
    protected var mMonthBusyBitsBgColor = 0
    protected var mMonthBusyBitsBusyTimeColor = 0
    protected var mMonthBusyBitsConflictTimeColor = 0
    private var mClickedDayIndex = -1
    private var mClickedDayColor = 0
    protected var mEventChipOutlineColor = -0x1
    protected var mDaySeparatorInnerColor = 0
    protected var mTodayAnimateColor = 0
    private var mAnimateToday = false
    private var mAnimateTodayAlpha = 0
    private var mTodayAnimator: ObjectAnimator? = null
    private val mAnimatorListener: TodayAnimatorListener = TodayAnimatorListener()

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
                    mTodayAnimator = ObjectAnimator.ofInt(this@MonthWeekEventsView,
                            "animateTodayAlpha", 255, 0)
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

    private var mDayXs: IntArray? = null

    /**
     * This provides a reference to a float array which allows for easy size
     * checking and reallocation. Used for drawing lines.
     */
    inner class FloatRef(size: Int) {
        var array: FloatArray
        fun ensureSize(newSize: Int) {
            if (newSize >= array.size) {
                // Add enough space for 7 more boxes to be drawn
                array = Arrays.copyOf(array, newSize + 16 * 7)
            }
        }

        init {
            array = FloatArray(size)
        }
    }

    // Sets the list of events for this week. Takes a sorted list of arrays
    // divided up by day for generating the large month version and the full
    // arraylist sorted by start time to generate the dna version.
    fun setEvents(sortedEvents: List<ArrayList<Event?>>?, unsortedEvents: ArrayList<Event?>?) {
        setEvents(sortedEvents)
        // The MIN_WEEK_WIDTH is a hack to prevent the view from trying to
        // generate dna bits before its width has been fixed.
        createDna(unsortedEvents)
    }

    /**
     * Sets up the dna bits for the view. This will return early if the view
     * isn't in a state that will create a valid set of dna yet (such as the
     * views width not being set correctly yet).
     */
    fun createDna(unsortedEvents: ArrayList<Event?>?) {
        if (unsortedEvents == null || mWidth <= MIN_WEEK_WIDTH || getContext() == null) {
            // Stash the list of events for use when this view is ready, or
            // just clear it if a null set has been passed to this view
            mUnsortedEvents = unsortedEvents
            mDna = null
            return
        } else {
            // clear the cached set of events since we're ready to build it now
            mUnsortedEvents = null
        }
        // Create the drawing coordinates for dna
        if (!mShowDetailsInMonth) {
            val numDays: Int = mEvents!!.size
            var effectiveWidth: Int = mWidth - mPadding * 2
            if (mShowWeekNum) {
                effectiveWidth -= SPACING_WEEK_NUMBER
            }
            DNA_ALL_DAY_WIDTH = effectiveWidth / numDays - 2 * DNA_SIDE_PADDING
            mDNAAllDayPaint.setStrokeWidth(DNA_ALL_DAY_WIDTH.toFloat())
            mDayXs = IntArray(numDays)
            for (day in 0 until numDays) {
                mDayXs!![day] = computeDayLeftPosition(day) + DNA_WIDTH / 2 + DNA_SIDE_PADDING
            }
            val top = DAY_SEPARATOR_INNER_WIDTH + DNA_MARGIN + DNA_ALL_DAY_HEIGHT + 1
            val bottom: Int = mHeight - DNA_MARGIN
            mDna = Utils.createDNAStrands(mFirstJulianDay, unsortedEvents, top, bottom,
                    DNA_MIN_SEGMENT_HEIGHT, mDayXs, getContext())
        }
    }

    fun setEvents(sortedEvents: List<ArrayList<Event?>>?) {
        mEvents = sortedEvents
        if (sortedEvents == null) {
            return
        }
        if (sortedEvents.size !== mNumDays) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.wtf(TAG, ("Events size must be same as days displayed: size="
                        + sortedEvents.size) + " days=" + mNumDays)
            }
            mEvents = null
            return
        }
    }

    protected fun loadColors(context: Context) {
        val res: Resources = context.getResources()
        mMonthWeekNumColor = res.getColor(R.color.month_week_num_color)
        mMonthNumColor = res.getColor(R.color.month_day_number)
        mMonthNumOtherColor = res.getColor(R.color.month_day_number_other)
        mMonthNumTodayColor = res.getColor(R.color.month_today_number)
        mMonthNameColor = mMonthNumColor
        mMonthNameOtherColor = mMonthNumOtherColor
        mMonthEventColor = res.getColor(R.color.month_event_color)
        mMonthDeclinedEventColor = res.getColor(R.color.agenda_item_declined_color)
        mMonthDeclinedExtrasColor = res.getColor(R.color.agenda_item_where_declined_text_color)
        mMonthEventExtraColor = res.getColor(R.color.month_event_extra_color)
        mMonthEventOtherColor = res.getColor(R.color.month_event_other_color)
        mMonthEventExtraOtherColor = res.getColor(R.color.month_event_extra_other_color)
        mMonthBGTodayColor = res.getColor(R.color.month_today_bgcolor)
        mMonthBGOtherColor = res.getColor(R.color.month_other_bgcolor)
        mMonthBGColor = res.getColor(R.color.month_bgcolor)
        mDaySeparatorInnerColor = res.getColor(R.color.month_grid_lines)
        mTodayAnimateColor = res.getColor(R.color.today_highlight_color)
        mClickedDayColor = res.getColor(R.color.day_clicked_background_color)
        mTodayDrawable = res.getDrawable(R.drawable.today_blue_week_holo_light)
    }

    /**
     * Sets up the text and style properties for painting. Override this if you
     * want to use a different paint.
     */
    @Override
    protected override fun initView() {
        super.initView()
        if (!mInitialized) {
            val resources: Resources = getContext().getResources()
            mShowDetailsInMonth = Utils.getConfigBool(getContext(), R.bool.show_details_in_month)
            TEXT_SIZE_EVENT_TITLE = resources.getInteger(R.integer.text_size_event_title)
            TEXT_SIZE_MONTH_NUMBER = resources.getInteger(R.integer.text_size_month_number)
            SIDE_PADDING_MONTH_NUMBER = resources.getInteger(R.integer.month_day_number_margin)
            CONFLICT_COLOR = resources.getColor(R.color.month_dna_conflict_time_color)
            EVENT_TEXT_COLOR = resources.getColor(R.color.calendar_event_text_color)
            if (mScale != 1f) {
                TOP_PADDING_MONTH_NUMBER *= mScale.toInt()
                TOP_PADDING_WEEK_NUMBER *= mScale.toInt()
                SIDE_PADDING_MONTH_NUMBER *= mScale.toInt()
                SIDE_PADDING_WEEK_NUMBER *= mScale.toInt()
                SPACING_WEEK_NUMBER *= mScale.toInt()
                TEXT_SIZE_MONTH_NUMBER *= mScale.toInt()
                TEXT_SIZE_EVENT *= mScale.toInt()
                TEXT_SIZE_EVENT_TITLE *= mScale.toInt()
                TEXT_SIZE_MORE_EVENTS *= mScale.toInt()
                TEXT_SIZE_MONTH_NAME *= mScale.toInt()
                TEXT_SIZE_WEEK_NUM *= mScale.toInt()
                DAY_SEPARATOR_OUTER_WIDTH *= mScale.toInt()
                DAY_SEPARATOR_INNER_WIDTH *= mScale.toInt()
                DAY_SEPARATOR_VERTICAL_LENGTH *= mScale.toInt()
                DAY_SEPARATOR_VERTICAL_LENGTH_PORTRAIT *= mScale.toInt()
                EVENT_X_OFFSET_LANDSCAPE *= mScale.toInt()
                EVENT_Y_OFFSET_LANDSCAPE *= mScale.toInt()
                EVENT_Y_OFFSET_PORTRAIT *= mScale.toInt()
                EVENT_SQUARE_WIDTH *= mScale.toInt()
                EVENT_SQUARE_BORDER *= mScale.toInt()
                EVENT_LINE_PADDING *= mScale.toInt()
                EVENT_BOTTOM_PADDING *= mScale.toInt()
                EVENT_RIGHT_PADDING *= mScale.toInt()
                DNA_MARGIN *= mScale.toInt()
                DNA_WIDTH *= mScale.toInt()
                DNA_ALL_DAY_HEIGHT *= mScale.toInt()
                DNA_MIN_SEGMENT_HEIGHT *= mScale.toInt()
                DNA_SIDE_PADDING *= mScale.toInt()
                DEFAULT_EDGE_SPACING *= mScale.toInt()
                DNA_ALL_DAY_WIDTH *= mScale.toInt()
                TODAY_HIGHLIGHT_WIDTH *= mScale.toInt()
            }
            if (!mShowDetailsInMonth) {
                TOP_PADDING_MONTH_NUMBER += DNA_ALL_DAY_HEIGHT + DNA_MARGIN
            }
            mInitialized = true
        }
        mPadding = DEFAULT_EDGE_SPACING
        loadColors(getContext())
        // TODO modify paint properties depending on isMini
        mMonthNumPaint = Paint()
        mMonthNumPaint.setFakeBoldText(false)
        mMonthNumPaint.setAntiAlias(true)
        mMonthNumPaint.setTextSize(TEXT_SIZE_MONTH_NUMBER.toFloat())
        mMonthNumPaint.setColor(mMonthNumColor)
        mMonthNumPaint.setStyle(Style.FILL)
        mMonthNumPaint.setTextAlign(Align.RIGHT)
        mMonthNumPaint.setTypeface(Typeface.DEFAULT)
        mMonthNumAscentHeight = (-mMonthNumPaint.ascent() + 0.5f).toInt()
        mMonthNumHeight = (mMonthNumPaint.descent() - mMonthNumPaint.ascent() + 0.5f).toInt()
        mEventPaint = TextPaint()
        mEventPaint.setFakeBoldText(true)
        mEventPaint.setAntiAlias(true)
        mEventPaint.setTextSize(TEXT_SIZE_EVENT_TITLE.toFloat())
        mEventPaint.setColor(mMonthEventColor)
        mSolidBackgroundEventPaint = TextPaint(mEventPaint)
        mSolidBackgroundEventPaint?.setColor(EVENT_TEXT_COLOR)
        mFramedEventPaint = TextPaint(mSolidBackgroundEventPaint)
        mDeclinedEventPaint = TextPaint()
        mDeclinedEventPaint?.setFakeBoldText(true)
        mDeclinedEventPaint?.setAntiAlias(true)
        mDeclinedEventPaint?.setTextSize(TEXT_SIZE_EVENT_TITLE.toFloat())
        mDeclinedEventPaint?.setColor(mMonthDeclinedEventColor)
        mEventAscentHeight = (-mEventPaint.ascent() + 0.5f).toInt()
        mEventHeight = (mEventPaint.descent() - mEventPaint.ascent() + 0.5f).toInt()
        mEventExtrasPaint = TextPaint()
        mEventExtrasPaint.setFakeBoldText(false)
        mEventExtrasPaint.setAntiAlias(true)
        mEventExtrasPaint.setStrokeWidth(EVENT_SQUARE_BORDER.toFloat())
        mEventExtrasPaint.setTextSize(TEXT_SIZE_EVENT.toFloat())
        mEventExtrasPaint.setColor(mMonthEventExtraColor)
        mEventExtrasPaint.setStyle(Style.FILL)
        mEventExtrasPaint.setTextAlign(Align.LEFT)
        mExtrasHeight = (mEventExtrasPaint.descent() - mEventExtrasPaint.ascent() + 0.5f).toInt()
        mExtrasAscentHeight = (-mEventExtrasPaint.ascent() + 0.5f).toInt()
        mExtrasDescent = (mEventExtrasPaint.descent() + 0.5f).toInt()
        mEventDeclinedExtrasPaint = TextPaint()
        mEventDeclinedExtrasPaint.setFakeBoldText(false)
        mEventDeclinedExtrasPaint.setAntiAlias(true)
        mEventDeclinedExtrasPaint.setStrokeWidth(EVENT_SQUARE_BORDER.toFloat())
        mEventDeclinedExtrasPaint.setTextSize(TEXT_SIZE_EVENT.toFloat())
        mEventDeclinedExtrasPaint.setColor(mMonthDeclinedExtrasColor)
        mEventDeclinedExtrasPaint.setStyle(Style.FILL)
        mEventDeclinedExtrasPaint.setTextAlign(Align.LEFT)
        mWeekNumPaint = Paint()
        mWeekNumPaint.setFakeBoldText(false)
        mWeekNumPaint.setAntiAlias(true)
        mWeekNumPaint.setTextSize(TEXT_SIZE_WEEK_NUM.toFloat())
        mWeekNumPaint.setColor(mWeekNumColor)
        mWeekNumPaint.setStyle(Style.FILL)
        mWeekNumPaint.setTextAlign(Align.RIGHT)
        mWeekNumAscentHeight = (-mWeekNumPaint.ascent() + 0.5f).toInt()
        mDNAAllDayPaint = Paint()
        mDNATimePaint = Paint()
        mDNATimePaint.setColor(mMonthBusyBitsBusyTimeColor)
        mDNATimePaint.setStyle(Style.FILL_AND_STROKE)
        mDNATimePaint.setStrokeWidth(DNA_WIDTH.toFloat())
        mDNATimePaint.setAntiAlias(false)
        mDNAAllDayPaint.setColor(mMonthBusyBitsConflictTimeColor)
        mDNAAllDayPaint.setStyle(Style.FILL_AND_STROKE)
        mDNAAllDayPaint.setStrokeWidth(DNA_ALL_DAY_WIDTH.toFloat())
        mDNAAllDayPaint.setAntiAlias(false)
        mEventSquarePaint = Paint()
        mEventSquarePaint.setStrokeWidth(EVENT_SQUARE_BORDER.toFloat())
        mEventSquarePaint.setAntiAlias(false)
        if (DEBUG_LAYOUT) {
            Log.d("EXTRA", "mScale=$mScale")
            Log.d("EXTRA", "mMonthNumPaint ascent=" + mMonthNumPaint.ascent()
                    .toString() + " descent=" + mMonthNumPaint.descent().toString() +
                    " int height=" + mMonthNumHeight)
            Log.d("EXTRA", "mEventPaint ascent=" + mEventPaint.ascent()
                    .toString() + " descent=" + mEventPaint.descent().toString() +
                    " int height=" + mEventHeight
                    .toString() + " int ascent=" + mEventAscentHeight)
            Log.d("EXTRA", "mEventExtrasPaint ascent=" + mEventExtrasPaint.ascent()
                    .toString() + " descent=" + mEventExtrasPaint.descent().toString() +
                    " int height=" + mExtrasHeight)
            Log.d("EXTRA", "mWeekNumPaint ascent=" + mWeekNumPaint.ascent()
                    .toString() + " descent=" + mWeekNumPaint.descent())
        }
    }

    @Override
    override fun setWeekParams(params: HashMap<String?, Int?>, tz: String) {
        super.setWeekParams(params, tz)
        if (params.containsKey(VIEW_PARAMS_ORIENTATION)) {
            mOrientation = params.get(VIEW_PARAMS_ORIENTATION) ?:
                    Configuration.ORIENTATION_LANDSCAPE
        }
        updateToday(tz)
        mNumCells = mNumDays + 1
        if (params.containsKey(VIEW_PARAMS_ANIMATE_TODAY) && mHasToday) {
            synchronized(mAnimatorListener) {
                if (mTodayAnimator != null) {
                    mTodayAnimator?.removeAllListeners()
                    mTodayAnimator?.cancel()
                }
                mTodayAnimator = ObjectAnimator.ofInt(this, "animateTodayAlpha",
                        Math.max(mAnimateTodayAlpha, 80), 255)
                mTodayAnimator?.setDuration(150)
                mAnimatorListener.setAnimator(mTodayAnimator)
                mAnimatorListener.setFadingIn(true)
                mTodayAnimator?.addListener(mAnimatorListener)
                mAnimateToday = true
                mTodayAnimator?.start()
            }
        }
    }

    /**
     * @param tz
     */
    fun updateToday(tz: String): Boolean {
        mTodayTime.timezone = tz
        mTodayTime.setToNow()
        mTodayTime.normalize(true)
        val julianToday: Int = Time.getJulianDay(mTodayTime.toMillis(false), mTodayTime.gmtoff)
        if (julianToday >= mFirstJulianDay && julianToday < mFirstJulianDay + mNumDays) {
            mHasToday = true
            mTodayIndex = julianToday - mFirstJulianDay
        } else {
            mHasToday = false
            mTodayIndex = -1
        }
        return mHasToday
    }

    fun setAnimateTodayAlpha(alpha: Int) {
        mAnimateTodayAlpha = alpha
        invalidate()
    }

    @Override
    protected override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
        drawWeekNums(canvas)
        drawDaySeparators(canvas)
        if (mHasToday && mAnimateToday) {
            drawToday(canvas)
        }
        if (mShowDetailsInMonth) {
            drawEvents(canvas)
        } else {
            if (mDna == null && mUnsortedEvents != null) {
                createDna(mUnsortedEvents)
            }
            drawDNA(canvas)
        }
        drawClick(canvas)
    }

    protected fun drawToday(canvas: Canvas) {
        r.top = DAY_SEPARATOR_INNER_WIDTH + TODAY_HIGHLIGHT_WIDTH / 2
        r.bottom = mHeight - Math.ceil(TODAY_HIGHLIGHT_WIDTH.toDouble() / 2.0f).toInt()
        p.setStyle(Style.STROKE)
        p.setStrokeWidth(TODAY_HIGHLIGHT_WIDTH.toFloat())
        r.left = computeDayLeftPosition(mTodayIndex) + TODAY_HIGHLIGHT_WIDTH / 2
        r.right = (computeDayLeftPosition(mTodayIndex + 1)
                - Math.ceil(TODAY_HIGHLIGHT_WIDTH.toDouble() / 2.0f).toInt())
        p.setColor(mTodayAnimateColor or (mAnimateTodayAlpha shl 24))
        canvas.drawRect(r, p)
        p.setStyle(Style.FILL)
    }

    // TODO move into SimpleWeekView
    // Computes the x position for the left side of the given day
    private fun computeDayLeftPosition(day: Int): Int {
        var effectiveWidth: Int = mWidth
        var x = 0
        var xOffset = 0
        if (mShowWeekNum) {
            xOffset = SPACING_WEEK_NUMBER + mPadding
            effectiveWidth -= xOffset
        }
        x = day * effectiveWidth / mNumDays + xOffset
        return x
    }

    @Override
    protected override fun drawDaySeparators(canvas: Canvas) {
        val lines = FloatArray(8 * 4)
        var count = 6 * 4
        var wkNumOffset = 0
        var i = 0
        if (mShowWeekNum) {
            // This adds the first line separating the week number
            val xOffset: Int = SPACING_WEEK_NUMBER + mPadding
            count += 4
            lines[i++] = xOffset.toFloat()
            lines[i++] = 0f
            lines[i++] = xOffset.toFloat()
            lines[i++] = mHeight.toFloat()
            wkNumOffset++
        }
        count += 4
        lines[i++] = 0f
        lines[i++] = 0f
        lines[i++] = mWidth.toFloat()
        lines[i++] = 0f
        val y0 = 0
        val y1: Int = mHeight
        while (i < count) {
            val x = computeDayLeftPosition(i / 4 - wkNumOffset)
            lines[i++] = x.toFloat()
            lines[i++] = y0.toFloat()
            lines[i++] = x.toFloat()
            lines[i++] = y1.toFloat()
        }
        p.setColor(mDaySeparatorInnerColor)
        p.setStrokeWidth(DAY_SEPARATOR_INNER_WIDTH.toFloat())
        canvas.drawLines(lines, 0, count, p)
    }

    @Override
    protected override fun drawBackground(canvas: Canvas) {
        var i = 0
        var offset = 0
        r.top = DAY_SEPARATOR_INNER_WIDTH
        r.bottom = mHeight
        if (mShowWeekNum) {
            i++
            offset++
        }
        if (!mOddMonth.get(i)) {
            while (++i < mOddMonth.size && !mOddMonth.get(i));
            r.right = computeDayLeftPosition(i - offset)
            r.left = 0
            p.setColor(mMonthBGOtherColor)
            canvas.drawRect(r, p)
            // compute left edge for i, set up r, draw
        } else if (!mOddMonth.get(mOddMonth.size - 1.also { i = it })) {
            while (--i >= offset && !mOddMonth.get(i));
            i++
            // compute left edge for i, set up r, draw
            r.right = mWidth
            r.left = computeDayLeftPosition(i - offset)
            p.setColor(mMonthBGOtherColor)
            canvas.drawRect(r, p)
        }
        if (mHasToday) {
            p.setColor(mMonthBGTodayColor)
            r.left = computeDayLeftPosition(mTodayIndex)
            r.right = computeDayLeftPosition(mTodayIndex + 1)
            canvas.drawRect(r, p)
        }
    }

    // Draw the "clicked" color on the tapped day
    private fun drawClick(canvas: Canvas) {
        if (mClickedDayIndex != -1) {
            val alpha: Int = p.getAlpha()
            p.setColor(mClickedDayColor)
            p.setAlpha(mClickedAlpha)
            r.left = computeDayLeftPosition(mClickedDayIndex)
            r.right = computeDayLeftPosition(mClickedDayIndex + 1)
            r.top = DAY_SEPARATOR_INNER_WIDTH
            r.bottom = mHeight
            canvas.drawRect(r, p)
            p.setAlpha(alpha)
        }
    }

    @Override
    protected override fun drawWeekNums(canvas: Canvas) {
        var y: Int
        var i = 0
        var offset = -1
        var todayIndex = mTodayIndex
        var x = 0
        var numCount: Int = mNumDays
        if (mShowWeekNum) {
            x = SIDE_PADDING_WEEK_NUMBER + mPadding
            y = mWeekNumAscentHeight + TOP_PADDING_WEEK_NUMBER
            canvas.drawText(mDayNumbers!!.get(0) as String, x.toFloat(), y.toFloat(), mWeekNumPaint)
            numCount++
            i++
            todayIndex++
            offset++
        }
        y = mMonthNumAscentHeight + TOP_PADDING_MONTH_NUMBER
        var isFocusMonth: Boolean = mFocusDay.get(i)
        var isBold = false
        mMonthNumPaint.setColor(if (isFocusMonth) mMonthNumColor else mMonthNumOtherColor)
        while (i < numCount) {
            if (mHasToday && todayIndex == i) {
                mMonthNumPaint.setColor(mMonthNumTodayColor)
                mMonthNumPaint.setFakeBoldText(true.also { isBold = it })
                if (i + 1 < numCount) {
                    // Make sure the color will be set back on the next
                    // iteration
                    isFocusMonth = !mFocusDay.get(i + 1)
                }
            } else if (mFocusDay.get(i) !== isFocusMonth) {
                isFocusMonth = mFocusDay.get(i)
                mMonthNumPaint.setColor(if (isFocusMonth) mMonthNumColor else mMonthNumOtherColor)
            }
            x = computeDayLeftPosition(i - offset) - SIDE_PADDING_MONTH_NUMBER
            canvas.drawText(mDayNumbers!!.get(i) as String, x.toFloat(), y.toFloat(),
                    mMonthNumPaint as Paint)
            if (isBold) {
                mMonthNumPaint.setFakeBoldText(false.also { isBold = it })
            }
            i++
        }
    }

    protected fun drawEvents(canvas: Canvas) {
        if (mEvents == null) {
            return
        }
        var day = -1
        for (eventDay in mEvents!!) {
            day++
            if (eventDay == null || eventDay.size === 0) {
                continue
            }
            var ySquare: Int
            val xSquare = computeDayLeftPosition(day) + SIDE_PADDING_MONTH_NUMBER + 1
            var rightEdge = computeDayLeftPosition(day + 1)
            if (mOrientation == Configuration.ORIENTATION_PORTRAIT) {
                ySquare = EVENT_Y_OFFSET_PORTRAIT + mMonthNumHeight + TOP_PADDING_MONTH_NUMBER
                rightEdge -= SIDE_PADDING_MONTH_NUMBER + 1
            } else {
                ySquare = EVENT_Y_OFFSET_LANDSCAPE
                rightEdge -= EVENT_X_OFFSET_LANDSCAPE
            }

            // Determine if everything will fit when time ranges are shown.
            var showTimes = true
            var iter: Iterator<Event> = eventDay.iterator() as Iterator<Event>
            var yTest = ySquare
            while (iter.hasNext()) {
                val event: Event = iter.next()
                val newY = drawEvent(canvas, event, xSquare, yTest, rightEdge, iter.hasNext(),
                        showTimes,  /*doDraw*/false)
                if (newY == yTest) {
                    showTimes = false
                    break
                }
                yTest = newY
            }
            var eventCount = 0
            iter = eventDay.iterator() as Iterator<Event>
            while (iter.hasNext()) {
                val event: Event = iter.next()
                val newY = drawEvent(canvas, event, xSquare, ySquare, rightEdge, iter.hasNext(),
                        showTimes,  /*doDraw*/true)
                if (newY == ySquare) {
                    break
                }
                eventCount++
                ySquare = newY
            }
            val remaining: Int = eventDay.size- eventCount
            if (remaining > 0) {
                drawMoreEvents(canvas, remaining, xSquare)
            }
        }
    }

    protected fun addChipOutline(lines: FloatRef, count: Int, x: Int, y: Int): Int {
        var count = count
        lines.ensureSize(count + 16)
        // top of box
        lines.array[count++] = x.toFloat()
        lines.array[count++] = y.toFloat()
        lines.array[count++] = (x + EVENT_SQUARE_WIDTH).toFloat()
        lines.array[count++] = y.toFloat()
        // right side of box
        lines.array[count++] = (x + EVENT_SQUARE_WIDTH).toFloat()
        lines.array[count++] = y.toFloat()
        lines.array[count++] = (x + EVENT_SQUARE_WIDTH).toFloat()
        lines.array[count++] = (y + EVENT_SQUARE_WIDTH).toFloat()
        // left side of box
        lines.array[count++] = x.toFloat()
        lines.array[count++] = y.toFloat()
        lines.array[count++] = x.toFloat()
        lines.array[count++] = (y + EVENT_SQUARE_WIDTH + 1).toFloat()
        // bottom of box
        lines.array[count++] = x.toFloat()
        lines.array[count++] = (y + EVENT_SQUARE_WIDTH).toFloat()
        lines.array[count++] = (x + EVENT_SQUARE_WIDTH + 1).toFloat()
        lines.array[count++] = (y + EVENT_SQUARE_WIDTH).toFloat()
        return count
    }

    /**
     * Attempts to draw the given event. Returns the y for the next event or the
     * original y if the event will not fit. An event is considered to not fit
     * if the event and its extras won't fit or if there are more events and the
     * more events line would not fit after drawing this event.
     *
     * @param canvas the canvas to draw on
     * @param event the event to draw
     * @param x the top left corner for this event's color chip
     * @param y the top left corner for this event's color chip
     * @param rightEdge the rightmost point we're allowed to draw on (exclusive)
     * @param moreEvents indicates whether additional events will follow this one
     * @param showTimes if set, a second line with a time range will be displayed for non-all-day
     * events
     * @param doDraw if set, do the actual drawing; otherwise this just computes the height
     * and returns
     * @return the y for the next event or the original y if it won't fit
     */
    protected fun drawEvent(canvas: Canvas, event: Event, x: Int, y: Int, rightEdge: Int,
                            moreEvents: Boolean, showTimes: Boolean, doDraw: Boolean): Int {
        /*
         * Vertical layout:
         *   (top of box)
         * a. EVENT_Y_OFFSET_LANDSCAPE or portrait equivalent
         * b. Event title: mEventHeight for a normal event, + 2xBORDER_SPACE for all-day event
         * c. [optional] Time range (mExtrasHeight)
         * d. EVENT_LINE_PADDING
         *
         * Repeat (b,c,d) as needed and space allows.  If we have more events than fit, we need
         * to leave room for something like "+2" at the bottom:
         *
         * e. "+ more" line (mExtrasHeight)
         *
         * f. EVENT_BOTTOM_PADDING (overlaps EVENT_LINE_PADDING)
         *   (bottom of box)
         */
        var y = y
        val BORDER_SPACE = EVENT_SQUARE_BORDER + 1 // want a 1-pixel gap inside border
        val STROKE_WIDTH_ADJ = EVENT_SQUARE_BORDER / 2 // adjust bounds for stroke width
        val allDay: Boolean = event.allDay
        var eventRequiredSpace = mEventHeight
        if (allDay) {
            // Add a few pixels for the box we draw around all-day events.
            eventRequiredSpace += BORDER_SPACE * 2
        } else if (showTimes) {
            // Need room for the "1pm - 2pm" line.
            eventRequiredSpace += mExtrasHeight
        }
        var reservedSpace = EVENT_BOTTOM_PADDING // leave a bit of room at the bottom
        if (moreEvents) {
            // More events follow.  Leave a bit of space between events.
            eventRequiredSpace += EVENT_LINE_PADDING

            // Make sure we have room for the "+ more" line.  (The "+ more" line is expected
            // to be <= the height of an event line, so we won't show "+1" when we could be
            // showing the event.)
            reservedSpace += mExtrasHeight
        }
        if (y + eventRequiredSpace + reservedSpace > mHeight) {
            // Not enough space, return original y
            return y
        } else if (!doDraw) {
            return y + eventRequiredSpace
        }
        val isDeclined = event.selfAttendeeStatus === Attendees.ATTENDEE_STATUS_DECLINED
        var color: Int = event.color
        if (isDeclined) {
            color = Utils.getDeclinedColorFromColor(color)
        }
        val textX: Int
        var textY: Int
        val textRightEdge: Int
        if (allDay) {
            // We shift the render offset "inward", because drawRect with a stroke width greater
            // than 1 draws outside the specified bounds.  (We don't adjust the left edge, since
            // we want to match the existing appearance of the "event square".)
            r.left = x
            r.right = rightEdge - STROKE_WIDTH_ADJ
            r.top = y + STROKE_WIDTH_ADJ
            r.bottom = y + mEventHeight + BORDER_SPACE * 2 - STROKE_WIDTH_ADJ
            textX = x + BORDER_SPACE
            textY = y + mEventAscentHeight + BORDER_SPACE
            textRightEdge = rightEdge - BORDER_SPACE
        } else {
            r.left = x
            r.right = x + EVENT_SQUARE_WIDTH
            r.bottom = y + mEventAscentHeight
            r.top = r.bottom - EVENT_SQUARE_WIDTH
            textX = x + EVENT_SQUARE_WIDTH + EVENT_RIGHT_PADDING
            textY = y + mEventAscentHeight
            textRightEdge = rightEdge
        }
        var boxStyle: Style = Style.STROKE
        var solidBackground = false
        if (event.selfAttendeeStatus !== Attendees.ATTENDEE_STATUS_INVITED) {
            boxStyle = Style.FILL_AND_STROKE
            if (allDay) {
                solidBackground = true
            }
        }
        mEventSquarePaint.setStyle(boxStyle)
        mEventSquarePaint.setColor(color)
        canvas.drawRect(r, mEventSquarePaint)
        val avail = (textRightEdge - textX).toFloat()
        var text: CharSequence = TextUtils.ellipsize(
                event.title, mEventPaint, avail, TextUtils.TruncateAt.END)
        val textPaint: TextPaint?
        textPaint = if (solidBackground) {
            // Text color needs to contrast with solid background.
            mSolidBackgroundEventPaint
        } else if (isDeclined) {
            // Use "declined event" color.
            mDeclinedEventPaint
        } else if (allDay) {
            // Text inside frame is same color as frame.
            mFramedEventPaint?.setColor(color)
            mFramedEventPaint
        } else {
            // Use generic event text color.
            mEventPaint
        }
        canvas.drawText(text.toString(), textX.toFloat(), textY.toFloat(), textPaint as Paint)
        y += mEventHeight
        if (allDay) {
            y += BORDER_SPACE * 2
        }
        if (showTimes && !allDay) {
            // show start/end time, e.g. "1pm - 2pm"
            textY = y + mExtrasAscentHeight
            mStringBuilder.setLength(0)
            text = DateUtils.formatDateRange(getContext(), mFormatter, event.startMillis,
                    event.endMillis, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                    Utils.getTimeZone(getContext(), null)).toString()
            text = TextUtils.ellipsize(text, mEventExtrasPaint, avail, TextUtils.TruncateAt.END)
            canvas.drawText(text.toString(), textX.toFloat(), textY.toFloat(),
                    if (isDeclined) mEventDeclinedExtrasPaint else mEventExtrasPaint)
            y += mExtrasHeight
        }
        y += EVENT_LINE_PADDING
        return y
    }

    protected fun drawMoreEvents(canvas: Canvas, remainingEvents: Int, x: Int) {
        val y: Int = mHeight - (mExtrasDescent + EVENT_BOTTOM_PADDING)
        val text: String = getContext().getResources().getQuantityString(
                R.plurals.month_more_events, remainingEvents)
        mEventExtrasPaint.setAntiAlias(true)
        mEventExtrasPaint.setFakeBoldText(true)
        canvas.drawText(String.format(text, remainingEvents), x.toFloat(), y.toFloat(),
                mEventExtrasPaint as Paint)
        mEventExtrasPaint.setFakeBoldText(false)
    }

    /**
     * Draws a line showing busy times in each day of week The method draws
     * non-conflicting times in the event color and times with conflicting
     * events in the dna conflict color defined in colors.
     *
     * @param canvas
     */
    protected fun drawDNA(canvas: Canvas) {
        // Draw event and conflict times
        if (mDna != null) {
            for (strand in mDna!!.values) {
                if (strand.color === CONFLICT_COLOR || strand.points == null ||
                        (strand.points as FloatArray).size === 0) {
                    continue
                }
                mDNATimePaint.setColor(strand.color)
                canvas.drawLines(strand.points as FloatArray, mDNATimePaint as Paint)
            }
            // Draw black last to make sure it's on top
            val strand: Utils.DNAStrand? = mDna?.get(CONFLICT_COLOR)
            if (strand != null && strand.points != null && strand.points?.size !== 0) {
                mDNATimePaint.setColor(strand.color)
                canvas.drawLines(strand.points as FloatArray, mDNATimePaint as Paint)
            }
            if (mDayXs == null) {
                return
            }
            val numDays = mDayXs!!.size
            val xOffset = (DNA_ALL_DAY_WIDTH - DNA_WIDTH) / 2
            if (strand != null && strand.allDays != null && strand.allDays?.size === numDays) {
                for (i in 0 until numDays) {
                    // this adds at most 7 draws. We could sort it by color and
                    // build an array instead but this is easier.
                    if (strand.allDays?.get(i) !== 0) {
                        mDNAAllDayPaint.setColor(strand.allDays!!.get(i))
                        canvas.drawLine(mDayXs!![i].toFloat() + xOffset.toFloat(),
                                DNA_MARGIN.toFloat(), mDayXs!![i].toFloat() + xOffset.toFloat(),
                                DNA_MARGIN.toFloat() + DNA_ALL_DAY_HEIGHT.toFloat(),
                                mDNAAllDayPaint as Paint)
                    }
                }
            }
        }
    }

    @Override
    protected override fun updateSelectionPositions() {
        if (mHasSelectedDay) {
            var selectedPosition: Int = mSelectedDay - mWeekStart
            if (selectedPosition < 0) {
                selectedPosition += 7
            }
            var effectiveWidth: Int = mWidth - mPadding * 2
            effectiveWidth -= SPACING_WEEK_NUMBER
            mSelectedLeft = selectedPosition * effectiveWidth / mNumDays + mPadding
            mSelectedRight = (selectedPosition + 1) * effectiveWidth / mNumDays + mPadding
            mSelectedLeft += SPACING_WEEK_NUMBER
            mSelectedRight += SPACING_WEEK_NUMBER
        }
    }

    fun getDayIndexFromLocation(x: Float): Int {
        val dayStart: Int = if (mShowWeekNum) SPACING_WEEK_NUMBER + mPadding else mPadding
        return if (x < dayStart || x > mWidth - mPadding) {
            -1
        } else (((x - dayStart) * mNumDays / (mWidth - dayStart - mPadding)).toInt())
        // Selection is (x - start) / (pixels/day) == (x -s) * day / pixels
    }

    @Override
    override fun getDayFromLocation(x: Float): Time? {
        val dayPosition = getDayIndexFromLocation(x)
        if (dayPosition == -1) {
            return null
        }
        var day: Int = mFirstJulianDay + dayPosition
        val time = Time(mTimeZone)
        if (mWeek === 0) {
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
            if (hover != null
                    && (mLastHoverTime == null || Time.compare(hover, mLastHoverTime) !== 0)) {
                val millis: Long = hover.toMillis(true)
                val date: String = Utils.formatDateRange(context, millis, millis,
                        DateUtils.FORMAT_SHOW_DATE) as String
                val accessEvent: AccessibilityEvent = AccessibilityEvent
                        .obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
                accessEvent.getText().add(date)
                if (mShowDetailsInMonth && mEvents != null) {
                    val dayStart: Int = SPACING_WEEK_NUMBER + mPadding
                    val dayPosition = ((event.getX() - dayStart) * mNumDays / (mWidth
                            - dayStart - mPadding)).toInt()
                    val events: ArrayList<Event?> = mEvents!![dayPosition]
                    val text: List<CharSequence> = accessEvent.getText() as List<CharSequence>
                    for (e in events) {
                        text.add(e!!.titleAndLocation.toString() + ". ")
                        var flags: Int = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR
                        if (!e.allDay) {
                            flags = flags or DateUtils.FORMAT_SHOW_TIME
                            if (DateFormat.is24HourFormat(context)) {
                                flags = flags or DateUtils.FORMAT_24HOUR
                            }
                        } else {
                            flags = flags or DateUtils.FORMAT_UTC
                        }
                        text.add(Utils.formatDateRange(context, e.startMillis, e.endMillis,
                                flags).toString() + ". ")
                    }
                }
                sendAccessibilityEventUnchecked(accessEvent)
                mLastHoverTime = hover
            }
        }
        return true
    }

    fun setClickedDay(xLocation: Float) {
        mClickedDayIndex = getDayIndexFromLocation(xLocation)
        invalidate()
    }

    fun clearClickedDay() {
        mClickedDayIndex = -1
        invalidate()
    }

    companion object {
        private const val TAG = "MonthView"
        private const val DEBUG_LAYOUT = false
        const val VIEW_PARAMS_ORIENTATION = "orientation"
        const val VIEW_PARAMS_ANIMATE_TODAY = "animate_today"

        /* NOTE: these are not constants, and may be multiplied by a scale factor */
        private var TEXT_SIZE_MONTH_NUMBER = 32
        private var TEXT_SIZE_EVENT = 12
        private var TEXT_SIZE_EVENT_TITLE = 14
        private var TEXT_SIZE_MORE_EVENTS = 12
        private var TEXT_SIZE_MONTH_NAME = 14
        private var TEXT_SIZE_WEEK_NUM = 12
        private var DNA_MARGIN = 4
        private var DNA_ALL_DAY_HEIGHT = 4
        private var DNA_MIN_SEGMENT_HEIGHT = 4
        private var DNA_WIDTH = 8
        private var DNA_ALL_DAY_WIDTH = 32
        private var DNA_SIDE_PADDING = 6
        private var CONFLICT_COLOR: Int = Color.BLACK
        private var EVENT_TEXT_COLOR: Int = Color.WHITE
        private var DEFAULT_EDGE_SPACING = 0
        private var SIDE_PADDING_MONTH_NUMBER = 4
        private var TOP_PADDING_MONTH_NUMBER = 4
        private var TOP_PADDING_WEEK_NUMBER = 4
        private var SIDE_PADDING_WEEK_NUMBER = 20
        private var DAY_SEPARATOR_OUTER_WIDTH = 0
        private var DAY_SEPARATOR_INNER_WIDTH = 1
        private var DAY_SEPARATOR_VERTICAL_LENGTH = 53
        private var DAY_SEPARATOR_VERTICAL_LENGTH_PORTRAIT = 64
        private const val MIN_WEEK_WIDTH = 50
        private var EVENT_X_OFFSET_LANDSCAPE = 38
        private var EVENT_Y_OFFSET_LANDSCAPE = 8
        private var EVENT_Y_OFFSET_PORTRAIT = 7
        private var EVENT_SQUARE_WIDTH = 10
        private var EVENT_SQUARE_BORDER = 2
        private var EVENT_LINE_PADDING = 2
        private var EVENT_RIGHT_PADDING = 4
        private var EVENT_BOTTOM_PADDING = 3
        private var TODAY_HIGHLIGHT_WIDTH = 2
        private var SPACING_WEEK_NUMBER = 24
        private var mInitialized = false
        private var mShowDetailsInMonth = false
        protected var mStringBuilder: StringBuilder = StringBuilder(50)

        // TODO recreate formatter when locale changes
        protected var mFormatter: Formatter = Formatter(mStringBuilder, Locale.getDefault())
        private const val mClickedAlpha = 128
    }
}