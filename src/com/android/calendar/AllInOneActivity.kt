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

import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.accounts.AccountManagerFuture
import android.animation.Animator
import android.animation.Animator.AnimatorListener
import android.animation.ObjectAnimator
import android.app.ActionBar
import android.app.ActionBar.Tab
import android.app.Activity
import android.app.Fragment
import android.app.FragmentManager
import android.app.FragmentTransaction
import android.content.AsyncQueryHandler
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.Configuration
import android.content.res.Resources
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Time
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.RelativeLayout.LayoutParams
import android.widget.TextView
import com.android.calendar.CalendarController.EventHandler
import com.android.calendar.CalendarController.EventInfo
import com.android.calendar.CalendarController.EventType
import com.android.calendar.CalendarController.ViewType
import com.android.calendar.month.MonthByWeekFragment
import java.util.Locale
import java.util.TimeZone
import android.provider.CalendarContract.Attendees.ATTENDEE_STATUS
import android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY
import android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME
import android.provider.CalendarContract.EXTRA_EVENT_END_TIME

class AllInOneActivity : Activity(), EventHandler, OnSharedPreferenceChangeListener,
    ActionBar.TabListener, ActionBar.OnNavigationListener {
    private var mController: CalendarController? = null
    private var mOnSaveInstanceStateCalled = false
    private var mBackToPreviousView = false
    private var mContentResolver: ContentResolver? = null
    private var mPreviousView = 0
    private var mCurrentView = 0
    private var mPaused = true
    private var mUpdateOnResume = false
    private var mHideControls = false
    private var mShowSideViews = true
    private var mShowWeekNum = false
    private var mHomeTime: TextView? = null
    private var mDateRange: TextView? = null
    private var mWeekTextView: TextView? = null
    private var mMiniMonth: View? = null
    private var mCalendarsList: View? = null
    private var mMiniMonthContainer: View? = null
    private var mSecondaryPane: View? = null
    private var mTimeZone: String? = null
    private var mShowCalendarControls = false
    private var mShowEventInfoFullScreen = false
    private var mWeekNum = 0
    private var mCalendarControlsAnimationTime = 0
    private var mControlsAnimateWidth = 0
    private var mControlsAnimateHeight = 0
    private var mViewEventId: Long = -1
    private var mIntentEventStartMillis: Long = -1
    private var mIntentEventEndMillis: Long = -1
    private var mIntentAttendeeResponse: Int = Attendees.ATTENDEE_STATUS_NONE
    private var mIntentAllDay = false

    // Action bar and Navigation bar (left side of Action bar)
    private var mActionBar: ActionBar? = null
    private val mDayTab: Tab? = null
    private val mWeekTab: Tab? = null
    private val mMonthTab: Tab? = null
    private var mControlsMenu: MenuItem? = null
    private var mOptionsMenu: Menu? = null
    private var mActionBarMenuSpinnerAdapter: CalendarViewAdapter? = null
    private var mHandler: QueryHandler? = null
    private var mCheckForAccounts = true
    private var mHideString: String? = null
    private var mShowString: String? = null
    var mDayOfMonthIcon: DayOfMonthDrawable? = null
    var mOrientation = 0

    // Params for animating the controls on the right
    private var mControlsParams: LayoutParams? = null
    private var mVerticalControlsParams: LinearLayout.LayoutParams? = null
    private val mSlideAnimationDoneListener: AnimatorListener = object : AnimatorListener {
        @Override
        override fun onAnimationCancel(animation: Animator) {
        }

        @Override
        override fun onAnimationEnd(animation: Animator) {
            val visibility: Int = if (mShowSideViews) View.VISIBLE else View.GONE
            mMiniMonth?.setVisibility(visibility)
            mCalendarsList?.setVisibility(visibility)
            mMiniMonthContainer?.setVisibility(visibility)
        }

        @Override
        override fun onAnimationRepeat(animation: Animator) {
        }

        @Override
        override fun onAnimationStart(animation: Animator) {
        }
    }

    private inner class QueryHandler(cr: ContentResolver?) : AsyncQueryHandler(cr) {
        @Override
        protected override fun onQueryComplete(token: Int, cookie: Any?, cursor: Cursor?) {
            mCheckForAccounts = false
            try {
                // If the query didn't return a cursor for some reason return
                if (cursor == null || cursor.getCount() > 0 || isFinishing()) {
                    return
                }
            } finally {
                if (cursor != null) {
                    cursor.close()
                }
            }
            val options = Bundle()
            options.putCharSequence(
                "introMessage",
                getResources().getString(R.string.create_an_account_desc)
            )
            options.putBoolean("allowSkip", true)
            val am: AccountManager = AccountManager.get(this@AllInOneActivity)
            am.addAccount("com.google", CalendarContract.AUTHORITY, null, options,
                this@AllInOneActivity,
                    object : AccountManagerCallback<Bundle?> {
                        @Override
                        override fun run(future: AccountManagerFuture<Bundle?>?) {
                        }
                    }, null
            )
        }
    }

    private val mHomeTimeUpdater: Runnable = object : Runnable {
        @Override
        override fun run() {
            mTimeZone = Utils.getTimeZone(this@AllInOneActivity, this)
            updateSecondaryTitleFields(-1)
            this@AllInOneActivity.invalidateOptionsMenu()
            Utils.setMidnightUpdater(mHandler, mTimeChangesUpdater, mTimeZone)
        }
    }

    // runs every midnight/time changes and refreshes the today icon
    private val mTimeChangesUpdater: Runnable = object : Runnable {
        @Override
        override fun run() {
            mTimeZone = Utils.getTimeZone(this@AllInOneActivity, mHomeTimeUpdater)
            this@AllInOneActivity.invalidateOptionsMenu()
            Utils.setMidnightUpdater(mHandler, this, mTimeZone)
        }
    }

    // Create an observer so that we can update the views whenever a
    // Calendar event changes.
    private val mObserver: ContentObserver = object : ContentObserver(Handler()) {
        @Override
        override fun deliverSelfNotifications(): Boolean {
            return true
        }

        @Override
        override fun onChange(selfChange: Boolean) {
            eventsChanged()
        }
    }

    @Override
    protected override fun onNewIntent(intent: Intent) {
        val action: String? = intent.getAction()
        if (DEBUG) Log.d(TAG, "New intent received " + intent.toString())
        // Don't change the date if we're just returning to the app's home
        if (Intent.ACTION_VIEW.equals(action) &&
            !intent.getBooleanExtra(Utils.INTENT_KEY_HOME, false)
        ) {
            var millis = parseViewAction(intent)
            if (millis == -1L) {
                millis = Utils.timeFromIntentInMillis(intent) as Long
            }
            if (millis != -1L && mViewEventId == -1L && mController != null) {
                val time = Time(mTimeZone)
                time.set(millis)
                time.normalize(true)
                mController?.sendEvent(this as Object?, EventType.GO_TO, time, time, -1,
                    ViewType.CURRENT)
            }
        }
    }

    @Override
    protected override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        if (icicle != null && icicle.containsKey(BUNDLE_KEY_CHECK_ACCOUNTS)) {
            mCheckForAccounts = icicle.getBoolean(BUNDLE_KEY_CHECK_ACCOUNTS)
        }
        // Launch add google account if this is first time and there are no
        // accounts yet
        if (mCheckForAccounts) {
            mHandler = QueryHandler(this.getContentResolver())
            mHandler?.startQuery(
                0, null, Calendars.CONTENT_URI, arrayOf<String>(
                    Calendars._ID
                ), null, null /* selection args */, null /* sort order */
            )
        }

        // This needs to be created before setContentView
        mController = CalendarController.getInstance(this)

        // Get time from intent or icicle
        var timeMillis: Long = -1
        var viewType = -1
        val intent: Intent = getIntent()
        if (icicle != null) {
            timeMillis = icicle.getLong(BUNDLE_KEY_RESTORE_TIME)
            viewType = icicle.getInt(BUNDLE_KEY_RESTORE_VIEW, -1)
        } else {
            val action: String? = intent.getAction()
            if (Intent.ACTION_VIEW.equals(action)) {
                // Open EventInfo later
                timeMillis = parseViewAction(intent)
            }
            if (timeMillis == -1L) {
                timeMillis = Utils.timeFromIntentInMillis(intent) as Long
            }
        }
        if (viewType == -1 || viewType > ViewType.MAX_VALUE) {
            viewType = Utils.getViewTypeFromIntentAndSharedPref(this)
        }
        mTimeZone = Utils.getTimeZone(this, mHomeTimeUpdater)
        val t = Time(mTimeZone)
        t.set(timeMillis)
        if (DEBUG) {
            if (icicle != null && intent != null) {
                Log.d(
                    TAG,
                    "both, icicle:" + icicle.toString().toString() + "  intent:" + intent.toString()
                )
            } else {
                Log.d(TAG, "not both, icicle:$icicle intent:$intent")
            }
        }
        val res: Resources = getResources()
        mHideString = res.getString(R.string.hide_controls)
        mShowString = res.getString(R.string.show_controls)
        mOrientation = res.getConfiguration().orientation
        if (mOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            mControlsAnimateWidth = res.getDimension(R.dimen.calendar_controls_width).toInt()
            if (mControlsParams == null) {
                mControlsParams = LayoutParams(mControlsAnimateWidth, 0)
            }
            mControlsParams?.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
        } else {
            // Make sure width is in between allowed min and max width values
            mControlsAnimateWidth = Math.max(
                res.getDisplayMetrics().widthPixels * 45 / 100,
                res.getDimension(R.dimen.min_portrait_calendar_controls_width).toInt()
            )
            mControlsAnimateWidth = Math.min(
                mControlsAnimateWidth,
                res.getDimension(R.dimen.max_portrait_calendar_controls_width).toInt()
            )
        }
        mControlsAnimateHeight = res.getDimension(R.dimen.calendar_controls_height).toInt()
        mHideControls = true
        mIsMultipane = Utils.getConfigBool(this, R.bool.multiple_pane_config)
        mIsTabletConfig = Utils.getConfigBool(this, R.bool.tablet_config)
        mShowCalendarControls = Utils.getConfigBool(this, R.bool.show_calendar_controls)
        mShowEventInfoFullScreen = Utils.getConfigBool(this, R.bool.show_event_info_full_screen)
        mCalendarControlsAnimationTime = res.getInteger(R.integer.calendar_controls_animation_time)
        Utils.setAllowWeekForDetailView(mIsMultipane)

        // setContentView must be called before configureActionBar
        setContentView(R.layout.all_in_one)
        if (mIsTabletConfig) {
            mDateRange = findViewById(R.id.date_bar) as TextView?
            mWeekTextView = findViewById(R.id.week_num) as TextView?
        } else {
            mDateRange = getLayoutInflater().inflate(R.layout.date_range_title, null) as TextView
        }

        // configureActionBar auto-selects the first tab you add, so we need to
        // call it before we set up our own fragments to make sure it doesn't
        // overwrite us
        configureActionBar(viewType)
        mHomeTime = findViewById(R.id.home_time) as TextView?
        mMiniMonth = findViewById(R.id.mini_month)
        if (mIsTabletConfig && mOrientation == Configuration.ORIENTATION_PORTRAIT) {
            mMiniMonth?.setLayoutParams(
                LayoutParams(
                    mControlsAnimateWidth,
                    mControlsAnimateHeight
                )
            )
        }
        mCalendarsList = findViewById(R.id.calendar_list)
        mMiniMonthContainer = findViewById(R.id.mini_month_container)
        mSecondaryPane = findViewById(R.id.secondary_pane)

        // Must register as the first activity because this activity can modify
        // the list of event handlers in it's handle method. This affects who
        // the rest of the handlers the controller dispatches to are.
        mController?.registerFirstEventHandler(HANDLER_KEY, this)
        initFragments(timeMillis, viewType, icicle)

        // Listen for changes that would require this to be refreshed
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(this)
        prefs?.registerOnSharedPreferenceChangeListener(this)
        mContentResolver = getContentResolver()
    }

    private fun parseViewAction(intent: Intent?): Long {
        var timeMillis: Long = -1
        val data: Uri? = intent?.getData()
        if (data != null && data.isHierarchical()) {
            val path = data.getPathSegments()
            if (path?.size == 2 && path[0].equals("events")) {
                try {
                    mViewEventId = data.getLastPathSegment()?.toLong() as Long
                    if (mViewEventId != -1L) {
                        mIntentEventStartMillis = intent.getLongExtra(EXTRA_EVENT_BEGIN_TIME, 0)
                        mIntentEventEndMillis = intent.getLongExtra(EXTRA_EVENT_END_TIME, 0)
                        mIntentAttendeeResponse = intent.getIntExtra(
                            ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_NONE
                        )
                        mIntentAllDay = intent.getBooleanExtra(EXTRA_EVENT_ALL_DAY, false)
                            as Boolean
                        timeMillis = mIntentEventStartMillis
                    }
                } catch (e: NumberFormatException) {
                    // Ignore if mViewEventId can't be parsed
                }
            }
        }
        return timeMillis
    }

    private fun configureActionBar(viewType: Int) {
        createButtonsSpinner(viewType, mIsTabletConfig)
        if (mIsMultipane) {
            mActionBar?.setDisplayOptions(
                ActionBar.DISPLAY_SHOW_CUSTOM or ActionBar.DISPLAY_SHOW_HOME
            )
        } else {
            mActionBar?.setDisplayOptions(0)
        }
    }

    private fun createButtonsSpinner(viewType: Int, tabletConfig: Boolean) {
        // If tablet configuration , show spinner with no dates
        mActionBarMenuSpinnerAdapter = CalendarViewAdapter(this, viewType, !tabletConfig)
        mActionBar = getActionBar()
        mActionBar?.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST)
        mActionBar?.setListNavigationCallbacks(mActionBarMenuSpinnerAdapter, this)
        when (viewType) {
            ViewType.AGENDA -> {
            }
            ViewType.DAY -> mActionBar?.setSelectedNavigationItem(BUTTON_DAY_INDEX)
            ViewType.WEEK -> mActionBar?.setSelectedNavigationItem(BUTTON_WEEK_INDEX)
            ViewType.MONTH -> mActionBar?.setSelectedNavigationItem(BUTTON_MONTH_INDEX)
            else -> mActionBar?.setSelectedNavigationItem(BUTTON_DAY_INDEX)
        }
    }

    // Clear buttons used in the agenda view
    private fun clearOptionsMenu() {
        if (mOptionsMenu == null) {
            return
        }
        val cancelItem: MenuItem? = mOptionsMenu?.findItem(R.id.action_cancel)
        if (cancelItem != null) {
            cancelItem.setVisible(false)
        }
    }

    @Override
    protected override fun onResume() {
        super.onResume()

        // Check if the upgrade code has ever been run. If not, force a sync just this one time.
        Utils.trySyncAndDisableUpgradeReceiver(this)

        // Must register as the first activity because this activity can modify
        // the list of event handlers in it's handle method. This affects who
        // the rest of the handlers the controller dispatches to are.
        mController?.registerFirstEventHandler(HANDLER_KEY, this)
        mOnSaveInstanceStateCalled = false
        mContentResolver?.registerContentObserver(
            CalendarContract.Events.CONTENT_URI,
            true, mObserver
        )
        if (mUpdateOnResume) {
            initFragments(mController?.time as Long, mController?.viewType as Int, null)
            mUpdateOnResume = false
        }
        val t = Time(mTimeZone)
        t.set(mController?.time as Long)
        mController?.sendEvent(
            this as Object?, EventType.UPDATE_TITLE, t, t, -1, ViewType.CURRENT,
            mController?.dateFlags as Long, null, null
        )
        // Make sure the drop-down menu will get its date updated at midnight
        if (mActionBarMenuSpinnerAdapter != null) {
            mActionBarMenuSpinnerAdapter?.refresh(this)
        }
        if (mControlsMenu != null) {
            mControlsMenu?.setTitle(if (mHideControls) mShowString else mHideString)
        }
        mPaused = false
        if (mViewEventId != -1L && mIntentEventStartMillis != -1L && mIntentEventEndMillis != -1L) {
            val currentMillis: Long = System.currentTimeMillis()
            var selectedTime: Long = -1
            if (currentMillis > mIntentEventStartMillis && currentMillis < mIntentEventEndMillis) {
                selectedTime = currentMillis
            }
            mController?.sendEventRelatedEventWithExtra(
                this as Object?, EventType.VIEW_EVENT, mViewEventId,
                mIntentEventStartMillis, mIntentEventEndMillis, -1, -1,
                EventInfo.buildViewExtraLong(mIntentAttendeeResponse, mIntentAllDay),
                selectedTime
            )
            mViewEventId = -1
            mIntentEventStartMillis = -1
            mIntentEventEndMillis = -1
            mIntentAllDay = false
        }
        Utils.setMidnightUpdater(mHandler, mTimeChangesUpdater, mTimeZone)
        // Make sure the today icon is up to date
        invalidateOptionsMenu()
    }

    @Override
    protected override fun onPause() {
        super.onPause()
        mController?.deregisterEventHandler(HANDLER_KEY)
        mPaused = true
        mHomeTime?.removeCallbacks(mHomeTimeUpdater)
        if (mActionBarMenuSpinnerAdapter != null) {
            mActionBarMenuSpinnerAdapter?.onPause()
        }
        mContentResolver?.unregisterContentObserver(mObserver)
        if (isFinishing()) {
            // Stop listening for changes that would require this to be refreshed
            val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(this)
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
        }
        // FRAG_TODO save highlighted days of the week;
        if (mController?.viewType != ViewType.EDIT) {
            Utils.setDefaultView(this, mController?.viewType as Int)
        }
        Utils.resetMidnightUpdater(mHandler, mTimeChangesUpdater)
    }

    @Override
    protected override fun onUserLeaveHint() {
        mController?.sendEvent(this as Object?, EventType.USER_HOME, null, null, -1,
            ViewType.CURRENT)
        super.onUserLeaveHint()
    }

    @Override
    override fun onSaveInstanceState(outState: Bundle) {
        mOnSaveInstanceStateCalled = true
        super.onSaveInstanceState(outState)
    }

    @Override
    protected override fun onDestroy() {
        super.onDestroy()
        val prefs: SharedPreferences? = GeneralPreferences.getSharedPreferences(this)
        prefs?.unregisterOnSharedPreferenceChangeListener(this)
        mController?.deregisterAllEventHandlers()
        CalendarController.removeInstance(this)
    }

    private fun initFragments(timeMillis: Long, viewType: Int, icicle: Bundle?) {
        if (DEBUG) {
            Log.d(TAG, "Initializing to $timeMillis for view $viewType")
        }
        val ft: FragmentTransaction = getFragmentManager().beginTransaction()
        if (mShowCalendarControls) {
            val miniMonthFrag: Fragment = MonthByWeekFragment(timeMillis, true)
            ft.replace(R.id.mini_month, miniMonthFrag)
            mController?.registerEventHandler(R.id.mini_month, miniMonthFrag as EventHandler)
        }
        if (!mShowCalendarControls || viewType == ViewType.EDIT) {
            mMiniMonth?.setVisibility(View.GONE)
            mCalendarsList?.setVisibility(View.GONE)
        }
        var info: EventInfo? = null
        if (viewType == ViewType.EDIT) {
            mPreviousView = GeneralPreferences.getSharedPreferences(this)?.getInt(
                GeneralPreferences.KEY_START_VIEW, GeneralPreferences.DEFAULT_START_VIEW
            ) as Int
            var eventId: Long = -1
            val intent: Intent = getIntent()
            val data: Uri? = intent.getData()
            if (data != null) {
                try {
                    eventId = data.getLastPathSegment()?.toLong() as Long
                } catch (e: NumberFormatException) {
                    if (DEBUG) {
                        Log.d(TAG, "Create new event")
                    }
                }
            } else if (icicle != null && icicle.containsKey(BUNDLE_KEY_EVENT_ID)) {
                eventId = icicle.getLong(BUNDLE_KEY_EVENT_ID)
            }
            val begin: Long = intent.getLongExtra(EXTRA_EVENT_BEGIN_TIME, -1)
            val end: Long = intent.getLongExtra(EXTRA_EVENT_END_TIME, -1)
            info = EventInfo()
            if (end != -1L) {
                info.endTime = Time()
                info.endTime?.set(end)
            }
            if (begin != -1L) {
                info.startTime = Time()
                info.startTime?.set(begin)
            }
            info.id = eventId
            // We set the viewtype so if the user presses back when they are
            // done editing the controller knows we were in the Edit Event
            // screen. Likewise for eventId
            mController?.viewType = viewType
            mController?.eventId = eventId
        } else {
            mPreviousView = viewType
        }
        setMainPane(ft, R.id.main_pane, viewType, timeMillis, true)
        ft.commit() // this needs to be after setMainPane()
        val t = Time(mTimeZone)
        t.set(timeMillis)
        if (viewType != ViewType.EDIT) {
            mController?.sendEvent(this as Object?, EventType.GO_TO, t, null, -1, viewType)
        }
    }

    @Override
    override fun onBackPressed() {
        if (mCurrentView == ViewType.EDIT || mBackToPreviousView) {
            mController?.sendEvent(this as Object?, EventType.GO_TO, null, null, -1, mPreviousView)
        } else {
            super.onBackPressed()
        }
    }

    @Override
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        mOptionsMenu = menu
        getMenuInflater().inflate(R.menu.all_in_one_title_bar, menu)

        // Hide the "show/hide controls" button if this is a phone
        // or the view type is "Month".
        mControlsMenu = menu.findItem(R.id.action_hide_controls)
        if (!mShowCalendarControls) {
            if (mControlsMenu != null) {
                mControlsMenu?.setVisible(false)
                mControlsMenu?.setEnabled(false)
            }
        } else if (mControlsMenu != null && mController != null &&
            mController?.viewType == ViewType.MONTH) {
            mControlsMenu?.setVisible(false)
            mControlsMenu?.setEnabled(false)
        } else if (mControlsMenu != null) {
            mControlsMenu?.setTitle(if (mHideControls) mShowString else mHideString)
        }
        val menuItem: MenuItem = menu.findItem(R.id.action_today)
        if (Utils.isJellybeanOrLater()) {
            // replace the default top layer drawable of the today icon with a
            // custom drawable that shows the day of the month of today
            val icon: LayerDrawable = menuItem.getIcon() as LayerDrawable
            Utils.setTodayIcon(icon, this, mTimeZone)
        } else {
            menuItem.setIcon(R.drawable.ic_menu_today_no_date_holo_light)
        }
        return true
    }

    @Override
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        var t: Time? = null
        var viewType: Int = ViewType.CURRENT
        var extras: Long = CalendarController.EXTRA_GOTO_TIME
        val itemId: Int = item.getItemId()
        if (itemId == R.id.action_today) {
            viewType = ViewType.CURRENT
            t = Time(mTimeZone)
            t.setToNow()
            extras = extras or CalendarController.EXTRA_GOTO_TODAY
        } else if (itemId == R.id.action_hide_controls) {
            mHideControls = !mHideControls
            item.setTitle(if (mHideControls) mShowString else mHideString)
            if (!mHideControls) {
                mMiniMonth?.setVisibility(View.VISIBLE)
                mCalendarsList?.setVisibility(View.VISIBLE)
                mMiniMonthContainer?.setVisibility(View.VISIBLE)
            }
            val slideAnimation: ObjectAnimator = ObjectAnimator.ofInt(
                this, "controlsOffset",
                if (mHideControls) 0 else mControlsAnimateWidth,
                if (mHideControls) mControlsAnimateWidth else 0
            )
            slideAnimation.setDuration(mCalendarControlsAnimationTime.toLong())
            ObjectAnimator.setFrameDelay(0)
            slideAnimation.start()
            return true
        } else {
            Log.d(TAG, "Unsupported itemId: $itemId")
            return true
        }
        mController?.sendEvent(this as Object?, EventType.GO_TO, t, null, t, -1,
            viewType, extras, null, null)
        return true
    }

    /**
     * Sets the offset of the controls on the right for animating them off/on
     * screen. ProGuard strips this if it's not in proguard.flags
     *
     * @param controlsOffset The current offset in pixels
     */
    fun setControlsOffset(controlsOffset: Int) {
        if (mOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            mMiniMonth?.setTranslationX(controlsOffset.toFloat())
            mCalendarsList?.setTranslationX(controlsOffset.toFloat())
            mControlsParams?.width = Math.max(0, mControlsAnimateWidth - controlsOffset)
            mMiniMonthContainer?.setLayoutParams(mControlsParams)
        } else {
            mMiniMonth?.setTranslationY(controlsOffset.toFloat())
            mCalendarsList?.setTranslationY(controlsOffset.toFloat())
            if (mVerticalControlsParams == null) {
                mVerticalControlsParams = LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, mControlsAnimateHeight
                ) as LinearLayout.LayoutParams?
            }
            mVerticalControlsParams?.height = Math.max(0, mControlsAnimateHeight - controlsOffset)
            mMiniMonthContainer?.setLayoutParams(mVerticalControlsParams)
        }
    }

    @Override
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key.equals(GeneralPreferences.KEY_WEEK_START_DAY)) {
            if (mPaused) {
                mUpdateOnResume = true
            } else {
                initFragments(mController?.time as Long, mController?.viewType as Int, null)
            }
        }
    }

    private fun setMainPane(
        ft: FragmentTransaction?,
        viewId: Int,
        viewType: Int,
        timeMillis: Long,
        force: Boolean
    ) {
        var ft: FragmentTransaction? = ft
        if (mOnSaveInstanceStateCalled) {
            return
        }
        if (!force && mCurrentView == viewType) {
            return
        }

        // Remove this when transition to and from month view looks fine.
        val doTransition = viewType != ViewType.MONTH && mCurrentView != ViewType.MONTH
        val fragmentManager: FragmentManager = getFragmentManager()
        if (viewType != mCurrentView) {
            // The rules for this previous view are different than the
            // controller's and are used for intercepting the back button.
            if (mCurrentView != ViewType.EDIT && mCurrentView > 0) {
                mPreviousView = mCurrentView
            }
            mCurrentView = viewType
        }
        // Create new fragment
        var frag: Fragment? = null
        val secFrag: Fragment? = null
        when (viewType) {
            ViewType.AGENDA -> {
            }
            ViewType.DAY -> {
                if (mActionBar != null && mActionBar?.getSelectedTab() != mDayTab) {
                    mActionBar?.selectTab(mDayTab)
                }
                if (mActionBarMenuSpinnerAdapter != null) {
                    mActionBar?.setSelectedNavigationItem(CalendarViewAdapter.DAY_BUTTON_INDEX)
                }
                frag = DayFragment(timeMillis, 1)
            }
            ViewType.MONTH -> {
                if (mActionBar != null && mActionBar?.getSelectedTab() != mMonthTab) {
                    mActionBar?.selectTab(mMonthTab)
                }
                if (mActionBarMenuSpinnerAdapter != null) {
                    mActionBar?.setSelectedNavigationItem(CalendarViewAdapter.MONTH_BUTTON_INDEX)
                }
                frag = MonthByWeekFragment(timeMillis, false)
            }
            ViewType.WEEK -> {
                if (mActionBar != null && mActionBar?.getSelectedTab() != mWeekTab) {
                    mActionBar?.selectTab(mWeekTab)
                }
                if (mActionBarMenuSpinnerAdapter != null) {
                    mActionBar?.setSelectedNavigationItem(CalendarViewAdapter.WEEK_BUTTON_INDEX)
                }
                frag = DayFragment(timeMillis, 7)
            }
            else -> {
                if (mActionBar != null && mActionBar?.getSelectedTab() != mWeekTab) {
                    mActionBar?.selectTab(mWeekTab)
                }
                if (mActionBarMenuSpinnerAdapter != null) {
                    mActionBar?.setSelectedNavigationItem(CalendarViewAdapter.WEEK_BUTTON_INDEX)
                }
                frag = DayFragment(timeMillis, 7)
            }
        }

        // Update the current view so that the menu can update its look according to the
        // current view.
        if (mActionBarMenuSpinnerAdapter != null) {
            mActionBarMenuSpinnerAdapter?.setMainView(viewType)
            if (!mIsTabletConfig) {
                mActionBarMenuSpinnerAdapter?.setTime(timeMillis)
            }
        }

        // Show date only on tablet configurations in views different than Agenda
        if (!mIsTabletConfig) {
            mDateRange?.setVisibility(View.GONE)
        } else {
            mDateRange?.setVisibility(View.GONE)
        }

        // Clear unnecessary buttons from the option menu when switching from the agenda view
        if (viewType != ViewType.AGENDA) {
            clearOptionsMenu()
        }
        var doCommit = false
        if (ft == null) {
            doCommit = true
            ft = fragmentManager.beginTransaction()
        }
        if (doTransition) {
            ft?.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
        }
        ft?.replace(viewId, frag)
        if (DEBUG) {
            Log.d(TAG, "Adding handler with viewId $viewId and type $viewType")
        }
        // If the key is already registered this will replace it
        mController?.registerEventHandler(viewId, frag as EventHandler?)
        if (doCommit) {
            if (DEBUG) {
                Log.d(TAG, "setMainPane AllInOne=" + this + " finishing:" + this.isFinishing())
            }
            ft?.commit()
        }
    }

    private fun setTitleInActionBar(event: EventInfo) {
        if (event.eventType != EventType.UPDATE_TITLE || mActionBar == null) {
            return
        }
        val start: Long? = event.startTime?.toMillis(false /* use isDst */)
        val end: Long?
        end = if (event.endTime != null) {
            event.endTime?.toMillis(false /* use isDst */)
        } else {
            start
        }
        val msg: String? = Utils.formatDateRange(this,
            start as Long,
            end as Long,
            event.extraLong.toInt()
        )
        val oldDate: CharSequence? = mDateRange?.getText()
        mDateRange?.setText(msg)
        updateSecondaryTitleFields(if (event.selectedTime != null)
            event.selectedTime?.toMillis(true) as Long else start)
        if (!TextUtils.equals(oldDate, msg)) {
            mDateRange?.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
            if (mShowWeekNum && mWeekTextView != null) {
                mWeekTextView?.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
            }
        }
    }

    private fun updateSecondaryTitleFields(visibleMillisSinceEpoch: Long) {
        mShowWeekNum = Utils.getShowWeekNumber(this)
        mTimeZone = Utils.getTimeZone(this, mHomeTimeUpdater)
        if (visibleMillisSinceEpoch != -1L) {
            val weekNum: Int = Utils.getWeekNumberFromTime(visibleMillisSinceEpoch, this)
            mWeekNum = weekNum
        }
        if (mShowWeekNum && mCurrentView == ViewType.WEEK && mIsTabletConfig &&
            mWeekTextView != null
        ) {
            val weekString: String = getResources().getQuantityString(
                R.plurals.weekN, mWeekNum,
                mWeekNum
            )
            mWeekTextView?.setText(weekString)
            mWeekTextView?.setVisibility(View.VISIBLE)
        } else if (visibleMillisSinceEpoch != -1L && mWeekTextView != null &&
            mCurrentView == ViewType.DAY && mIsTabletConfig) {
            val time = Time(mTimeZone)
            time.set(visibleMillisSinceEpoch)
            val julianDay: Int = Time.getJulianDay(visibleMillisSinceEpoch, time.gmtoff)
            time.setToNow()
            val todayJulianDay: Int = Time.getJulianDay(time.toMillis(false), time.gmtoff)
            val dayString: String = Utils.getDayOfWeekString(
                julianDay,
                todayJulianDay,
                visibleMillisSinceEpoch,
                this
            )
            mWeekTextView?.setText(dayString)
            mWeekTextView?.setVisibility(View.VISIBLE)
        } else if (mWeekTextView != null && (!mIsTabletConfig || mCurrentView != ViewType.DAY)) {
            mWeekTextView?.setVisibility(View.GONE)
        }
        if (mHomeTime != null && (mCurrentView == ViewType.DAY || mCurrentView == ViewType.WEEK) &&
            !TextUtils.equals(mTimeZone, Time.getCurrentTimezone())
        ) {
            val time = Time(mTimeZone)
            time.setToNow()
            val millis: Long = time.toMillis(true)
            val isDST = time.isDst !== 0
            var flags: Int = DateUtils.FORMAT_SHOW_TIME
            if (DateFormat.is24HourFormat(this)) {
                flags = flags or DateUtils.FORMAT_24HOUR
            }
            // Formats the time as
            val timeString: String = StringBuilder(
                Utils.formatDateRange(this, millis, millis, flags)
            ).append(" ").append(
                TimeZone.getTimeZone(mTimeZone).getDisplayName(
                    isDST, TimeZone.SHORT, Locale.getDefault()
                )
            ).toString()
            mHomeTime?.setText(timeString)
            mHomeTime?.setVisibility(View.VISIBLE)
            // Update when the minute changes
            mHomeTime?.removeCallbacks(mHomeTimeUpdater)
            mHomeTime?.postDelayed(
                mHomeTimeUpdater,
                DateUtils.MINUTE_IN_MILLIS - millis % DateUtils.MINUTE_IN_MILLIS
            )
        } else if (mHomeTime != null) {
            mHomeTime?.setVisibility(View.GONE)
        }
    }

    @get:Override override val supportedEventTypes: Long
        get() = EventType.GO_TO or EventType.UPDATE_TITLE

    @Override
    override fun handleEvent(event: EventInfo?) {
        var displayTime: Long = -1
        if (event?.eventType == EventType.GO_TO) {
            if (event.extraLong and CalendarController.EXTRA_GOTO_BACK_TO_PREVIOUS != 0L) {
                mBackToPreviousView = true
            } else if (event.viewType != mController?.previousViewType &&
                event.viewType != ViewType.EDIT
            ) {
                // Clear the flag is change to a different view type
                mBackToPreviousView = false
            }
            setMainPane(
                null, R.id.main_pane, event.viewType, event.startTime?.toMillis(false)
                    as Long, false
            )
            if (mShowCalendarControls) {
                val animationSize =
                    if (mOrientation == Configuration.ORIENTATION_LANDSCAPE) mControlsAnimateWidth
                    else mControlsAnimateHeight
                val noControlsView = event.viewType == ViewType.MONTH
                if (mControlsMenu != null) {
                    mControlsMenu?.setVisible(!noControlsView)
                    mControlsMenu?.setEnabled(!noControlsView)
                }
                if (noControlsView || mHideControls) {
                    // hide minimonth and calendar frag
                    mShowSideViews = false
                    if (!mHideControls) {
                        val slideAnimation: ObjectAnimator = ObjectAnimator.ofInt(
                            this,
                            "controlsOffset", 0, animationSize
                        )
                        slideAnimation.addListener(mSlideAnimationDoneListener)
                        slideAnimation.setDuration(mCalendarControlsAnimationTime.toLong())
                        ObjectAnimator.setFrameDelay(0)
                        slideAnimation.start()
                    } else {
                        mMiniMonth?.setVisibility(View.GONE)
                        mCalendarsList?.setVisibility(View.GONE)
                        mMiniMonthContainer?.setVisibility(View.GONE)
                    }
                } else {
                    // show minimonth and calendar frag
                    mShowSideViews = true
                    mMiniMonth?.setVisibility(View.VISIBLE)
                    mCalendarsList?.setVisibility(View.VISIBLE)
                    mMiniMonthContainer?.setVisibility(View.VISIBLE)
                    if (!mHideControls &&
                        mController?.previousViewType == ViewType.MONTH
                    ) {
                        val slideAnimation: ObjectAnimator = ObjectAnimator.ofInt(
                            this,
                            "controlsOffset", animationSize, 0
                        )
                        slideAnimation.setDuration(mCalendarControlsAnimationTime.toLong())
                        ObjectAnimator.setFrameDelay(0)
                        slideAnimation.start()
                    }
                }
            }
            displayTime =
                if (event.selectedTime != null) event.selectedTime?.toMillis(true) as Long
                else event.startTime?.toMillis(true) as Long
            if (!mIsTabletConfig) {
                mActionBarMenuSpinnerAdapter?.setTime(displayTime)
            }
        } else if (event?.eventType == EventType.UPDATE_TITLE) {
            setTitleInActionBar(event as CalendarController.EventInfo)
            if (!mIsTabletConfig) {
                mActionBarMenuSpinnerAdapter?.setTime(mController?.time as Long)
            }
        }
        updateSecondaryTitleFields(displayTime)
    }

    @Override
    override fun eventsChanged() {
        mController?.sendEvent(this as Object?, EventType.EVENTS_CHANGED, null, null, -1,
            ViewType.CURRENT)
    }

    @Override
    override fun onTabSelected(tab: Tab?, ft: FragmentTransaction?) {
        Log.w(TAG, "TabSelected AllInOne=" + this + " finishing:" + this.isFinishing())
        if (tab == mDayTab && mCurrentView != ViewType.DAY) {
            mController?.sendEvent(this as Object?, EventType.GO_TO, null, null, -1, ViewType.DAY)
        } else if (tab == mWeekTab && mCurrentView != ViewType.WEEK) {
            mController?.sendEvent(this as Object?, EventType.GO_TO, null, null, -1, ViewType.WEEK)
        } else if (tab == mMonthTab && mCurrentView != ViewType.MONTH) {
            mController?.sendEvent(this as Object?, EventType.GO_TO, null, null, -1, ViewType.MONTH)
        } else {
            Log.w(
                TAG, "TabSelected event from unknown tab: " +
                    if (tab == null) "null" else tab.getText()
            )
            Log.w(
                TAG, "CurrentView:" + mCurrentView + " Tab:" + tab.toString() + " Day:" + mDayTab +
                    " Week:" + mWeekTab + " Month:" + mMonthTab
            )
        }
    }

    @Override
    override fun onTabReselected(tab: Tab?, ft: FragmentTransaction?) {
    }

    @Override
    override fun onTabUnselected(tab: Tab?, ft: FragmentTransaction?) {
    }

    @Override
    override fun onNavigationItemSelected(itemPosition: Int, itemId: Long): Boolean {
        when (itemPosition) {
            CalendarViewAdapter.DAY_BUTTON_INDEX -> if (mCurrentView != ViewType.DAY) {
                mController?.sendEvent(this as Object?, EventType.GO_TO, null, null, -1,
                    ViewType.DAY)
            }
            CalendarViewAdapter.WEEK_BUTTON_INDEX -> if (mCurrentView != ViewType.WEEK) {
                mController?.sendEvent(this as Object?, EventType.GO_TO, null, null, -1,
                    ViewType.WEEK)
            }
            CalendarViewAdapter.MONTH_BUTTON_INDEX -> if (mCurrentView != ViewType.MONTH) {
                mController?.sendEvent(this as Object?, EventType.GO_TO, null, null, -1,
                    ViewType.MONTH)
            }
            CalendarViewAdapter.AGENDA_BUTTON_INDEX -> {
            }
            else -> {
                Log.w(TAG, "ItemSelected event from unknown button: $itemPosition")
                Log.w(
                    TAG, "CurrentView:" + mCurrentView + " Button:" + itemPosition +
                        " Day:" + mDayTab + " Week:" + mWeekTab + " Month:" + mMonthTab
                )
            }
        }
        return false
    }

    companion object {
        private const val TAG = "AllInOneActivity"
        private const val DEBUG = false
        private const val EVENT_INFO_FRAGMENT_TAG = "EventInfoFragment"
        private const val BUNDLE_KEY_RESTORE_TIME = "key_restore_time"
        private const val BUNDLE_KEY_EVENT_ID = "key_event_id"
        private const val BUNDLE_KEY_RESTORE_VIEW = "key_restore_view"
        private const val BUNDLE_KEY_CHECK_ACCOUNTS = "key_check_for_accounts"
        private const val HANDLER_KEY = 0

        // Indices of buttons for the drop down menu (tabs replacement)
        // Must match the strings in the array buttons_list in arrays.xml and the
        // OnNavigationListener
        private const val BUTTON_DAY_INDEX = 0
        private const val BUTTON_WEEK_INDEX = 1
        private const val BUTTON_MONTH_INDEX = 2
        private const val BUTTON_AGENDA_INDEX = 3
        private var mIsMultipane = false
        private var mIsTabletConfig = false
    }
}
