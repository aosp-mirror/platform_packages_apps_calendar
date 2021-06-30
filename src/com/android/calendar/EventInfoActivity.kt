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

import android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME
import android.provider.CalendarContract.EXTRA_EVENT_END_TIME
import android.provider.CalendarContract.Attendees.ATTENDEE_STATUS
import android.app.ActionBar
import android.app.Activity
import android.app.FragmentManager
import android.app.FragmentTransaction
import android.content.Intent
import android.content.res.Resources
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.util.Log
import android.widget.Toast

class EventInfoActivity : Activity() {
    private var mInfoFragment: EventInfoFragment? = null
    private var mStartMillis: Long = 0
    private var mEndMillis: Long = 0
    private var mEventId: Long = 0

    // Create an observer so that we can update the views whenever a
    // Calendar event changes.
    private val mObserver: ContentObserver = object : ContentObserver(Handler()) {
        @Override
        override fun deliverSelfNotifications(): Boolean {
            return false
        }

        @Override
        override fun onChange(selfChange: Boolean) {
            if (selfChange) return
            val temp = mInfoFragment
            if (temp != null) {
                mInfoFragment?.reloadEvents()
            }
        }
    }

    @Override
    protected override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)

        // Get the info needed for the fragment
        val intent: Intent = getIntent()
        var attendeeResponse = 0
        mEventId = -1
        var isDialog = false
        if (icicle != null) {
            mEventId = icicle.getLong(EventInfoFragment.BUNDLE_KEY_EVENT_ID)
            mStartMillis = icicle.getLong(EventInfoFragment.BUNDLE_KEY_START_MILLIS)
            mEndMillis = icicle.getLong(EventInfoFragment.BUNDLE_KEY_END_MILLIS)
            attendeeResponse = icicle.getInt(EventInfoFragment.BUNDLE_KEY_ATTENDEE_RESPONSE)
            isDialog = icicle.getBoolean(EventInfoFragment.BUNDLE_KEY_IS_DIALOG)
        } else if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            mStartMillis = intent.getLongExtra(EXTRA_EVENT_BEGIN_TIME, 0)
            mEndMillis = intent.getLongExtra(EXTRA_EVENT_END_TIME, 0)
            attendeeResponse = intent.getIntExtra(
                    ATTENDEE_STATUS,
                    Attendees.ATTENDEE_STATUS_NONE
            )
            val data: Uri? = intent.getData()
            if (data != null) {
                try {
                    val pathSegments = data.getPathSegments()
                    val size: Int = pathSegments.size
                    if (size > 2 && "EventTime".equals(pathSegments[2])) {
                        // Support non-standard VIEW intent format:
                        // dat = content://com.android.calendar/events/[id]/EventTime/[start]/[end]
                        mEventId = pathSegments[1].toLong()
                        if (size > 4) {
                            mStartMillis = pathSegments[3].toLong()
                            mEndMillis = pathSegments[4].toLong()
                        }
                    } else {
                        mEventId = data.getLastPathSegment() as Long
                    }
                } catch (e: NumberFormatException) {
                    if (mEventId == -1L) {
                        // do nothing here , deal with it later
                    } else if (mStartMillis == 0L || mEndMillis == 0L) {
                        // Parsing failed on the start or end time , make sure the times were not
                        // pulled from the intent's extras and reset them.
                        mStartMillis = 0
                        mEndMillis = 0
                    }
                }
            }
        }
        if (mEventId == -1L) {
            Log.w(TAG, "No event id")
            Toast.makeText(this, R.string.event_not_found, Toast.LENGTH_SHORT).show()
            finish()
        }

        // If we do not support showing full screen event info in this configuration,
        // close the activity and show the event in AllInOne.
        val res: Resources = getResources()
        if (!res.getBoolean(R.bool.agenda_show_event_info_full_screen) &&
                !res.getBoolean(R.bool.show_event_info_full_screen)
        ) {
            CalendarController.getInstance(this)
                    ?.launchViewEvent(mEventId, mStartMillis, mEndMillis, attendeeResponse)
            finish()
            return
        }
        setContentView(R.layout.simple_frame_layout)

        // Get the fragment if exists
        mInfoFragment = getFragmentManager().findFragmentById(R.id.main_frame) as EventInfoFragment

        // Remove the application title
        val bar: ActionBar? = getActionBar()
        if (bar != null) {
            bar.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP or ActionBar.DISPLAY_SHOW_HOME)
        }

        // Create a new fragment if none exists
        if (mInfoFragment == null) {
            val fragmentManager: FragmentManager = getFragmentManager()
            val ft: FragmentTransaction = fragmentManager.beginTransaction()
            mInfoFragment = EventInfoFragment(
                    this,
                    mEventId,
                    mStartMillis,
                    mEndMillis,
                    attendeeResponse,
                    isDialog,
                    if (isDialog) EventInfoFragment.DIALOG_WINDOW_STYLE
                    else EventInfoFragment.FULL_WINDOW_STYLE
            )
            ft.replace(R.id.main_frame, mInfoFragment)
            ft.commit()
        }
    }

    @Override
    protected override fun onNewIntent(intent: Intent?) {
        // From the Android Dev Guide: "It's important to note that when
        // onNewIntent(Intent) is called, the Activity has not been restarted,
        // so the getIntent() method will still return the Intent that was first
        // received with onCreate(). This is why setIntent(Intent) is called
        // inside onNewIntent(Intent) (just in case you call getIntent() at a
        // later time)."
        setIntent(intent)
    }

    @Override
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    @Override
    protected override fun onResume() {
        super.onResume()
        getContentResolver().registerContentObserver(
                CalendarContract.Events.CONTENT_URI,
                true, mObserver
        )
    }

    @Override
    protected override fun onPause() {
        super.onPause()
        getContentResolver().unregisterContentObserver(mObserver)
    }

    @Override
    protected override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EventInfoActivity"
    }
}