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

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Handler
import android.os.Process
import android.provider.CalendarContract
import android.provider.CalendarContract.EventDays
import android.util.Log
import java.util.ArrayList
import java.util.Arrays
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class EventLoader(context: Context) {
    private val mContext: Context
    private val mHandler: Handler = Handler()
    private val mSequenceNumber: AtomicInteger? = AtomicInteger()
    private val mLoaderQueue: LinkedBlockingQueue<LoadRequest>
    private var mLoaderThread: LoaderThread? = null
    private val mResolver: ContentResolver

    private interface LoadRequest {
        fun processRequest(eventLoader: EventLoader?)
        fun skipRequest(eventLoader: EventLoader?)
    }

    private class ShutdownRequest : LoadRequest {
        override fun processRequest(eventLoader: EventLoader?) {}
        override fun skipRequest(eventLoader: EventLoader?) {}
    }

    /**
     *
     * Code for handling requests to get whether days have an event or not
     * and filling in the eventDays array.
     *
     */
    private class LoadEventDaysRequest(
        var startDay: Int,
        var numDays: Int,
        var eventDays: BooleanArray,
        uiCallback: Runnable
    ) : LoadRequest {
        var uiCallback: Runnable
        @Override
        override fun processRequest(eventLoader: EventLoader?) {
            val handler: Handler? = eventLoader?.mHandler
            val cr: ContentResolver? = eventLoader?.mResolver

            // Clear the event days
            Arrays.fill(eventDays, false)

            // query which days have events
            val cursor: Cursor = EventDays.query(cr, startDay, numDays, PROJECTION)
            try {
                val startDayColumnIndex: Int = cursor.getColumnIndexOrThrow(EventDays.STARTDAY)
                val endDayColumnIndex: Int = cursor.getColumnIndexOrThrow(EventDays.ENDDAY)

                // Set all the days with events to true
                while (cursor.moveToNext()) {
                    val firstDay: Int = cursor.getInt(startDayColumnIndex)
                    val lastDay: Int = cursor.getInt(endDayColumnIndex)
                    // we want the entire range the event occurs, but only within the month
                    val firstIndex: Int = Math.max(firstDay - startDay, 0)
                    val lastIndex: Int = Math.min(lastDay - startDay, 30)
                    for (i in firstIndex..lastIndex) {
                        eventDays[i] = true
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close()
                }
            }
            handler?.post(uiCallback)
        }

        @Override
        override fun skipRequest(eventLoader: EventLoader?) {
        }

        companion object {
            /**
             * The projection used by the EventDays query.
             */
            private val PROJECTION = arrayOf<String>(
                    CalendarContract.EventDays.STARTDAY, CalendarContract.EventDays.ENDDAY
            )
        }

        init {
            this.uiCallback = uiCallback
        }
    }

    private class LoadEventsRequest(
        var id: Int,
        var startDay: Int,
        var numDays: Int,
        events: ArrayList<Event?>,
        successCallback: Runnable,
        cancelCallback: Runnable
    ) : LoadRequest {
        var events: ArrayList<Event?>
        var successCallback: Runnable
        var cancelCallback: Runnable
        @Override
        override fun processRequest(eventLoader: EventLoader?) {
            Event.loadEvents(eventLoader?.mContext, events, startDay,
                    numDays, id, eventLoader?.mSequenceNumber)

            // Check if we are still the most recent request.
            if (id == eventLoader?.mSequenceNumber?.get()) {
                eventLoader.mHandler.post(successCallback)
            } else {
                eventLoader?.mHandler?.post(cancelCallback)
            }
        }

        @Override
        override fun skipRequest(eventLoader: EventLoader?) {
            eventLoader?.mHandler?.post(cancelCallback)
        }

        init {
            this.events = events
            this.successCallback = successCallback
            this.cancelCallback = cancelCallback
        }
    }

    private class LoaderThread(
        queue: LinkedBlockingQueue<LoadRequest>,
        eventLoader: EventLoader
    ) : Thread() {
        var mQueue: LinkedBlockingQueue<LoadRequest>
        var mEventLoader: EventLoader
        fun shutdown() {
            try {
                mQueue.put(ShutdownRequest())
            } catch (ex: InterruptedException) {
                // The put() method fails with InterruptedException if the
                // queue is full. This should never happen because the queue
                // has no limit.
                Log.e("Cal", "LoaderThread.shutdown() interrupted!")
            }
        }

        @Override
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            while (true) {
                try {
                    // Wait for the next request
                    var request: LoadRequest = mQueue.take()

                    // If there are a bunch of requests already waiting, then
                    // skip all but the most recent request.
                    while (!mQueue.isEmpty()) {
                        // Let the request know that it was skipped
                        request.skipRequest(mEventLoader)

                        // Skip to the next request
                        request = mQueue.take()
                    }
                    if (request is ShutdownRequest) {
                        return
                    }
                    request.processRequest(mEventLoader)
                } catch (ex: InterruptedException) {
                    Log.e("Cal", "background LoaderThread interrupted!")
                }
            }
        }

        init {
            mQueue = queue
            mEventLoader = eventLoader
        }
    }

    /**
     * Call this from the activity's onResume()
     */
    fun startBackgroundThread() {
        mLoaderThread = LoaderThread(mLoaderQueue, this)
        mLoaderThread?.start()
    }

    /**
     * Call this from the activity's onPause()
     */
    fun stopBackgroundThread() {
        mLoaderThread!!.shutdown()
    }

    /**
     * Loads "numDays" days worth of events, starting at start, into events.
     * Posts uiCallback to the [Handler] for this view, which will run in the UI thread.
     * Reuses an existing background thread, if events were already being loaded in the background.
     * NOTE: events and uiCallback are not used if an existing background thread gets reused --
     * the ones that were passed in on the call that results in the background thread getting
     * created are used, and the most recent call's worth of data is loaded into events and posted
     * via the uiCallback.
     */
    fun loadEventsInBackground(
        numDays: Int,
        events: ArrayList<Event?>,
        startDay: Int,
        successCallback: Runnable,
        cancelCallback: Runnable
    ) {

        // Increment the sequence number for requests.  We don't care if the
        // sequence numbers wrap around because we test for equality with the
        // latest one.
        val id: Int = mSequenceNumber?.incrementAndGet() as Int

        // Send the load request to the background thread
        val request = LoadEventsRequest(id, startDay, numDays,
                events, successCallback, cancelCallback)
        try {
            mLoaderQueue.put(request)
        } catch (ex: InterruptedException) {
            // The put() method fails with InterruptedException if the
            // queue is full. This should never happen because the queue
            // has no limit.
            Log.e("Cal", "loadEventsInBackground() interrupted!")
        }
    }

    /**
     * Sends a request for the days with events to be marked. Loads "numDays"
     * worth of days, starting at start, and fills in eventDays to express which
     * days have events.
     *
     * @param startDay First day to check for events
     * @param numDays Days following the start day to check
     * @param eventDay Whether or not an event exists on that day
     * @param uiCallback What to do when done (log data, redraw screen)
     */
    fun loadEventDaysInBackground(
        startDay: Int,
        numDays: Int,
        eventDays: BooleanArray,
        uiCallback: Runnable
    ) {
        // Send load request to the background thread
        val request = LoadEventDaysRequest(startDay, numDays,
                eventDays, uiCallback)
        try {
            mLoaderQueue.put(request)
        } catch (ex: InterruptedException) {
            // The put() method fails with InterruptedException if the
            // queue is full. This should never happen because the queue
            // has no limit.
            Log.e("Cal", "loadEventDaysInBackground() interrupted!")
        }
    }

    init {
        mContext = context
        mLoaderQueue = LinkedBlockingQueue<LoadRequest>()
        mResolver = context.getContentResolver()
    }
}