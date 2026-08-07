package com.tvonnet.debridxtreamiptv.ui.home

import android.content.Context
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem

/**
 * M2c — the phone's Continue Watching card options.
 *
 * On TV the same two actions live in the quick-info bubble: long-press the card and the bubble
 * swaps its resume line for two chips you move between with the D-pad. That control cannot work in
 * the hand — its text is 5.5-7.5sp, its chips are 20dp tall, and it opens by calling
 * `requestFocus()`, which is a no-op in touch mode — so the phone gets a bottom sheet instead:
 * full-width 56dp rows, 16sp, one tap acts, tapping the scrim closes.
 *
 * A [BottomSheetDialog] rather than a DialogFragment because the caller is a RecyclerView holder,
 * which has a Context but no FragmentManager.
 *
 * The explicit theme is load-bearing: the Context-only constructor picks Material's LIGHT dialog
 * theme, and since our layout paints everything inside, the only thing that showed was the window's
 * navigation-bar area — a pale band under a dark sheet. Colouring the sheet container in code does
 * not reach it, because that band belongs to the dialog's WINDOW.
 */
object ContinueWatchingOptionsSheet {

    fun show(
        context: Context,
        item: ContinueWatchingItem,
        onOpenDetail: (ContinueWatchingItem) -> Unit,
        onRemoveItem: (ContinueWatchingItem) -> Unit
    ) {
        val dialog = BottomSheetDialog(context, R.style.ThemeOverlay_DebridXtream_BottomSheetDialog)
        dialog.setContentView(R.layout.sheet_continue_watching_options)

        dialog.findViewById<TextView>(R.id.tv_sheet_title)?.text =
            item.title.takeIf { it.isNotBlank() } ?: context.getString(R.string.cw_options_title)

        dialog.findViewById<TextView>(R.id.tv_sheet_details)?.setOnClickListener {
            dialog.dismiss()
            onOpenDetail(item)
        }
        dialog.findViewById<TextView>(R.id.tv_sheet_remove)?.setOnClickListener {
            dialog.dismiss()
            onRemoveItem(item)
        }

        dialog.show()
    }
}
