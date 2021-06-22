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
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AbsListView.OnScrollListener
import android.widget.Adapter
import android.widget.FrameLayout
import android.widget.ListView

/**
 * Implements a ListView class with a sticky header at the top. The header is
 * per section and it is pinned to the top as long as its section is at the top
 * of the view. If it is not, the header slides up or down (depending on the
 * scroll movement) and the header of the current section slides to the top.
 * Notes:
 * 1. The class uses the first available child ListView as the working
 * ListView. If no ListView child exists, the class will create a default one.
 * 2. The ListView's adapter must be passed to this class using the 'setAdapter'
 * method. The adapter must implement the HeaderIndexer interface. If no adapter
 * is specified, the class will try to extract it from the ListView
 * 3. The class registers itself as a listener to scroll events (OnScrollListener), if the
 * ListView needs to receive scroll events, it must register its listener using
 * this class' setOnScrollListener method.
 * 4. Headers for the list view must be added before using the StickyHeaderListView
 * 5. The implementation should register to listen to dataset changes. Right now this is not done
 * since a change the dataset in a listview forces a call to OnScroll. The needed code is
 * commented out.
 */
class StickyHeaderListView(context: Context, attrs: AttributeSet?) :
    FrameLayout(context, attrs), OnScrollListener {
    protected var mChildViewsCreated = false
    protected var mDoHeaderReset = false
    protected var mContext: Context? = null
    protected var mAdapter: Adapter? = null
    protected var mIndexer: HeaderIndexer? = null
    protected var mHeaderHeightListener: HeaderHeightListener? = null
    protected var mStickyHeader: View? = null
    // A invisible header used when a section has no header
    protected var mNonessentialHeader: View? = null
    protected var mListView: ListView? = null
    protected var mListener: AbsListView.OnScrollListener? = null
    private var mSeparatorWidth = 0
    private var mSeparatorView: View? = null
    private var mLastStickyHeaderHeight = 0

    // This code is needed only if dataset changes do not force a call to OnScroll
    // protected DataSetObserver mListDataObserver = null;
    protected var mCurrentSectionPos = -1 // Position of section that has its header on the

    // top of the view
    protected var mNextSectionPosition = -1 // Position of next section's header
    protected var mListViewHeadersCount = 0

    /**
     * Interface that must be implemented by the ListView adapter to provide headers locations
     * and number of items under each header.
     *
     */
    interface HeaderIndexer {
        /**
         * Calculates the position of the header of a specific item in the adapter's data set.
         * For example: Assuming you have a list with albums and songs names:
         * Album A, song 1, song 2, ...., song 10, Album B, song 1, ..., song 7. A call to
         * this method with the position of song 5 in Album B, should return  the position
         * of Album B.
         * @param position - Position of the item in the ListView dataset
         * @return Position of header. -1 if the is no header
         */
        fun getHeaderPositionFromItemPosition(position: Int): Int

        /**
         * Calculates the number of items in the section defined by the header (not including
         * the header).
         * For example: A list with albums and songs, the method should return
         * the number of songs names (without the album name).
         *
         * @param headerPosition - the value returned by 'getHeaderPositionFromItemPosition'
         * @return Number of items. -1 on error.
         */
        fun getHeaderItemsNumber(headerPosition: Int): Int
    }

    /***
     *
     * Interface that is used to update the sticky header's height
     *
     */
    interface HeaderHeightListener {
        /***
         * Updated a change in the sticky header's size
         *
         * @param height - new height of sticky header
         */
        fun OnHeaderHeightChanged(height: Int)
    }

    /**
     * Sets the adapter to be used by the class to get views of headers
     *
     * @param adapter - The adapter.
     */
    fun setAdapter(adapter: Adapter?) {
        // This code is needed only if dataset changes do not force a call to
        // OnScroll
        // if (mAdapter != null && mListDataObserver != null) {
        // mAdapter.unregisterDataSetObserver(mListDataObserver);
        // }
        if (adapter != null) {
            mAdapter = adapter
            // This code is needed only if dataset changes do not force a call
            // to OnScroll
            // mAdapter.registerDataSetObserver(mListDataObserver);
        }
    }

    /**
     * Sets the indexer object (that implements the HeaderIndexer interface).
     *
     * @param indexer - The indexer.
     */
    fun setIndexer(indexer: HeaderIndexer?) {
        mIndexer = indexer
    }

    /**
     * Sets the list view that is displayed
     * @param lv - The list view.
     */
    fun setListView(lv: ListView?) {
        mListView = lv
        mListView?.setOnScrollListener(this)
        mListViewHeadersCount = mListView?.getHeaderViewsCount() as Int
    }

    /**
     * Sets an external OnScroll listener. Since the StickyHeaderListView sets
     * itself as the scroll events listener of the listview, this method allows
     * the user to register another listener that will be called after this
     * class listener is called.
     *
     * @param listener - The external listener.
     */
    fun setOnScrollListener(listener: AbsListView.OnScrollListener?) {
        mListener = listener
    }

    fun setHeaderHeightListener(listener: HeaderHeightListener?) {
        mHeaderHeightListener = listener
    }

    /**
     * Scroll status changes listener
     *
     * @param view - the scrolled view
     * @param scrollState - new scroll state.
     */
    @Override
    override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
        if (mListener != null) {
            mListener?.onScrollStateChanged(view, scrollState)
        }
    }

    /**
     * Scroll events listener
     *
     * @param view - the scrolled view
     * @param firstVisibleItem - the index (in the list's adapter) of the top
     * visible item.
     * @param visibleItemCount - the number of visible items in the list
     * @param totalItemCount - the total number items in the list
     */
    @Override
    override fun onScroll(
        view: AbsListView?,
        firstVisibleItem: Int,
        visibleItemCount: Int,
        totalItemCount: Int
    ) {
        updateStickyHeader(firstVisibleItem)
        if (mListener != null) {
            mListener?.onScroll(view, firstVisibleItem, visibleItemCount, totalItemCount)
        }
    }

    /**
     * Sets a separator below the sticky header, which will be visible while the sticky header
     * is not scrolling up.
     * @param color - color of separator
     * @param width - width in pixels of separator
     */
    fun setHeaderSeparator(color: Int, width: Int) {
        mSeparatorView = View(mContext)
        val params: ViewGroup.LayoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            width, Gravity.TOP
        )
        mSeparatorView?.setLayoutParams(params)
        mSeparatorView?.setBackgroundColor(color)
        mSeparatorWidth = width
        this.addView(mSeparatorView)
    }

    protected fun updateStickyHeader(firstVisibleItemInput: Int) {
        // Try to make sure we have an adapter to work with (may not succeed).
        var firstVisibleItem = firstVisibleItemInput
        if (mAdapter == null && mListView != null) {
            setAdapter(mListView?.getAdapter())
        }
        firstVisibleItem -= mListViewHeadersCount
        if (mAdapter != null && mIndexer != null && mDoHeaderReset) {

            // Get the section header position
            var sectionSize = 0
            val sectionPos = mIndexer!!.getHeaderPositionFromItemPosition(firstVisibleItem)

            // New section - set it in the header view
            var newView = false
            if (sectionPos != mCurrentSectionPos) {

                // No header for current position , use the nonessential invisible one,
                // hide the separator
                if (sectionPos == -1) {
                    sectionSize = 0
                    this.removeView(mStickyHeader)
                    mStickyHeader = mNonessentialHeader
                    if (mSeparatorView != null) {
                        mSeparatorView?.setVisibility(View.GONE)
                    }
                    newView = true
                } else {
                    // Create a copy of the header view to show on top
                    sectionSize = mIndexer!!.getHeaderItemsNumber(sectionPos)
                    val v: View? =
                        mAdapter?.getView(sectionPos + mListViewHeadersCount, null, mListView)
                    v?.measure(
                        MeasureSpec.makeMeasureSpec(
                            mListView?.getWidth() as Int,
                            MeasureSpec.EXACTLY
                        ), MeasureSpec.makeMeasureSpec(
                            mListView?.getHeight() as Int,
                            MeasureSpec.AT_MOST
                        )
                    )
                    this.removeView(mStickyHeader)
                    mStickyHeader = v
                    newView = true
                }
                mCurrentSectionPos = sectionPos
                mNextSectionPosition = sectionSize + sectionPos + 1
            }

            // Do transitions
            // If position of bottom of last item in a section is smaller than the height of the
            // sticky header - shift drawable of header.
            if (mStickyHeader != null) {
                val sectionLastItemPosition = mNextSectionPosition - firstVisibleItem - 1
                var stickyHeaderHeight: Int = mStickyHeader?.getHeight() as Int
                if (stickyHeaderHeight == 0) {
                    stickyHeaderHeight = mStickyHeader?.getMeasuredHeight() as Int
                }

                // Update new header height
                if (mHeaderHeightListener != null &&
                    mLastStickyHeaderHeight != stickyHeaderHeight
                ) {
                    mLastStickyHeaderHeight = stickyHeaderHeight
                    mHeaderHeightListener!!.OnHeaderHeightChanged(stickyHeaderHeight)
                }
                val SectionLastView: View? = mListView?.getChildAt(sectionLastItemPosition)
                if (SectionLastView != null && SectionLastView.getBottom() <= stickyHeaderHeight) {
                    val lastViewBottom: Int = SectionLastView.getBottom()
                    mStickyHeader?.setTranslationY(lastViewBottom.toFloat() -
                        stickyHeaderHeight.toFloat())
                    if (mSeparatorView != null) {
                        mSeparatorView?.setVisibility(View.GONE)
                    }
                } else if (stickyHeaderHeight != 0) {
                    mStickyHeader?.setTranslationY(0f)
                    if (mSeparatorView != null &&
                        mStickyHeader?.equals(mNonessentialHeader) == false) {
                        mSeparatorView?.setVisibility(View.VISIBLE)
                    }
                }
                if (newView) {
                    mStickyHeader?.setVisibility(View.INVISIBLE)
                    this.addView(mStickyHeader)
                    if (mSeparatorView != null &&
                        mStickyHeader?.equals(mNonessentialHeader) == false) {
                        val params: FrameLayout.LayoutParams = LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            mSeparatorWidth
                        )
                        params.setMargins(0, mStickyHeader?.getMeasuredHeight() as Int, 0, 0)
                        mSeparatorView?.setLayoutParams(params)
                        mSeparatorView?.setVisibility(View.VISIBLE)
                    }
                    mStickyHeader?.setVisibility(View.VISIBLE)
                }
            }
        }
    }

    @Override
    protected override fun onFinishInflate() {
        super.onFinishInflate()
        if (!mChildViewsCreated) {
            setChildViews()
        }
        mDoHeaderReset = true
    }

    @Override
    protected override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!mChildViewsCreated) {
            setChildViews()
        }
        mDoHeaderReset = true
    }

    // Resets the sticky header when the adapter data set was changed
    // This code is needed only if dataset changes do not force a call to OnScroll
    // protected void onDataChanged() {
    // Should do a call to updateStickyHeader if needed
    // }
    private fun setChildViews() {
        // Find a child ListView (if any)
        val iChildNum: Int = getChildCount()
        for (i in 0 until iChildNum) {
            val v: Object = getChildAt(i) as Object
            if (v is ListView) {
                setListView(v as ListView)
            }
        }

        // No child ListView - add one
        if (mListView == null) {
            setListView(ListView(mContext))
        }

        // Create a nonessential view , it will be used in case a section has no header
        mNonessentialHeader = View(mContext)
        val params: ViewGroup.LayoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            1, Gravity.TOP
        )
        mNonessentialHeader?.setLayoutParams(params)
        mNonessentialHeader?.setBackgroundColor(Color.TRANSPARENT)
        mChildViewsCreated = true
    }

    companion object {
        private const val TAG = "StickyHeaderListView"
    }

    /**
     * Constructor
     *
     * @param context - application context.
     * @param attrs - layout attributes.
     */
    init {
        mContext = context
    }
}