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
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.widget.Button

/**
 * A button with more than two states. When the button is pressed
 * or clicked, the state transitions automatically.
 *
 * **XML attributes**
 * See [ MultiStateButton Attributes][R.styleable.MultiStateButton],
 * [Button][android.R.styleable.Button], [TextView Attributes][android.R.styleable.TextView],
 * [ ][android.R.styleable.View]
 *
 */
class MultiStateButton(context: Context?, attrs: AttributeSet?, defStyle: Int) :
                       Button(context, attrs, defStyle) {
    //The current state for this button, ranging from 0 to maxState-1
    var mState = 0
        private set

    //The maximum number of states allowed for this button.
    private var mMaxStates = 1

    //The currently displaying resource ID. This gets set to a default on creation and remains
    //on the last set if the resources get set to null.
    private var mButtonResource = 0

    //A list of all drawable resources used by this button in the order it uses them.
    private var mButtonResources: IntArray
    private var mButtonDrawable: Drawable? = null

    constructor(context: Context?) : this(context, null) {}
    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0) {}

    override fun performClick(): Boolean {
        /* When clicked, toggle the state */
        transitionState()
        return super.performClick()
    }

    fun transitionState() {
        mState = (mState + 1) % mMaxStates
        setButtonDrawable(mButtonResources[mState])
    }

    /**
     * Allows for a new set of drawable resource ids to be set.
     *
     * This sets the maximum states allowed to the length of the resources array. It will also
     * set the current state to the maximum allowed if it's greater than the new max.
     */
    //@Throws(IllegalArgumentException::class)
    fun setButtonResources(resources: IntArray?) {
        if (resources == null) {
            throw IllegalArgumentException("Button resources cannot be null")
        }
        mMaxStates = resources.size
        if (mState >= mMaxStates) {
            mState = mMaxStates - 1
        }
        mButtonResources = resources
    }

    /**
     * Attempts to set the state. Returns true if successful, false otherwise.
     */
    fun setState(state: Int): Boolean {
        if (state >= mMaxStates || state < 0) {
            //When moved out of Calendar the tag should be changed.
            Log.w("Cal", "MultiStateButton state set to value greater than maxState or < 0")
            return false
        }
        mState = state
        setButtonDrawable(mButtonResources[mState])
        return true
    }

    /**
     * Set the background to a given Drawable, identified by its resource id.
     *
     * @param resid the resource id of the drawable to use as the background
     */
    fun setButtonDrawable(resid: Int) {
        if (resid != 0 && resid == mButtonResource) {
            return
        }
        mButtonResource = resid
        var d: Drawable? = null
        if (mButtonResource != 0) {
            d = getResources().getDrawable(mButtonResource)
        }
        setButtonDrawable(d)
    }

    /**
     * Set the background to a given Drawable
     *
     * @param d The Drawable to use as the background
     */
    fun setButtonDrawable(d: Drawable?) {
        if (d != null) {
            if (mButtonDrawable != null) {
                mButtonDrawable?.setCallback(null)
                unscheduleDrawable(mButtonDrawable)
            }
            d.setCallback(this)
            d.setState(getDrawableState())
            d.setVisible(getVisibility() === VISIBLE, false)
            mButtonDrawable = d
            mButtonDrawable?.setState(getDrawableState())
            setMinHeight(mButtonDrawable?.getIntrinsicHeight() ?: 0)
            setWidth(mButtonDrawable?.getIntrinsicWidth() ?: 0)
        }
        refreshDrawableState()
    }

    protected override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mButtonDrawable != null) {
            val verticalGravity: Int = getGravity() and Gravity.VERTICAL_GRAVITY_MASK
            val horizontalGravity: Int = getGravity() and Gravity.HORIZONTAL_GRAVITY_MASK
            val height: Int = mButtonDrawable?.getIntrinsicHeight() ?: 0
            val width: Int = mButtonDrawable?.getIntrinsicWidth() ?: 0
            var y = 0
            var x = 0
            when (verticalGravity) {
                Gravity.BOTTOM -> y = getHeight() - height
                Gravity.CENTER_VERTICAL -> y = (getHeight() - height) / 2
            }
            when (horizontalGravity) {
                Gravity.RIGHT -> x = getWidth() - width
                Gravity.CENTER_HORIZONTAL -> x = (getWidth() - width) / 2
            }
            mButtonDrawable?.setBounds(x, y, x + width, y + height)
            mButtonDrawable?.draw(canvas)
        }
    }

    init {
        //Currently using the standard buttonStyle, will update when new resources are added.
        //TODO add a more generic default button
        mButtonResources = intArrayOf(R.drawable.widget_show)
        setButtonDrawable(mButtonResources[mState])
    }
}