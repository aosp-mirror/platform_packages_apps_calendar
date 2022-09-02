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
import android.app.Activity
import android.app.Dialog
import android.app.DialogFragment
import android.app.Service
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.SparseIntArray
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RadioGroup.OnCheckedChangeListener
import android.widget.ScrollView
import android.widget.TextView
import com.android.calendar.CalendarController.EventInfo
import com.android.calendar.CalendarController.EventType
import com.android.calendarcommon2.DateException
import com.android.calendarcommon2.Duration
import java.util.ArrayList

class EventInfoFragment : DialogFragment, OnCheckedChangeListener, CalendarController.EventHandler,
    OnClickListener {
    private var mWindowStyle = DIALOG_WINDOW_STYLE
    private var mCurrentQuery = 0

    companion object {
        const val DEBUG = false
        const val TAG = "EventInfoFragment"
        internal const val BUNDLE_KEY_EVENT_ID = "key_event_id"
        internal const val BUNDLE_KEY_START_MILLIS = "key_start_millis"
        internal const val BUNDLE_KEY_END_MILLIS = "key_end_millis"
        internal const val BUNDLE_KEY_IS_DIALOG = "key_fragment_is_dialog"
        internal const val BUNDLE_KEY_DELETE_DIALOG_VISIBLE = "key_delete_dialog_visible"
        internal const val BUNDLE_KEY_WINDOW_STYLE = "key_window_style"
        internal const val BUNDLE_KEY_CALENDAR_COLOR = "key_calendar_color"
        internal const val BUNDLE_KEY_CALENDAR_COLOR_INIT = "key_calendar_color_init"
        internal const val BUNDLE_KEY_CURRENT_COLOR = "key_current_color"
        internal const val BUNDLE_KEY_CURRENT_COLOR_KEY = "key_current_color_key"
        internal const val BUNDLE_KEY_CURRENT_COLOR_INIT = "key_current_color_init"
        internal const val BUNDLE_KEY_ORIGINAL_COLOR = "key_original_color"
        internal const val BUNDLE_KEY_ORIGINAL_COLOR_INIT = "key_original_color_init"
        internal const val BUNDLE_KEY_ATTENDEE_RESPONSE = "key_attendee_response"
        internal const val BUNDLE_KEY_USER_SET_ATTENDEE_RESPONSE = "key_user_set_attendee_response"
        internal const val BUNDLE_KEY_TENTATIVE_USER_RESPONSE = "key_tentative_user_response"
        internal const val BUNDLE_KEY_RESPONSE_WHICH_EVENTS = "key_response_which_events"
        internal const val BUNDLE_KEY_REMINDER_MINUTES = "key_reminder_minutes"
        internal const val BUNDLE_KEY_REMINDER_METHODS = "key_reminder_methods"
        private const val PERIOD_SPACE = ". "
        private const val NO_EVENT_COLOR = ""

        /**
         * These are the corresponding indices into the array of strings
         * "R.array.change_response_labels" in the resource file.
         */
        const val UPDATE_SINGLE = 0
        const val UPDATE_ALL = 1

        // Style of view
        const val FULL_WINDOW_STYLE = 0
        const val DIALOG_WINDOW_STYLE = 1

        // Query tokens for QueryHandler
        private const val TOKEN_QUERY_EVENT = 1 shl 0
        private const val TOKEN_QUERY_CALENDARS = 1 shl 1
        private const val TOKEN_QUERY_ATTENDEES = 1 shl 2
        private const val TOKEN_QUERY_DUPLICATE_CALENDARS = 1 shl 3
        private const val TOKEN_QUERY_REMINDERS = 1 shl 4
        private const val TOKEN_QUERY_VISIBLE_CALENDARS = 1 shl 5
        private const val TOKEN_QUERY_COLORS = 1 shl 6
        private const val TOKEN_QUERY_ALL = (TOKEN_QUERY_DUPLICATE_CALENDARS
            or TOKEN_QUERY_ATTENDEES or TOKEN_QUERY_CALENDARS or TOKEN_QUERY_EVENT
            or TOKEN_QUERY_REMINDERS or TOKEN_QUERY_VISIBLE_CALENDARS or TOKEN_QUERY_COLORS)
        private val EVENT_PROJECTION = arrayOf<String>(
            Events._ID, // 0  do not remove; used in DeleteEventHelper
            Events.TITLE,  // 1  do not remove; used in DeleteEventHelper
            Events.RRULE,  // 2  do not remove; used in DeleteEventHelper
            Events.ALL_DAY, // 3  do not remove; used in DeleteEventHelper
            Events.CALENDAR_ID, // 4  do not remove; used in DeleteEventHelper
            Events.DTSTART, // 5  do not remove; used in DeleteEventHelper
            Events._SYNC_ID, // 6  do not remove; used in DeleteEventHelper
            Events.EVENT_TIMEZONE, // 7  do not remove; used in DeleteEventHelper
            Events.DESCRIPTION, // 8
            Events.EVENT_LOCATION, // 9
            Calendars.CALENDAR_ACCESS_LEVEL, // 10
            Events.CALENDAR_COLOR, // 11
            Events.EVENT_COLOR, // 12
            Events.HAS_ATTENDEE_DATA, // 13
            Events.ORGANIZER,  // 14
            Events.HAS_ALARM,  // 15
            Calendars.MAX_REMINDERS, // 16
            Calendars.ALLOWED_REMINDERS, // 17
            Events.CUSTOM_APP_PACKAGE, // 18
            Events.CUSTOM_APP_URI, // 19
            Events.DTEND, // 20
            Events.DURATION, // 21
            Events.ORIGINAL_SYNC_ID // 22 do not remove; used in DeleteEventHelper
        )
        private const val EVENT_INDEX_ID = 0
        private const val EVENT_INDEX_TITLE = 1
        private const val EVENT_INDEX_RRULE = 2
        private const val EVENT_INDEX_ALL_DAY = 3
        private const val EVENT_INDEX_CALENDAR_ID = 4
        private const val EVENT_INDEX_DTSTART = 5
        private const val EVENT_INDEX_SYNC_ID = 6
        private const val EVENT_INDEX_EVENT_TIMEZONE = 7
        private const val EVENT_INDEX_DESCRIPTION = 8
        private const val EVENT_INDEX_EVENT_LOCATION = 9
        private const val EVENT_INDEX_ACCESS_LEVEL = 10
        private const val EVENT_INDEX_CALENDAR_COLOR = 11
        private const val EVENT_INDEX_EVENT_COLOR = 12
        private const val EVENT_INDEX_HAS_ATTENDEE_DATA = 13
        private const val EVENT_INDEX_ORGANIZER = 14
        private const val EVENT_INDEX_HAS_ALARM = 15
        private const val EVENT_INDEX_MAX_REMINDERS = 16
        private const val EVENT_INDEX_ALLOWED_REMINDERS = 17
        private const val EVENT_INDEX_CUSTOM_APP_PACKAGE = 18
        private const val EVENT_INDEX_CUSTOM_APP_URI = 19
        private const val EVENT_INDEX_DTEND = 20
        private const val EVENT_INDEX_DURATION = 21
        val CALENDARS_PROJECTION = arrayOf<String>(
            Calendars._ID, // 0
            Calendars.CALENDAR_DISPLAY_NAME, // 1
            Calendars.OWNER_ACCOUNT, // 2
            Calendars.CAN_ORGANIZER_RESPOND, // 3
            Calendars.ACCOUNT_NAME, // 4
            Calendars.ACCOUNT_TYPE // 5
        )
        const val CALENDARS_INDEX_DISPLAY_NAME = 1
        const val CALENDARS_INDEX_OWNER_ACCOUNT = 2
        const val CALENDARS_INDEX_OWNER_CAN_RESPOND = 3
        const val CALENDARS_INDEX_ACCOUNT_NAME = 4
        const val CALENDARS_INDEX_ACCOUNT_TYPE = 5
        val CALENDARS_WHERE: String = Calendars._ID.toString() + "=?"
        val CALENDARS_DUPLICATE_NAME_WHERE: String =
            Calendars.CALENDAR_DISPLAY_NAME.toString() + "=?"
        val CALENDARS_VISIBLE_WHERE: String = Calendars.VISIBLE.toString() + "=?"
        const val COLORS_INDEX_COLOR = 1
        const val COLORS_INDEX_COLOR_KEY = 2
        private var mScale = 0f // Used for supporting different screen densities
        private var mCustomAppIconSize = 32
        private const val FADE_IN_TIME = 300 // in milliseconds
        private const val LOADING_MSG_DELAY = 600 // in milliseconds
        private const val LOADING_MSG_MIN_DISPLAY_TIME = 600
        private var mDialogWidth = 500
        private var mDialogHeight = 600
        private var DIALOG_TOP_MARGIN = 8
        fun getResponseFromButtonId(buttonId: Int): Int {
            return Attendees.ATTENDEE_STATUS_NONE
        }

        fun findButtonIdForResponse(response: Int): Int {
            return -1
        }

        init {
            if (!Utils.isJellybeanOrLater()) {
                EVENT_PROJECTION[EVENT_INDEX_CUSTOM_APP_PACKAGE] = Events._ID // nonessential value
                EVENT_PROJECTION[EVENT_INDEX_CUSTOM_APP_URI] = Events._ID // nonessential value
            }
        }
    }

    private var mView: View? = null
    private var mUri: Uri? = null
    var eventId: Long = 0
        private set
    private val mEventCursor: Cursor? = null
    private val mCalendarsCursor: Cursor? = null
    var startMillis: Long = 0
        private set
    var endMillis: Long = 0
        private set
    private var mAllDay = false
    private var mOwnerCanRespond = false
    private var mSyncAccountName: String? = null
    private var mCalendarOwnerAccount: String? = null
    private var mIsBusyFreeCalendar = false
    private val mOriginalAttendeeResponse = 0
    private var mAttendeeResponseFromIntent: Int = Attendees.ATTENDEE_STATUS_NONE
    private val mUserSetResponse: Int = Attendees.ATTENDEE_STATUS_NONE
    private val mWhichEvents = -1

    // Used as the temporary response until the dialog is confirmed. It is also
    // able to be used as a state marker for configuration changes.
    private val mTentativeUserSetResponse: Int = Attendees.ATTENDEE_STATUS_NONE
    private var mHasAlarm = false

    // Used to prevent saving changes in event if it is being deleted.
    private val mEventDeletionStarted = false
    private var mTitle: TextView? = null
    private var mWhenDateTime: TextView? = null
    private var mWhere: TextView? = null
    private var mMenu: Menu? = null
    private var mHeadlines: View? = null
    private var mScrollView: ScrollView? = null
    private var mLoadingMsgView: View? = null
    private var mErrorMsgView: View? = null
    private var mAnimateAlpha: ObjectAnimator? = null
    private var mLoadingMsgStartTime: Long = 0
    private val mDisplayColorKeyMap: SparseIntArray = SparseIntArray()
    private val mOriginalColor = -1
    private val mOriginalColorInitialized = false
    private val mCalendarColor = -1
    private val mCalendarColorInitialized = false
    private val mCurrentColor = -1
    private val mCurrentColorInitialized = false
    private val mCurrentColorKey = -1
    private var mNoCrossFade = false // Used to prevent repeated cross-fade
    private var mResponseRadioGroup: RadioGroup? = null
    var mToEmails: ArrayList<String> = ArrayList<String>()
    var mCcEmails: ArrayList<String> = ArrayList<String>()
    private val mTZUpdater: Runnable = object : Runnable {
        @Override
        override fun run() {
            updateEvent(mView)
        }
    }
    private val mLoadingMsgAlphaUpdater: Runnable = object : Runnable {
        @Override
        override fun run() {
            // Since this is run after a delay, make sure to only show the message
            // if the event's data is not shown yet.
            if (!mAnimateAlpha!!.isRunning() && mScrollView!!.getAlpha() == 0f) {
                mLoadingMsgStartTime = System.currentTimeMillis()
                mLoadingMsgView?.setAlpha(1f)
            }
        }
    }
    private var mIsDialog = false
    private var mIsPaused = true
    private val mDismissOnResume = false
    private var mX = -1
    private var mY = -1
    private var mMinTop = 0 // Dialog cannot be above this location
    private var mIsTabletConfig = false
    private var mActivity: Activity? = null
    private var mContext: Context? = null
    private var mController: CalendarController? = null
    private fun sendAccessibilityEventIfQueryDone(token: Int) {
        mCurrentQuery = mCurrentQuery or token
        if (mCurrentQuery == TOKEN_QUERY_ALL) {
            sendAccessibilityEvent()
        }
    }

    constructor(
        context: Context,
        uri: Uri?,
        startMillis: Long,
        endMillis: Long,
        attendeeResponse: Int,
        isDialog: Boolean,
        windowStyle: Int
    ) {
        val r: Resources = context.getResources()
        if (mScale == 0f) {
            mScale = context.getResources().getDisplayMetrics().density
            if (mScale != 1f) {
                mCustomAppIconSize *= mScale.toInt()
                if (isDialog) {
                    DIALOG_TOP_MARGIN *= mScale.toInt()
                }
            }
        }
        if (isDialog) {
            setDialogSize(r)
        }
        mIsDialog = isDialog
        setStyle(DialogFragment.STYLE_NO_TITLE, 0)
        mUri = uri
        this.startMillis = startMillis
        this.endMillis = endMillis
        mAttendeeResponseFromIntent = attendeeResponse
        mWindowStyle = windowStyle
    }

    // This is currently required by the fragment manager.
    constructor() {}
    constructor(
        context: Context?,
        eventId: Long,
        startMillis: Long,
        endMillis: Long,
        attendeeResponse: Int,
        isDialog: Boolean,
        windowStyle: Int
    ) : this(
        context as Context, ContentUris.withAppendedId(Events.CONTENT_URI, eventId), startMillis,
        endMillis, attendeeResponse, isDialog, windowStyle
    ) {
        this.eventId = eventId
    }

    @Override
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (mIsDialog) {
            applyDialogParams()
        }
        val activity: Activity = getActivity()
        mContext = activity
    }

    private fun applyDialogParams() {
        val dialog: Dialog = getDialog()
        dialog.setCanceledOnTouchOutside(true)
        val window: Window? = dialog.getWindow()
        window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val a: WindowManager.LayoutParams? = window?.getAttributes()
        a!!.dimAmount = .4f
        a.width = mDialogWidth
        a.height = mDialogHeight

        // On tablets , do smart positioning of dialog
        // On phones , use the whole screen
        if (mX != -1 || mY != -1) {
            a.x = mX - mDialogWidth / 2
            a.y = mY - mDialogHeight / 2
            if (a.y < mMinTop) {
                a.y = mMinTop + DIALOG_TOP_MARGIN
            }
            a.gravity = Gravity.LEFT or Gravity.TOP
        }
        window.setAttributes(a)
    }

    fun setDialogParams(x: Int, y: Int, minTop: Int) {
        mX = x
        mY = y
        mMinTop = minTop
    }

    // Implements OnCheckedChangeListener
    @Override
    override fun onCheckedChanged(group: RadioGroup?, checkedId: Int) {
    }

    fun onNothingSelected(parent: AdapterView<*>?) {}
    @Override
    override fun onDetach() {
        super.onDetach()
        mController?.deregisterEventHandler(R.layout.event_info)
    }

    @Override
    override fun onAttach(activity: Activity?) {
        super.onAttach(activity)
        mActivity = activity
        // Ensure that mIsTabletConfig is set before creating the menu.
        mIsTabletConfig = Utils.getConfigBool(mActivity as Context, R.bool.tablet_config)
        mController = CalendarController.getInstance(mActivity)
        mController?.registerEventHandler(R.layout.event_info, this)
        if (!mIsDialog) {
            setHasOptionsMenu(true)
        }
    }

    @Override
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        mView = if (mWindowStyle == DIALOG_WINDOW_STYLE) {
            inflater.inflate(R.layout.event_info_dialog, container, false)
        } else {
            inflater.inflate(R.layout.event_info, container, false)
        }
        mScrollView = mView?.findViewById(R.id.event_info_scroll_view) as ScrollView
        mLoadingMsgView = mView?.findViewById(R.id.event_info_loading_msg)
        mErrorMsgView = mView?.findViewById(R.id.event_info_error_msg)
        mTitle = mView?.findViewById(R.id.title) as TextView
        mWhenDateTime = mView?.findViewById(R.id.when_datetime) as TextView
        mWhere = mView?.findViewById(R.id.where) as TextView
        mHeadlines = mView?.findViewById(R.id.event_info_headline)
        mResponseRadioGroup = mView?.findViewById(R.id.response_value) as RadioGroup
        mAnimateAlpha = ObjectAnimator.ofFloat(mScrollView, "Alpha", 0f, 1f)
        mAnimateAlpha?.setDuration(FADE_IN_TIME.toLong())
        mAnimateAlpha?.addListener(object : AnimatorListenerAdapter() {
            var defLayerType = 0
            @Override
            override fun onAnimationStart(animation: Animator) {
                // Use hardware layer for better performance during animation
                defLayerType = mScrollView?.getLayerType() as Int
                mScrollView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                // Ensure that the loading message is gone before showing the
                // event info
                mLoadingMsgView?.removeCallbacks(mLoadingMsgAlphaUpdater)
                mLoadingMsgView?.setVisibility(View.GONE)
            }

            @Override
            override fun onAnimationCancel(animation: Animator) {
                mScrollView?.setLayerType(defLayerType, null)
            }

            @Override
            override fun onAnimationEnd(animation: Animator) {
                mScrollView?.setLayerType(defLayerType, null)
                // Do not cross fade after the first time
                mNoCrossFade = true
            }
        })
        mLoadingMsgView?.setAlpha(0f)
        mScrollView?.setAlpha(0f)
        mErrorMsgView?.setVisibility(View.INVISIBLE)
        mLoadingMsgView?.postDelayed(mLoadingMsgAlphaUpdater, LOADING_MSG_DELAY.toLong())

        // Hide Edit/Delete buttons if in full screen mode on a phone
        if (!mIsDialog && !mIsTabletConfig || mWindowStyle == FULL_WINDOW_STYLE) {
            mView?.findViewById<View>(R.id.event_info_buttons_container)?.setVisibility(View.GONE)
        }
        return mView
    }

    private fun updateTitle() {
        val res: Resources = getActivity().getResources()
        getActivity().setTitle(res.getString(R.string.event_info_title))
    }

    /**
     * Initializes the event cursor, which is expected to point to the first
     * (and only) result from a query.
     * @return false if the cursor is empty, true otherwise
     */
    private fun initEventCursor(): Boolean {
        if (mEventCursor == null || mEventCursor.getCount() === 0) {
            return false
        }
        mEventCursor.moveToFirst()
        eventId = mEventCursor.getInt(EVENT_INDEX_ID).toLong()
        val rRule: String = mEventCursor.getString(EVENT_INDEX_RRULE)
        // mHasAlarm will be true if it was saved in the event already.
        mHasAlarm = if (mEventCursor.getInt(EVENT_INDEX_HAS_ALARM) === 1) true else false
        return true
    }

    @Override
    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
    }

    @Override
    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        // Show color/edit/delete buttons only in non-dialog configuration
        if (!mIsDialog && !mIsTabletConfig || mWindowStyle == FULL_WINDOW_STYLE) {
            inflater.inflate(R.menu.event_info_title_bar, menu)
            mMenu = menu
        }
    }

    @Override
    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        // If we're a dialog we don't want to handle menu buttons
        if (mIsDialog) {
            return false
        }
        // Handles option menu selections:
        // Home button - close event info activity and start the main calendar
        // one
        // Edit button - start the event edit activity and close the info
        // activity
        // Delete button - start a delete query that calls a runnable that close
        // the info activity
        val itemId: Int = item.getItemId()
        if (itemId == android.R.id.home) {
            Utils.returnToCalendarHome(mContext as Context)
            mActivity?.finish()
            return true
        } else if (itemId == R.id.info_action_edit) {
            mActivity?.finish()
        }
        return super.onOptionsItemSelected(item)
    }

    @Override
    override fun onStop() {
        super.onStop()
    }

    @Override
    override fun onDestroy() {
        if (mEventCursor != null) {
            mEventCursor.close()
        }
        if (mCalendarsCursor != null) {
            mCalendarsCursor.close()
        }
        super.onDestroy()
    }

    /**
     * Creates an exception to a recurring event.  The only change we're making is to the
     * "self attendee status" value.  The provider will take care of updating the corresponding
     * Attendees.attendeeStatus entry.
     *
     * @param eventId The recurring event.
     * @param status The new value for selfAttendeeStatus.
     */
    private fun createExceptionResponse(eventId: Long, status: Int) {
        val values = ContentValues()
        values.put(Events.ORIGINAL_INSTANCE_TIME, startMillis)
        values.put(Events.SELF_ATTENDEE_STATUS, status)
        values.put(Events.STATUS, Events.STATUS_CONFIRMED)
        val ops: ArrayList<ContentProviderOperation> = ArrayList<ContentProviderOperation>()
        val exceptionUri: Uri = Uri.withAppendedPath(
            Events.CONTENT_EXCEPTION_URI,
            eventId.toString()
        )
        ops.add(ContentProviderOperation.newInsert(exceptionUri).withValues(values).build())
    }

    private fun displayEventNotFound() {
        mErrorMsgView?.setVisibility(View.VISIBLE)
        mScrollView?.setVisibility(View.GONE)
        mLoadingMsgView?.setVisibility(View.GONE)
    }

    private fun updateEvent(view: View?) {
        if (mEventCursor == null || view == null) {
            return
        }
        val context: Context = view.getContext() ?: return
        var eventName: String = mEventCursor.getString(EVENT_INDEX_TITLE)
        if (eventName == null || eventName.length == 0) {
            eventName = getActivity().getString(R.string.no_title_label)
        }

        // 3rd parties might not have specified the start/end time when firing the
        // Events.CONTENT_URI intent.  Update these with values read from the db.
        if (startMillis == 0L && endMillis == 0L) {
            startMillis = mEventCursor.getLong(EVENT_INDEX_DTSTART)
            endMillis = mEventCursor.getLong(EVENT_INDEX_DTEND)
            if (endMillis == 0L) {
                val duration: String = mEventCursor.getString(EVENT_INDEX_DURATION)
                if (!TextUtils.isEmpty(duration)) {
                    try {
                        val d = Duration()
                        d.parse(duration)
                        val endMillis: Long = startMillis + d.getMillis()
                        if (endMillis >= startMillis) {
                            this.endMillis = endMillis
                        } else {
                            Log.d(TAG, "Invalid duration string: $duration")
                        }
                    } catch (e: DateException) {
                        Log.d(TAG, "Error parsing duration string $duration", e)
                    }
                }
                if (endMillis == 0L) {
                    endMillis = startMillis
                }
            }
        }
        mAllDay = mEventCursor.getInt(EVENT_INDEX_ALL_DAY) !== 0
        val location: String = mEventCursor.getString(EVENT_INDEX_EVENT_LOCATION)
        val description: String = mEventCursor.getString(EVENT_INDEX_DESCRIPTION)
        val rRule: String = mEventCursor.getString(EVENT_INDEX_RRULE)
        val eventTimezone: String = mEventCursor.getString(EVENT_INDEX_EVENT_TIMEZONE)
        mHeadlines?.setBackgroundColor(mCurrentColor)

        // What
        if (eventName != null) {
            setTextCommon(view, R.id.title, eventName)
        }

        // When
        // Set the date and repeats (if any)
        val localTimezone: String? = Utils.getTimeZone(mActivity, mTZUpdater)
        val resources: Resources = context.getResources()
        var displayedDatetime: String? = Utils.getDisplayedDatetime(
            startMillis, endMillis,
            System.currentTimeMillis(), localTimezone as String, mAllDay, context
        )
        var displayedTimezone: String? = null
        if (!mAllDay) {
            displayedTimezone = Utils.getDisplayedTimezone(
                startMillis, localTimezone,
                eventTimezone
            )
        }
        // Display the datetime.  Make the timezone (if any) transparent.
        if (displayedTimezone == null) {
            setTextCommon(view, R.id.when_datetime, displayedDatetime as CharSequence)
        } else {
            val timezoneIndex: Int = displayedDatetime!!.length
            displayedDatetime += "  $displayedTimezone"
            val sb = SpannableStringBuilder(displayedDatetime)
            val transparentColorSpan = ForegroundColorSpan(
                resources.getColor(R.color.event_info_headline_transparent_color)
            )
            sb.setSpan(
                transparentColorSpan, timezoneIndex, displayedDatetime.length,
                Spannable.SPAN_INCLUSIVE_INCLUSIVE
            )
            setTextCommon(view, R.id.when_datetime, sb)
        }
        view.findViewById<View>(R.id.when_repeat).setVisibility(View.GONE)

        // Organizer view is setup in the updateCalendar method

        // Where
        if (location == null || location.trim().length == 0) {
            setVisibilityCommon(view, R.id.where, View.GONE)
        } else {
            val textView: TextView? = mWhere
            if (textView != null) {
                textView.setText(location.trim())
            }
        }

        // Launch Custom App
        if (Utils.isJellybeanOrLater()) {
            updateCustomAppButton()
        }
    }

    private fun updateCustomAppButton() {
        setVisibilityCommon(mView, R.id.launch_custom_app_container, View.GONE)
        return
    }

    private fun sendAccessibilityEvent() {
        val am: AccessibilityManager =
            getActivity().getSystemService(Service.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled()) {
            return
        }
        val event: AccessibilityEvent =
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        event.setClassName(EventInfoFragment::class.java.getName())
        event.setPackageName(getActivity().getPackageName())
        var text = event.getText()
        if (mResponseRadioGroup?.getVisibility() == View.VISIBLE) {
            val id: Int = mResponseRadioGroup!!.getCheckedRadioButtonId()
            if (id != View.NO_ID) {
                text.add((getView()?.findViewById(R.id.response_label) as TextView).getText())
                text.add(
                    (mResponseRadioGroup?.findViewById(id) as RadioButton)
                        .getText().toString() + PERIOD_SPACE
                )
            }
        }
        am.sendAccessibilityEvent(event)
    }

    private fun updateCalendar(view: View?) {
        mCalendarOwnerAccount = ""
        if (mCalendarsCursor != null && mEventCursor != null) {
            mCalendarsCursor.moveToFirst()
            val tempAccount: String = mCalendarsCursor.getString(CALENDARS_INDEX_OWNER_ACCOUNT)
            mCalendarOwnerAccount = tempAccount ?: ""
            mOwnerCanRespond = mCalendarsCursor.getInt(CALENDARS_INDEX_OWNER_CAN_RESPOND) !== 0
            mSyncAccountName = mCalendarsCursor.getString(CALENDARS_INDEX_ACCOUNT_NAME)
            setVisibilityCommon(view, R.id.organizer_container, View.GONE)
            mIsBusyFreeCalendar =
                mEventCursor.getInt(EVENT_INDEX_ACCESS_LEVEL) === Calendars.CAL_ACCESS_FREEBUSY
            if (!mIsBusyFreeCalendar) {
                val b: View? = mView?.findViewById(R.id.edit)
                b?.setEnabled(true)
                b?.setOnClickListener(object : OnClickListener {
                    @Override
                    override fun onClick(v: View?) {
                        // For dialogs, just close the fragment
                        // For full screen, close activity on phone, leave it for tablet
                        if (mIsDialog) {
                            this@EventInfoFragment.dismiss()
                        } else if (!mIsTabletConfig) {
                            getActivity().finish()
                        }
                    }
                })
            }
            var button: View
            if ((!mIsDialog && !mIsTabletConfig ||
                mWindowStyle == FULL_WINDOW_STYLE) && mMenu != null
            ) {
                mActivity?.invalidateOptionsMenu()
            }
        } else {
            setVisibilityCommon(view, R.id.calendar, View.GONE)
            sendAccessibilityEventIfQueryDone(TOKEN_QUERY_DUPLICATE_CALENDARS)
        }
    }

    private fun setTextCommon(view: View, id: Int, text: CharSequence) {
        val textView: TextView = view.findViewById(id) as TextView ?: return
        textView.setText(text)
    }

    private fun setVisibilityCommon(view: View?, id: Int, visibility: Int) {
        val v: View? = view?.findViewById(id)
        if (v != null) {
            v.setVisibility(visibility)
        }
        return
    }

    @Override
    override fun onPause() {
        mIsPaused = true
        super.onPause()
    }

    @Override
    override fun onResume() {
        super.onResume()
        if (mIsDialog) {
            setDialogSize(getActivity().getResources())
            applyDialogParams()
        }
        mIsPaused = false
        if (mTentativeUserSetResponse != Attendees.ATTENDEE_STATUS_NONE) {
            val buttonId = findButtonIdForResponse(mTentativeUserSetResponse)
            mResponseRadioGroup?.check(buttonId)
        }
    }

    @Override
    override fun eventsChanged() {
    }

    @get:Override override val supportedEventTypes: Long
        get() = EventType.EVENTS_CHANGED

    @Override
    override fun handleEvent(event: EventInfo?) {
        reloadEvents()
    }

    fun reloadEvents() {}
    @Override
    override fun onClick(view: View?) {
    }

    private fun setDialogSize(r: Resources) {
        mDialogWidth = r.getDimension(R.dimen.event_info_dialog_width).toInt()
        mDialogHeight = r.getDimension(R.dimen.event_info_dialog_height).toInt()
    }
}