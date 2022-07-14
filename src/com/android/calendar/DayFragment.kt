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

import com.android.calendar.CalendarController.EventInfo
import com.android.calendar.CalendarController.EventType
import android.app.Fragment
import android.content.Context
import android.os.Bundle
import android.text.format.Time
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout.LayoutParams
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ProgressBar
import android.widget.ViewSwitcher
import android.widget.ViewSwitcher.ViewFactory

/**
 * This is the base class for Day and Week Activities.
 */
class DayFragment : Fragment, CalendarController.EventHandler, ViewFactory {
    protected var mProgressBar: ProgressBar? = null
    protected var mViewSwitcher: ViewSwitcher? = null
    protected var mInAnimationForward: Animation? = null
    protected var mOutAnimationForward: Animation? = null
    protected var mInAnimationBackward: Animation? = null
    protected var mOutAnimationBackward: Animation? = null
    var mEventLoader: EventLoader? = null
    var mSelectedDay: Time = Time()
    private val mTZUpdater: Runnable = object : Runnable {
        override fun run() {
            if (!this@DayFragment.isAdded()) {
                return
            }
            val tz: String? = Utils.getTimeZone(getActivity(), this)
            mSelectedDay.timezone = tz
            mSelectedDay.normalize(true)
        }
    }
    private var mNumDays = 0

    constructor() {
        mSelectedDay.setToNow()
    }

    constructor(timeMillis: Long, numOfDays: Int) {
        mNumDays = numOfDays
        if (timeMillis == 0L) {
            mSelectedDay.setToNow()
        } else {
            mSelectedDay.set(timeMillis)
        }
    }

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        val context: Context = getActivity()
        mInAnimationForward = AnimationUtils.loadAnimation(context, R.anim.slide_left_in)
        mOutAnimationForward = AnimationUtils.loadAnimation(context, R.anim.slide_left_out)
        mInAnimationBackward = AnimationUtils.loadAnimation(context, R.anim.slide_right_in)
        mOutAnimationBackward = AnimationUtils.loadAnimation(context, R.anim.slide_right_out)
        mEventLoader = EventLoader(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater?,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v: View? = inflater?.inflate(R.layout.day_activity, null)
        mViewSwitcher = v?.findViewById(R.id.switcher) as? ViewSwitcher
        mViewSwitcher?.setFactory(this)
        mViewSwitcher?.getCurrentView()?.requestFocus()
        (mViewSwitcher?.getCurrentView() as? DayView)?.updateTitle()
        return v
    }

    override fun makeView(): View {
        mTZUpdater.run()
        val view = DayView(getActivity(), CalendarController
                .getInstance(getActivity()), mViewSwitcher, mEventLoader, mNumDays)
        view.setId(DayFragment.Companion.VIEW_ID)
        view.setLayoutParams(LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        view.setSelected(mSelectedDay, false, false)
        return view
    }

    override fun onResume() {
        super.onResume()
        mEventLoader!!.startBackgroundThread()
        mTZUpdater.run()
        eventsChanged()
        var view: DayView? = mViewSwitcher?.getCurrentView() as? DayView
        view?.handleOnResume()
        view?.restartCurrentTimeUpdates()
        view = mViewSwitcher?.getNextView() as? DayView
        view?.handleOnResume()
        view?.restartCurrentTimeUpdates()
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        var view: DayView? = mViewSwitcher?.getCurrentView() as? DayView
        view?.cleanup()
        view = mViewSwitcher?.getNextView() as? DayView
        view?.cleanup()
        mEventLoader!!.stopBackgroundThread()

        // Stop events cross-fade animation
        view?.stopEventsAnimation()
        (mViewSwitcher?.getNextView() as? DayView)?.stopEventsAnimation()
    }

    fun startProgressSpinner() {
        // start the progress spinner
        mProgressBar?.setVisibility(View.VISIBLE)
    }

    fun stopProgressSpinner() {
        // stop the progress spinner
        mProgressBar?.setVisibility(View.GONE)
    }

    private fun goTo(goToTime: Time?, ignoreTime: Boolean, animateToday: Boolean) {
        if (mViewSwitcher == null) {
            // The view hasn't been set yet. Just save the time and use it later.
            mSelectedDay.set(goToTime)
            return
        }
        val currentView: DayView? = mViewSwitcher?.getCurrentView() as? DayView

        // How does goTo time compared to what's already displaying?
        val diff: Int = currentView?.compareToVisibleTimeRange(goToTime as Time) as Int
        if (diff == 0) {
            // In visible range. No need to switch view
            currentView.setSelected(goToTime, ignoreTime, animateToday)
        } else {
            // Figure out which way to animate
            if (diff > 0) {
                mViewSwitcher?.setInAnimation(mInAnimationForward)
                mViewSwitcher?.setOutAnimation(mOutAnimationForward)
            } else {
                mViewSwitcher?.setInAnimation(mInAnimationBackward)
                mViewSwitcher?.setOutAnimation(mOutAnimationBackward)
            }
            val next: DayView? = mViewSwitcher?.getNextView() as? DayView
            if (ignoreTime) {
                next!!.firstVisibleHour = currentView.firstVisibleHour
            }
            next?.setSelected(goToTime, ignoreTime, animateToday)
            next?.reloadEvents()
            mViewSwitcher?.showNext()
            next?.requestFocus()
            next?.updateTitle()
            next?.restartCurrentTimeUpdates()
        }
    }

    /**
     * Returns the selected time in milliseconds. The milliseconds are measured
     * in UTC milliseconds from the epoch and uniquely specifies any selectable
     * time.
     *
     * @return the selected time in milliseconds
     */
    val selectedTimeInMillis: Long
        get() {
            if (mViewSwitcher == null) {
                return -1
            }
            val view: DayView = mViewSwitcher?.getCurrentView() as DayView ?: return -1
            return view.selectedTimeInMillis
        }

    override fun eventsChanged() {
        if (mViewSwitcher == null) {
            return
        }
        var view: DayView? = mViewSwitcher?.getCurrentView() as? DayView
        view?.clearCachedEvents()
        view?.reloadEvents()
        view = mViewSwitcher?.getNextView() as? DayView
        view?.clearCachedEvents()
    }

    val nextView: DayView?
        get() = mViewSwitcher?.getNextView() as? DayView
    override val supportedEventTypes: Long
        get() = CalendarController.EventType.GO_TO or CalendarController.EventType.EVENTS_CHANGED

    override fun handleEvent(msg: CalendarController.EventInfo?) {
        if (msg?.eventType == CalendarController.EventType.GO_TO) {
// TODO support a range of time
// TODO support event_id
// TODO support select message
            goTo(msg.selectedTime, msg.extraLong and CalendarController.EXTRA_GOTO_DATE != 0L,
                    msg.extraLong and CalendarController.EXTRA_GOTO_TODAY != 0L)
        } else if (msg?.eventType == CalendarController.EventType.EVENTS_CHANGED) {
            eventsChanged()
        }
    }

    companion object {
        /**
         * The view id used for all the views we create. It's OK to have all child
         * views have the same ID. This ID is used to pick which view receives
         * focus when a view hierarchy is saved / restore
         */
        private const val VIEW_ID = 1
        protected const val BUNDLE_KEY_RESTORE_TIME = "key_restore_time"
    }
}