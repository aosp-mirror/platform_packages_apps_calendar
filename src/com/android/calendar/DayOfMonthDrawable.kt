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

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * A custom view to draw the day of the month in the today button in the options menu
 */
class DayOfMonthDrawable(c: Context) : Drawable() {
    private var mDayOfMonth = "1"
    private val mPaint: Paint
    private val mTextBounds: Rect = Rect()
    override fun draw(canvas: Canvas) {
        mPaint.getTextBounds(mDayOfMonth, 0, mDayOfMonth.length, mTextBounds)
        val textHeight: Int = mTextBounds.bottom - mTextBounds.top
        val bounds: Rect = getBounds()
        canvas.drawText(
            mDayOfMonth, (bounds.right).toFloat() / 2f, ((bounds.bottom).toFloat() +
                textHeight + 1) / 2f, mPaint
        )
    }

    override fun setAlpha(alpha: Int) {
        mPaint.setAlpha(alpha)
    }

    override fun setColorFilter(cf: ColorFilter?) {
        // Ignore
    }

    override fun getOpacity(): Int {
        return PixelFormat.UNKNOWN
    }

    fun setDayOfMonth(day: Int) {
        mDayOfMonth = Integer.toString(day)
        invalidateSelf()
    }

    companion object {
        private var mTextSize = 14f
    }

    init {
        mTextSize = c.getResources().getDimension(R.dimen.today_icon_text_size)
        mPaint = Paint()
        mPaint.setAlpha(255)
        mPaint.setColor(-0x888889)
        mPaint.setTypeface(Typeface.DEFAULT_BOLD)
        mPaint.setTextSize(mTextSize)
        mPaint.setTextAlign(Paint.Align.CENTER)
    }
}