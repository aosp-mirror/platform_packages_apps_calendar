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

import android.app.Activity
import android.app.FragmentManager
import android.app.backup.BackupManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.preference.CheckBoxPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.Preference.OnPreferenceClickListener
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.preference.PreferenceScreen
import android.provider.CalendarContract
import android.provider.CalendarContract.CalendarCache
import android.text.TextUtils
import android.text.format.Time
import com.android.calendar.alerts.AlertReceiver
import com.android.timezonepicker.TimeZoneInfo
import com.android.timezonepicker.TimeZonePickerDialog
import com.android.timezonepicker.TimeZonePickerDialog.OnTimeZoneSetListener
import com.android.timezonepicker.TimeZonePickerUtils

class GeneralPreferences : PreferenceFragment(), OnSharedPreferenceChangeListener,
        OnPreferenceChangeListener, OnTimeZoneSetListener {
    var mAlert: CheckBoxPreference? = null
    var mVibrate: CheckBoxPreference? = null
    var mPopup: CheckBoxPreference? = null
    var mUseHomeTZ: CheckBoxPreference? = null
    var mHideDeclined: CheckBoxPreference? = null
    var mHomeTZ: Preference? = null
    var mTzPickerUtils: TimeZonePickerUtils? = null
    var mWeekStart: ListPreference? = null
    var mDefaultReminder: ListPreference? = null
    private var mTimeZoneId: String? = null

    @Override
    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        val activity: Activity = getActivity()

        // Make sure to always use the same preferences file regardless of the package name
        // we're running under
        val preferenceManager: PreferenceManager = getPreferenceManager()
        val sharedPreferences: SharedPreferences? = getSharedPreferences(activity)
        preferenceManager.setSharedPreferencesName(SHARED_PREFS_NAME)

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.general_preferences)
        val preferenceScreen: PreferenceScreen = getPreferenceScreen()
        mAlert = preferenceScreen.findPreference(KEY_ALERTS) as CheckBoxPreference
        mVibrate = preferenceScreen.findPreference(KEY_ALERTS_VIBRATE) as CheckBoxPreference
        val vibrator: Vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator == null || !vibrator.hasVibrator()) {
            val mAlertGroup: PreferenceCategory = preferenceScreen
                    .findPreference(KEY_ALERTS_CATEGORY) as PreferenceCategory
            mAlertGroup.removePreference(mVibrate)
        }
        mPopup = preferenceScreen.findPreference(KEY_ALERTS_POPUP) as CheckBoxPreference
        mUseHomeTZ = preferenceScreen.findPreference(KEY_HOME_TZ_ENABLED) as CheckBoxPreference
        mHideDeclined = preferenceScreen.findPreference(KEY_HIDE_DECLINED) as CheckBoxPreference
        mWeekStart = preferenceScreen.findPreference(KEY_WEEK_START_DAY) as ListPreference
        mDefaultReminder = preferenceScreen.findPreference(KEY_DEFAULT_REMINDER) as ListPreference
        mHomeTZ = preferenceScreen.findPreference(KEY_HOME_TZ)
        mWeekStart?.setSummary(mWeekStart?.getEntry())
        mDefaultReminder?.setSummary(mDefaultReminder?.getEntry())

        // This triggers an asynchronous call to the provider to refresh the data in shared pref
        mTimeZoneId = Utils.getTimeZone(activity, null)
        val prefs: SharedPreferences = CalendarUtils.getSharedPreferences(activity,
                Utils.SHARED_PREFS_NAME)

        // Utils.getTimeZone will return the currentTimeZone instead of the one
        // in the shared_pref if home time zone is disabled. So if home tz is
        // off, we will explicitly read it.
        if (!prefs.getBoolean(KEY_HOME_TZ_ENABLED, false)) {
            mTimeZoneId = prefs.getString(KEY_HOME_TZ, Time.getCurrentTimezone())
        }

        mHomeTZ?.setOnPreferenceClickListener(object : Preference.OnPreferenceClickListener {
            @Override
            override fun onPreferenceClick(preference: Preference?): Boolean {
                showTimezoneDialog()
                return true
            }
        })

        if (mTzPickerUtils == null) {
            mTzPickerUtils = TimeZonePickerUtils(getActivity())
        }
        val timezoneName: CharSequence? = mTzPickerUtils?.getGmtDisplayName(getActivity(),
                mTimeZoneId, System.currentTimeMillis(), false)
        mHomeTZ?.setSummary(timezoneName ?: mTimeZoneId)
        val tzpd: TimeZonePickerDialog = activity.getFragmentManager()
                .findFragmentByTag(FRAG_TAG_TIME_ZONE_PICKER) as TimeZonePickerDialog
        if (tzpd != null) {
            tzpd.setOnTimeZoneSetListener(this)
        }
        migrateOldPreferences(sharedPreferences)
        updateChildPreferences()
    }

    private fun showTimezoneDialog() {
        val activity: Activity = getActivity() ?: return
        val b = Bundle()
        b.putLong(TimeZonePickerDialog.BUNDLE_START_TIME_MILLIS, System.currentTimeMillis())
        b.putString(TimeZonePickerDialog.BUNDLE_TIME_ZONE, Utils.getTimeZone(activity, null))
        val fm: FragmentManager = getActivity().getFragmentManager()
        var tzpd: TimeZonePickerDialog? = fm
                .findFragmentByTag(FRAG_TAG_TIME_ZONE_PICKER) as TimeZonePickerDialog
        if (tzpd != null) {
            tzpd.dismiss()
        }
        tzpd = TimeZonePickerDialog()
        tzpd.setArguments(b)
        tzpd.setOnTimeZoneSetListener(this)
        tzpd.show(fm, FRAG_TAG_TIME_ZONE_PICKER)
    }

    @Override
    override fun onStart() {
        super.onStart()
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this)
        setPreferenceListeners(this)
    }

    /**
     * Sets up all the preference change listeners to use the specified
     * listener.
     */
    private fun setPreferenceListeners(listener: OnPreferenceChangeListener?) {
        mUseHomeTZ?.setOnPreferenceChangeListener(listener)
        mHomeTZ?.setOnPreferenceChangeListener(listener)
        mWeekStart?.setOnPreferenceChangeListener(listener)
        mDefaultReminder?.setOnPreferenceChangeListener(listener)
        mHideDeclined?.setOnPreferenceChangeListener(listener)
        mVibrate?.setOnPreferenceChangeListener(listener)
    }

    @Override
    override fun onStop() {
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this)
        setPreferenceListeners(null)
        super.onStop()
    }

    @Override
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        val a: Activity = getActivity()
        if (key.equals(KEY_ALERTS)) {
            updateChildPreferences()
            if (a != null) {
                val intent = Intent()
                intent.setClass(a, AlertReceiver::class.java)
                if (mAlert?.isChecked() ?: false) {
                    intent.setAction(AlertReceiver.ACTION_DISMISS_OLD_REMINDERS)
                } else {
                    intent.setAction(AlertReceiver.EVENT_REMINDER_APP_ACTION)
                }
                a.sendBroadcast(intent)
            }
        }
        if (a != null) {
            BackupManager.dataChanged(a.getPackageName())
        }
    }

    /**
     * Handles time zone preference changes
     */
    @Override
    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        val tz: String?
        val activity: Activity = getActivity()
        if (preference === mUseHomeTZ) {
            tz = if (newValue != null) {
                mTimeZoneId
            } else {
                CalendarCache.TIMEZONE_TYPE_AUTO
            }
            Utils.setTimeZone(activity, tz)
            return true
        } else if (preference === mHideDeclined) {
            mHideDeclined?.setChecked(newValue as Boolean)
            val intent = Intent(Utils.getWidgetScheduledUpdateAction(activity))
            intent.setDataAndType(CalendarContract.CONTENT_URI, Utils.APPWIDGET_DATA_TYPE)
            activity.sendBroadcast(intent)
            return true
        } else if (preference === mWeekStart) {
            mWeekStart?.setValue(newValue as String)
            mWeekStart?.setSummary(mWeekStart?.getEntry())
        } else if (preference === mDefaultReminder) {
            mDefaultReminder?.setValue(newValue as String)
            mDefaultReminder?.setSummary(mDefaultReminder?.getEntry())
        } else if (preference === mVibrate) {
            mVibrate?.setChecked(newValue as Boolean)
            return true
        } else {
            return true
        }
        return false
    }

    fun getRingtoneTitleFromUri(context: Context?, uri: String?): String? {
        if (TextUtils.isEmpty(uri)) {
            return null
        }
        val ring: Ringtone = RingtoneManager.getRingtone(getActivity(), Uri.parse(uri))
        return if (ring != null) {
            ring.getTitle(context)
        } else null
    }

    /**
     * If necessary, upgrades previous versions of preferences to the current
     * set of keys and values.
     * @param prefs the preferences to upgrade
     */
    private fun migrateOldPreferences(prefs: SharedPreferences?) {
        // If needed, migrate vibration setting from a previous version
        mVibrate?.setChecked(Utils.getDefaultVibrate(getActivity(), prefs))

        // If needed, migrate the old alerts type settin
        if (prefs?.contains(KEY_ALERTS) == false && prefs.contains(KEY_ALERTS_TYPE) == true) {
            val type: String? = prefs.getString(KEY_ALERTS_TYPE, ALERT_TYPE_STATUS_BAR)
            if (type.equals(ALERT_TYPE_OFF)) {
                mAlert?.setChecked(false)
                mPopup?.setChecked(false)
                mPopup?.setEnabled(false)
            } else if (type.equals(ALERT_TYPE_STATUS_BAR)) {
                mAlert?.setChecked(true)
                mPopup?.setChecked(false)
                mPopup?.setEnabled(true)
            } else if (type.equals(ALERT_TYPE_ALERTS)) {
                mAlert?.setChecked(true)
                mPopup?.setChecked(true)
                mPopup?.setEnabled(true)
            }
            // clear out the old setting
            prefs.edit().remove(KEY_ALERTS_TYPE).commit()
        }
    }

    /**
     * Keeps the dependent settings in sync with the parent preference, so for
     * example, when notifications are turned off, we disable the preferences
     * for configuring the exact notification behavior.
     */
    private fun updateChildPreferences() {
        if (mAlert?.isChecked() ?: false) {
            mVibrate?.setEnabled(true)
            mPopup?.setEnabled(true)
        } else {
            mVibrate?.setEnabled(false)
            mPopup?.setEnabled(false)
        }
    }

    @Override
    override fun onPreferenceTreeClick(
        preferenceScreen: PreferenceScreen?,
        preference: Preference
    ): Boolean {
        val key: String = preference.getKey()
        return super.onPreferenceTreeClick(preferenceScreen, preference)
    }

    @Override
    override fun onTimeZoneSet(tzi: TimeZoneInfo) {
        if (mTzPickerUtils == null) {
            mTzPickerUtils = TimeZonePickerUtils(getActivity())
        }
        val timezoneName: CharSequence? = mTzPickerUtils?.getGmtDisplayName(
                getActivity(), tzi.mTzId, System.currentTimeMillis(), false)
        mHomeTZ?.setSummary(timezoneName)
        Utils.setTimeZone(getActivity(), tzi.mTzId)
    }

    companion object {
        // The name of the shared preferences file. This name must be maintained for historical
        // reasons, as it's what PreferenceManager assigned the first time the file was created.
        const val SHARED_PREFS_NAME = "com.android.calendar_preferences"
        const val SHARED_PREFS_NAME_NO_BACKUP = "com.android.calendar_preferences_no_backup"
        private const val FRAG_TAG_TIME_ZONE_PICKER = "TimeZonePicker"

        // Preference keys
        const val KEY_HIDE_DECLINED = "preferences_hide_declined"
        const val KEY_WEEK_START_DAY = "preferences_week_start_day"
        const val KEY_SHOW_WEEK_NUM = "preferences_show_week_num"
        const val KEY_DAYS_PER_WEEK = "preferences_days_per_week"
        const val KEY_SKIP_SETUP = "preferences_skip_setup"
        const val KEY_CLEAR_SEARCH_HISTORY = "preferences_clear_search_history"
        const val KEY_ALERTS_CATEGORY = "preferences_alerts_category"
        const val KEY_ALERTS = "preferences_alerts"
        const val KEY_ALERTS_VIBRATE = "preferences_alerts_vibrate"
        const val KEY_ALERTS_RINGTONE = "preferences_alerts_ringtone"
        const val KEY_ALERTS_POPUP = "preferences_alerts_popup"
        const val KEY_SHOW_CONTROLS = "preferences_show_controls"
        const val KEY_DEFAULT_REMINDER = "preferences_default_reminder"
        const val NO_REMINDER = -1
        const val NO_REMINDER_STRING = "-1"
        const val REMINDER_DEFAULT_TIME = 10 // in minutes
        const val KEY_DEFAULT_CELL_HEIGHT = "preferences_default_cell_height"
        const val KEY_VERSION = "preferences_version"

        /** Key to SharePreference for default view (CalendarController.ViewType)  */
        const val KEY_START_VIEW = "preferred_startView"

        /**
         * Key to SharePreference for default detail view (CalendarController.ViewType)
         * Typically used by widget
         */
        const val KEY_DETAILED_VIEW = "preferred_detailedView"
        const val KEY_DEFAULT_CALENDAR = "preference_defaultCalendar"

        // These must be in sync with the array preferences_week_start_day_values
        const val WEEK_START_DEFAULT = "-1"
        const val WEEK_START_SATURDAY = "7"
        const val WEEK_START_SUNDAY = "1"
        const val WEEK_START_MONDAY = "2"

        // These keys are kept to enable migrating users from previous versions
        private const val KEY_ALERTS_TYPE = "preferences_alerts_type"
        private const val ALERT_TYPE_ALERTS = "0"
        private const val ALERT_TYPE_STATUS_BAR = "1"
        private const val ALERT_TYPE_OFF = "2"
        const val KEY_HOME_TZ_ENABLED = "preferences_home_tz_enabled"
        const val KEY_HOME_TZ = "preferences_home_tz"

        // Default preference values
        const val DEFAULT_START_VIEW: Int = CalendarController.ViewType.WEEK
        const val DEFAULT_DETAILED_VIEW: Int = CalendarController.ViewType.DAY
        const val DEFAULT_SHOW_WEEK_NUM = false

        // This should match the XML file.
        const val DEFAULT_RINGTONE = "content://settings/system/notification_sound"

        /** Return a properly configured SharedPreferences instance  */
        @JvmStatic
        fun getSharedPreferences(context: Context?): SharedPreferences? {
            return context?.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        }

        /** Set the default shared preferences in the proper context  */
        @JvmStatic
        fun setDefaultValues(context: Context?) {
            PreferenceManager.setDefaultValues(context, SHARED_PREFS_NAME, Context.MODE_PRIVATE,
                    R.xml.general_preferences, false)
        }
    }
}