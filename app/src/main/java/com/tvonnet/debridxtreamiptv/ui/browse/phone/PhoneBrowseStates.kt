package com.tvonnet.debridxtreamiptv.ui.browse.phone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.tvonnet.debridxtreamiptv.R

/**
 * What Browse says when the grid has nothing to draw.
 *
 * Four different situations, four different sentences and four different next actions — because
 * "nothing here" is useless in every one of them. A category that has 96 titles the filters
 * removed needs a different answer from a playlist that has no films at all, and the user can
 * only tell which they are in if the screen says so.
 *
 * The chrome above stays live in every case except the empty catalogue: the fix for an empty
 * result is a category or a filter, and both live up there.
 */
class PhoneBrowseStates(
    private val host: ViewGroup,
    private val inflater: LayoutInflater,
) {
    private var panel: View? = null

    /** The category is listed by the provider but came back with nothing in it. */
    fun emptyCategory(categoryName: String, onAll: () -> Unit, onRetry: () -> Unit) = show(
        icon = R.drawable.ic_filter,
        title = host.context.getString(R.string.phone_state_empty_category_title, categoryName),
        body = host.context.getString(R.string.phone_state_empty_category_body),
        primary = host.context.getString(R.string.phone_state_all_titles) to onAll,
        secondary = host.context.getString(R.string.phone_retry) to onRetry,
    )

    /** The playlist genuinely carries no VOD. Controls for nothing are worse than no controls. */
    fun emptyCatalogue(onRecheck: () -> Unit) = show(
        icon = R.drawable.ic_error_network,
        title = host.context.getString(R.string.phone_state_empty_catalogue_title),
        body = host.context.getString(R.string.phone_state_empty_catalogue_body),
        primary = host.context.getString(R.string.phone_state_recheck) to onRecheck,
        secondary = null,
    )

    /**
     * The fetch failed. The category rail survives on purpose — categories come from a different
     * call and are cached, so the user's cheapest escape is still one tap away.
     */
    fun error(onRetry: () -> Unit, onCached: (() -> Unit)?) = show(
        icon = R.drawable.ic_error_network,
        title = host.context.getString(R.string.phone_state_error_title),
        body = host.context.getString(R.string.phone_state_error_body),
        primary = host.context.getString(R.string.phone_retry) to onRetry,
        secondary = onCached?.let { host.context.getString(R.string.phone_state_use_cached) to it },
    )

    fun hide() {
        panel?.let(host::removeView)
        panel = null
    }

    private fun show(
        icon: Int,
        title: String,
        body: String,
        primary: Pair<String, () -> Unit>,
        secondary: Pair<String, () -> Unit>?,
    ) {
        val view = panel ?: inflater.inflate(R.layout.view_phone_browse_state, host, false)
            .also { host.addView(it); panel = it }

        view.findViewById<ImageView>(R.id.phone_state_icon).setImageResource(icon)
        view.findViewById<TextView>(R.id.phone_state_title).text = title
        view.findViewById<TextView>(R.id.phone_state_body).text = body
        view.findViewById<TextView>(R.id.phone_state_primary).apply {
            text = primary.first
            setOnClickListener { primary.second() }
        }
        view.findViewById<TextView>(R.id.phone_state_secondary).apply {
            isVisible = secondary != null
            text = secondary?.first.orEmpty()
            setOnClickListener { secondary?.second?.invoke() }
        }
    }
}
