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
package com.android.calendar.alerts

import android.app.ListActivity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import com.android.calendar.R
import com.android.calendar.Utils
import java.util.Arrays

/**
 * Activity which displays when the user wants to email guests from notifications.
 *
 * This presents the user with list if quick responses to be populated in an email
 * to minimize typing.
 *
 */
class QuickResponseActivity : ListActivity(), OnItemClickListener {
    private var mResponses: Array<String?>? = null
    @Override
    protected override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        val intent: Intent? = getIntent()
        if (intent == null) {
            finish()
            return
        }
        mEventId = intent.getLongExtra(EXTRA_EVENT_ID, -1) as Long
        if (mEventId == -1L) {
            finish()
            return
        }

        // Set listener
        getListView().setOnItemClickListener(this@QuickResponseActivity)

        // Populate responses
        val responses: Array<String> = Utils.getQuickResponses(this)
        Arrays.sort(responses)

        // Add "Custom response..."
        mResponses = arrayOfNulls(responses.size + 1)
        var i: Int
        i = 0
        while (i < responses.size) {
            mResponses!![i] = responses[i]
            i++
        }
        mResponses!![i] = getResources().getString(R.string.quick_response_custom_msg)
        setListAdapter(ArrayAdapter<String>(this, R.layout.quick_response_item,
                                                mResponses as Array<String?>))
    }

    // implements OnItemClickListener
    @Override
    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        var body: String? = null
        if (mResponses != null && position < mResponses!!.size - 1) {
            body = mResponses!![position]
        }

        // Start thread to query provider and send mail
        QueryThread(mEventId, body).start()
    }

    private inner class QueryThread internal constructor(var mEventId: Long, var mBody: String?) :
        Thread() {
        @Override
        override fun run() {
        }
    }

    companion object {
        private const val TAG = "QuickResponseActivity"
        const val EXTRA_EVENT_ID = "eventId"
        var mEventId: Long = 0
    }
}