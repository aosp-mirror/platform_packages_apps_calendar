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
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.preference.PreferenceScreen
import android.text.format.DateFormat
import android.text.format.Time
import android.util.Log
import android.widget.TimePicker

class OtherPreferences : PreferenceFragment(), OnPreferenceChangeListener {
    private var mCopyDb: Preference? = null
    private var mQuietHours: CheckBoxPreference? = null
    private var mQuietHoursStart: Preference? = null
    private var mQuietHoursEnd: Preference? = null
    private var mTimePickerDialog: TimePickerDialog? = null
    private var mQuietHoursStartListener: TimeSetListener? = null
    private var mQuietHoursStartDialog: TimePickerDialog? = null
    private var mQuietHoursEndListener: TimeSetListener? = null
    private var mQuietHoursEndDialog: TimePickerDialog? = null
    private var mIs24HourMode = false

    @Override
    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        val manager: PreferenceManager = getPreferenceManager()
        manager.setSharedPreferencesName(SHARED_PREFS_NAME)
        val prefs: SharedPreferences = manager.getSharedPreferences()
        addPreferencesFromResource(R.xml.other_preferences)
        mCopyDb = findPreference(KEY_OTHER_COPY_DB)
        val activity: Activity = getActivity()
        if (activity == null) {
            Log.d(TAG, "Activity was null")
        }
        mIs24HourMode = DateFormat.is24HourFormat(activity)
        mQuietHours = findPreference(KEY_OTHER_QUIET_HOURS) as CheckBoxPreference?
        val startHour: Int = prefs.getInt(KEY_OTHER_QUIET_HOURS_START_HOUR,
                QUIET_HOURS_DEFAULT_START_HOUR)
        val startMinute: Int = prefs.getInt(KEY_OTHER_QUIET_HOURS_START_MINUTE,
                QUIET_HOURS_DEFAULT_START_MINUTE)
        mQuietHoursStart = findPreference(KEY_OTHER_QUIET_HOURS_START)
        mQuietHoursStartListener = TimeSetListener(START_LISTENER)
        mQuietHoursStartDialog = TimePickerDialog(
                activity, mQuietHoursStartListener,
                startHour, startMinute, mIs24HourMode)
        mQuietHoursStart?.setSummary(formatTime(startHour, startMinute))
        val endHour: Int = prefs.getInt(KEY_OTHER_QUIET_HOURS_END_HOUR,
                QUIET_HOURS_DEFAULT_END_HOUR)
        val endMinute: Int = prefs.getInt(KEY_OTHER_QUIET_HOURS_END_MINUTE,
                QUIET_HOURS_DEFAULT_END_MINUTE)
        mQuietHoursEnd = findPreference(KEY_OTHER_QUIET_HOURS_END)
        mQuietHoursEndListener = TimeSetListener(END_LISTENER)
        mQuietHoursEndDialog = TimePickerDialog(
                activity, mQuietHoursEndListener,
                endHour, endMinute, mIs24HourMode)
        mQuietHoursEnd?.setSummary(formatTime(endHour, endMinute))
    }

    @Override
    override fun onPreferenceChange(preference: Preference?, objValue: Any?): Boolean {
        return true
    }

    @Override
    override fun onPreferenceTreeClick(screen: PreferenceScreen?, preference: Preference): Boolean {
        if (preference === mCopyDb) {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.setComponent(ComponentName("com.android.providers.calendar",
                    "com.android.providers.calendar.CalendarDebugActivity"))
            startActivity(intent)
        } else if (preference === mQuietHoursStart) {
            if (mTimePickerDialog == null) {
                mTimePickerDialog = mQuietHoursStartDialog
                mTimePickerDialog?.show()
            } else {
                Log.v(TAG, "not null")
            }
        } else if (preference === mQuietHoursEnd) {
            if (mTimePickerDialog == null) {
                mTimePickerDialog = mQuietHoursEndDialog
                mTimePickerDialog?.show()
            } else {
                Log.v(TAG, "not null")
            }
        } else {
            return super.onPreferenceTreeClick(screen, preference)
        }
        return true
    }

    private inner class TimeSetListener(private val mListenerId: Int) :
            TimePickerDialog.OnTimeSetListener {
        @Override
        override fun onTimeSet(view: TimePicker?, hourOfDay: Int, minute: Int) {
            mTimePickerDialog = null
            val prefs: SharedPreferences = getPreferenceManager().getSharedPreferences()
            val editor: SharedPreferences.Editor = prefs.edit()
            val summary = formatTime(hourOfDay, minute)
            when (mListenerId) {
                START_LISTENER -> {
                    mQuietHoursStart?.setSummary(summary)
                    editor.putInt(KEY_OTHER_QUIET_HOURS_START_HOUR, hourOfDay)
                    editor.putInt(KEY_OTHER_QUIET_HOURS_START_MINUTE, minute)
                }
                END_LISTENER -> {
                    mQuietHoursEnd?.setSummary(summary)
                    editor.putInt(KEY_OTHER_QUIET_HOURS_END_HOUR, hourOfDay)
                    editor.putInt(KEY_OTHER_QUIET_HOURS_END_MINUTE, minute)
                }
                else -> Log.d(TAG, "Set time for unknown listener: $mListenerId")
            }
            editor.commit()
        }
    }

    /**
     * @param hourOfDay the hour of the day (0-24)
     * @param minute
     * @return human-readable string formatted based on 24-hour mode.
     */
    private fun formatTime(hourOfDay: Int, minute: Int): String {
        val time = Time()
        time.hour = hourOfDay
        time.minute = minute
        val format = if (mIs24HourMode) format24Hour else format12Hour
        return time.format(format)
    }

    companion object {
        private const val TAG = "CalendarOtherPreferences"

        // The name of the shared preferences file. This name must be maintained for
        // historical reasons, as it's what PreferenceManager assigned the first
        // time the file was created.
        const val SHARED_PREFS_NAME = "com.android.calendar_preferences"

        // Must be the same keys that are used in the other_preferences.xml file.
        const val KEY_OTHER_COPY_DB = "preferences_copy_db"
        const val KEY_OTHER_QUIET_HOURS = "preferences_reminders_quiet_hours"
        const val KEY_OTHER_REMINDERS_RESPONDED = "preferences_reminders_responded"
        const val KEY_OTHER_QUIET_HOURS_START = "preferences_reminders_quiet_hours_start"
        const val KEY_OTHER_QUIET_HOURS_START_HOUR = "preferences_reminders_quiet_hours_start_hour"
        const val KEY_OTHER_QUIET_HOURS_START_MINUTE =
                "preferences_reminders_quiet_hours_start_minute"
        const val KEY_OTHER_QUIET_HOURS_END = "preferences_reminders_quiet_hours_end"
        const val KEY_OTHER_QUIET_HOURS_END_HOUR = "preferences_reminders_quiet_hours_end_hour"
        const val KEY_OTHER_QUIET_HOURS_END_MINUTE = "preferences_reminders_quiet_hours_end_minute"
        const val KEY_OTHER_1 = "preferences_tardis_1"
        const val QUIET_HOURS_DEFAULT_START_HOUR = 22
        const val QUIET_HOURS_DEFAULT_START_MINUTE = 0
        const val QUIET_HOURS_DEFAULT_END_HOUR = 8
        const val QUIET_HOURS_DEFAULT_END_MINUTE = 0
        private const val START_LISTENER = 1
        private const val END_LISTENER = 2
        private const val format24Hour = "%H:%M"
        private const val format12Hour = "%I:%M%P"
    }
}