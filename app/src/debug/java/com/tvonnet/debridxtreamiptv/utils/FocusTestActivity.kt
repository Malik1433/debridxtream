package com.tvonnet.debridxtreamiptv.utils

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Bare host for the D-pad focus tests: one [RecyclerView] of focusable rows plus one focusable
 * control OUTSIDE it, which is what lets a test prove "a refresh must not steal focus from
 * somewhere else".
 *
 * Lives in the **debug** source set (declared in `app/src/debug/AndroidManifest.xml`) so it runs in
 * the app process and never ships in a release build. It cannot live in `androidTest`: that would
 * register it under `com.debridxtream.tv.test` and `ActivityScenario` fails with "Intent in process
 * com.debridxtream.tv resolved to different process".
 */
class FocusTestActivity : Activity() {

    lateinit var recyclerView: RecyclerView
        private set

    /** Stands in for the sidebar / chip strip the user might be on during a background refresh. */
    lateinit var outsideButton: Button
        private set

    val listAdapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        outsideButton = Button(this).apply {
            text = "outside"
            isFocusable = true
            isFocusableInTouchMode = true
        }

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FocusTestActivity)
            adapter = listAdapter
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.FILL
                addView(outsideButton)
                addView(recyclerView)
            }
        )
    }

    /** Rows carry stable ids, which is what `updatePreservingFocus` restores by. */
    class RowAdapter : RecyclerView.Adapter<RowAdapter.RowHolder>() {

        private var ids: List<Long> = emptyList()

        init {
            setHasStableIds(true)
        }

        fun setIds(newIds: List<Long>) {
            ids = newIds
            notifyDataSetChanged()
        }

        fun idAt(position: Int): Long = ids[position]

        override fun getItemId(position: Int): Long = ids[position]

        override fun getItemCount(): Int = ids.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = TextView(parent.context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                height = ROW_HEIGHT_PX
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ROW_HEIGHT_PX
                )
            }
            return RowHolder(row)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val id = ids[position]
            (holder.itemView as TextView).text = rowLabel(id)
            holder.itemView.tag = id
        }

        class RowHolder(view: View) : RecyclerView.ViewHolder(view)

        companion object {
            private const val ROW_HEIGHT_PX = 120
            fun rowLabel(id: Long): String = "row-$id"
        }
    }
}
