package com.tvonnet.debridxtreamiptv.ui.detail.phone

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi

/**
 * Draws the phone Detail screen from a [PhoneDetailModel]. No data access, no navigation — the
 * host supplies both, which is what lets one screen serve the IPTV fragments and the debrid
 * activities without any of them knowing about the others.
 *
 * The order it renders in is fixed by the handoff and is the same for a movie and a series; the
 * series simply adds the season bar and the episode list. Anything the two share is identical and
 * in the same place, so switching between them never feels like two screens.
 */
class PhoneDetailView(
    private val root: View,
    private val inflater: LayoutInflater,
    private val actions: PhoneDetailActions,
) {
    private val context: Context = root.context
    private var plotExpanded = false

    init {
        root.findViewById<View>(R.id.phone_dt_back).setOnClickListener { actions.onBack() }
        root.findViewById<View>(R.id.phone_dt_play).setOnClickListener { actions.onPlay() }
    }

    /** Chrome stays live while the body shimmers — the back arrow must never stop working. */
    fun showLoading() {
        val holder = root.findViewById<LinearLayout>(R.id.phone_dt_state)
        holder.removeAllViews()
        holder.isVisible = true
        holder.addView(inflater.inflate(R.layout.item_phone_detail_skeleton, holder, false))
    }

    /**
     * The header still renders on failure: the poster, title and year came from the card the user
     * tapped and are already in hand. A blank screen would throw away information we have.
     */
    fun showError(headerOnly: PhoneDetailModel) {
        render(headerOnly)
        val holder = root.findViewById<LinearLayout>(R.id.phone_dt_state)
        holder.removeAllViews()
        holder.isVisible = false
        root.findViewById<TextView>(R.id.phone_dt_facts).apply {
            setText(R.string.phone_details_unavailable)
            setTextColor(color(R.color.phone_amber))
        }
        val rails = root.findViewById<LinearLayout>(R.id.phone_dt_rails)
        val panel = inflater.inflate(R.layout.item_phone_error_panel, rails, false)
        panel.findViewById<TextView>(R.id.phone_error_body)
            .setText(R.string.phone_details_failed_body)
        panel.findViewById<View>(R.id.phone_error_retry).setOnClickListener { actions.onRetry() }
        // Sources resolve independently of metadata, so playback is still on the table.
        panel.findViewById<TextView>(R.id.phone_error_cached).apply {
            setText(R.string.phone_play_anyway)
            setOnClickListener { actions.onPlay() }
        }
        rails.removeAllViews()
        rails.addView(panel)
    }

    fun render(model: PhoneDetailModel) {
        root.findViewById<LinearLayout>(R.id.phone_dt_state).isVisible = false
        bindHeader(model)
        bindPrimary(model)
        bindActions(model)
        bindPlot(model)
        bindSeries(model)
        bindRails(model)
    }

    // ── header ──────────────────────────────────────────────────────────────

    private fun bindHeader(model: PhoneDetailModel) {
        root.findViewById<TextView>(R.id.phone_dt_bar_title).text = model.title
        root.findViewById<TextView>(R.id.phone_dt_title).apply {
            text = model.title
            // A twenty-word title drops a size and takes a third line rather than being cut off
            // mid-word — the header holds its 232dp either way.
            if (model.title.length > LONG_TITLE_CHARS) {
                textSize = 19f
                maxLines = 3
            } else {
                textSize = 22f
                maxLines = 2
            }
        }

        bindHeaderArt(model)

        root.findViewById<View>(R.id.phone_dt_rating_row).isVisible = !model.rating.isNullOrBlank()
        root.findViewById<TextView>(R.id.phone_dt_rating).text = model.rating.orEmpty()

        val facts = listOfNotNull(
            model.year?.takeIf { it.isNotBlank() },
            model.runtimeMinutes?.takeIf { it > 0 }?.let { runtime(it) },
            model.certification?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        root.findViewById<TextView>(R.id.phone_dt_facts).apply {
            text = facts
            isVisible = facts.isNotBlank()
            setTextColor(color(R.color.phone_text_secondary))
        }

        root.findViewById<TextView>(R.id.phone_dt_genres).apply {
            text = model.genres.orEmpty()
            isVisible = !model.genres.isNullOrBlank()
        }

        val badges = root.findViewById<LinearLayout>(R.id.phone_dt_badges)
        badges.removeAllViews()
        model.badges.forEach { badge ->
            val chip = inflater.inflate(R.layout.item_phone_detail_badge, badges, false) as TextView
            chip.text = badge.text
            when (badge.tone) {
                PhoneDetailModel.Badge.Tone.CYAN -> {
                    chip.setBackgroundResource(R.drawable.bg_phone_badge_cyan)
                    chip.setTextColor(color(R.color.phone_cyan))
                }
                PhoneDetailModel.Badge.Tone.GREEN -> {
                    chip.setBackgroundResource(R.drawable.bg_phone_badge_green)
                    chip.setTextColor(color(R.color.phone_green))
                }
                PhoneDetailModel.Badge.Tone.NEUTRAL -> {
                    chip.setBackgroundResource(R.drawable.bg_phone_badge_neutral)
                    chip.setTextColor(color(R.color.phone_text_secondary))
                }
            }
            badges.addView(chip)
        }
        badges.isVisible = model.badges.isNotEmpty()
    }

    /** The poster is always there; the backdrop is atmosphere, and often is not there at all. */
    private fun bindHeaderArt(model: PhoneDetailModel) {
        val poster = root.findViewById<ImageView>(R.id.phone_dt_poster)
        Glide.with(poster).load(model.posterUrl).centerCrop().into(poster)

        val backdrop = root.findViewById<ImageView>(R.id.phone_dt_backdrop)
        val header = root.findViewById<View>(R.id.phone_dt_header)
        if (model.backdropUrl.isNullOrBlank()) {
            // No backdrop is common enough that it cannot be a special case: the same 232dp fills
            // with a gradient and not one measurement changes.
            backdrop.setImageDrawable(null)
            header.setBackgroundResource(R.drawable.bg_phone_detail_gradient)
        } else {
            header.setBackgroundColor(color(R.color.phone_home_bg))
            Glide.with(backdrop).load(model.backdropUrl).centerCrop().into(backdrop)
        }
    }

    private fun bindPrimary(model: PhoneDetailModel) {
        val label = root.findViewById<TextView>(R.id.phone_dt_play_label)
        val position = root.findViewById<TextView>(R.id.phone_dt_play_position)
        val bar = root.findViewById<ProgressBar>(R.id.phone_dt_play_progress)
        val playback = model.playback
        if (playback == null || playback.positionMs <= 0) {
            label.setText(R.string.phone_play)
            position.text = model.runtimeMinutes?.takeIf { it > 0 }?.let { runtime(it) }.orEmpty()
            position.isVisible = position.text.isNotBlank()
            bar.isVisible = false
            return
        }
        label.setText(R.string.phone_resume)
        position.isVisible = true
        position.text = context.getString(
            R.string.phone_position_of, clock(playback.positionMs), clock(playback.durationMs)
        )
        bar.isVisible = true
        bar.progress = playback.percent
    }

    private fun bindActions(model: PhoneDetailModel) {
        val row = root.findViewById<LinearLayout>(R.id.phone_dt_actions)
        row.removeAllViews()
        val items = buildList {
            // Debrid only. IPTV has no source list, and a button that opens an empty screen is
            // worse than one that is not there.
            model.sourceCount?.let { count ->
                add(Action(context.getString(R.string.phone_sources) + " $count", R.drawable.ic_player_sources, false) { actions.onSources() })
            }
            add(
                Action(
                    context.getString(R.string.phone_favourite),
                    if (model.isFavourite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                    model.isFavourite,
                    tintOn = R.color.phone_red,
                ) { actions.onToggleFavourite() }
            )
            add(
                Action(
                    context.getString(R.string.phone_watched),
                    R.drawable.ic_check_circle,
                    model.isWatched,
                ) { actions.onToggleWatched() }
            )
            if (model.isSeries) {
                add(Action(context.getString(R.string.phone_episodes), R.drawable.ic_player_episodes, false) { scrollToEpisodes() })
            } else if (model.hasTrailer) {
                // Only when one exists — the row re-flexes to three rather than showing a button
                // that cannot do anything.
                add(Action(context.getString(R.string.phone_trailer), R.drawable.ic_trailer_video, false) { actions.onTrailer() })
            }
        }
        items.forEach { action ->
            val cell = inflater.inflate(R.layout.item_phone_detail_action, row, false)
            val tint = color(if (action.on) action.tintOn else R.color.phone_text_secondary)
            cell.findViewById<ImageView>(R.id.phone_act_icon).apply {
                setImageResource(action.iconRes)
                setColorFilter(tint)
            }
            cell.findViewById<TextView>(R.id.phone_act_label).apply {
                text = action.label
                setTextColor(tint)
            }
            cell.findViewById<View>(R.id.phone_act_root).setBackgroundResource(
                if (action.on) R.drawable.bg_phone_action_on else R.drawable.bg_phone_action
            )
            cell.setOnClickListener { action.onClick() }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            params.marginEnd = if (action === items.last()) 0 else PhoneUi.dp(context, 8)
            row.addView(cell, params)
        }
    }

    private data class Action(
        val label: String,
        val iconRes: Int,
        val on: Boolean,
        val tintOn: Int = R.color.phone_cyan,
        val onClick: () -> Unit,
    )

    /**
     * Four lines is enough to decide; More expands IN PLACE. A sheet would hide the plot behind a
     * second screen, and on a series the expanded plot cannot bury the episodes because the season
     * bar pins itself under the app bar as it arrives.
     */
    private fun bindPlot(model: PhoneDetailModel) {
        val plot = root.findViewById<TextView>(R.id.phone_dt_plot)
        val toggle = root.findViewById<TextView>(R.id.phone_dt_plot_toggle)
        val text = model.plot?.takeIf { it.isNotBlank() }
        plot.isVisible = text != null
        plot.text = text.orEmpty()
        plot.maxLines = if (plotExpanded) Int.MAX_VALUE else PLOT_LINES

        val needsToggle = text != null && text.length > PLOT_CLAMP_CHARS
        toggle.isVisible = needsToggle
        toggle.setText(if (plotExpanded) R.string.phone_less else R.string.phone_more)
        toggle.setOnClickListener {
            plotExpanded = !plotExpanded
            plot.maxLines = if (plotExpanded) Int.MAX_VALUE else PLOT_LINES
            toggle.setText(if (plotExpanded) R.string.phone_less else R.string.phone_more)
        }

        val credits = root.findViewById<TextView>(R.id.phone_dt_credits)
        val name = model.creditsName?.takeIf { it.isNotBlank() }
        credits.isVisible = name != null
        credits.text = if (name == null) "" else "${model.creditsLabel.orEmpty()}  $name"
    }

    private fun bindSeries(model: PhoneDetailModel) {
        val bar = root.findViewById<View>(R.id.phone_dt_season_bar)
        val list = root.findViewById<LinearLayout>(R.id.phone_dt_episodes)
        bar.isVisible = model.isSeries
        list.removeAllViews()
        if (!model.isSeries) return

        val season = model.seasons.firstOrNull { it.number == model.selectedSeason }
        root.findViewById<TextView>(R.id.phone_dt_season_name).text =
            context.getString(R.string.phone_season_n, model.selectedSeason ?: 1)
        root.findViewById<TextView>(R.id.phone_dt_season_meta).text = context.getString(
            R.string.phone_season_meta,
            season?.episodeCount ?: model.episodes.size,
            season?.watchedCount ?: model.episodes.count { it.watched },
        )
        bar.setOnClickListener { PhoneSeasonSheet.show(context, inflater, model, actions.onSeasonPicked) }

        model.episodes.forEach { episode -> list.addView(episodeRow(list, episode, model)) }
    }

    private fun episodeRow(
        parent: ViewGroup,
        episode: PhoneDetailModel.Episode,
        model: PhoneDetailModel,
    ): View {
        val row = inflater.inflate(R.layout.item_phone_episode, parent, false)
        val title = row.findViewById<TextView>(R.id.phone_ep_title)
        val meta = row.findViewById<TextView>(R.id.phone_ep_meta)
        val progress = row.findViewById<ProgressBar>(R.id.phone_ep_progress)

        row.findViewById<TextView>(R.id.phone_ep_number).text = "E%02d".format(episode.number)
        title.text = "${episode.number}. ${episode.title}"
        row.findViewById<TextView>(R.id.phone_ep_plot).apply {
            text = episode.plot.orEmpty()
            isVisible = !episode.plot.isNullOrBlank()
        }

        val still = row.findViewById<ImageView>(R.id.phone_ep_still)
        Glide.with(still).load(episode.stillUrl).centerCrop().into(still)

        val runtime = episode.runtimeMinutes ?: 0
        val isNextUp = model.nextUpEpisode != null && model.nextUpEpisode == episode.id
        val partial = episode.positionMs?.takeIf { it > 0 } ?: 0L
        val duration = episode.durationMs ?: 0L

        row.findViewById<View>(R.id.phone_ep_watched).isVisible = episode.watched
        row.findViewById<View>(R.id.phone_ep_badge).isVisible = isNextUp
        row.findViewById<View>(R.id.phone_ep_play).isVisible = isNextUp
        row.setBackgroundResource(
            if (isNextUp) R.drawable.bg_phone_row_next else R.drawable.bg_phone_row_reserved
        )
        title.setTextColor(color(if (episode.watched) R.color.phone_text_muted else R.color.phone_text_primary))

        when {
            partial > 0 && duration > 0 -> {
                progress.isVisible = true
                progress.progress = ((partial * 100) / duration).toInt().coerceIn(0, 100)
                meta.setTextColor(color(R.color.phone_cyan))
                meta.text = context.getString(
                    R.string.phone_episode_left,
                    ((duration - partial) / 60_000L).toInt().coerceAtLeast(0),
                    clock(partial),
                    clock(duration),
                )
            }
            episode.watched -> {
                progress.isVisible = false
                meta.setTextColor(color(R.color.phone_text_muted))
                meta.text = context.getString(R.string.phone_episode_watched_meta, runtime)
            }
            else -> {
                progress.isVisible = false
                meta.setTextColor(color(R.color.phone_text_muted))
                meta.text = if (runtime > 0) context.getString(R.string.phone_episode_meta, runtime) else ""
            }
        }

        row.setOnClickListener { actions.onEpisode(episode) }
        row.findViewById<View>(R.id.phone_ep_overflow).setOnClickListener {
            actions.onEpisodeOverflow(episode)
        }
        return row
    }

    private fun bindRails(model: PhoneDetailModel) {
        val rails = root.findViewById<LinearLayout>(R.id.phone_dt_rails)
        rails.removeAllViews()

        if (model.cast.isNotEmpty()) {
            addRail(rails, context.getString(R.string.phone_cast)) { items ->
                model.cast.forEach { member ->
                    val cell = inflater.inflate(R.layout.item_phone_cast, items, false)
                    cell.findViewById<TextView>(R.id.phone_cast_name).text = member.name
                    cell.findViewById<TextView>(R.id.phone_cast_role).apply {
                        text = member.role.orEmpty()
                        isVisible = !member.role.isNullOrBlank()
                    }
                    val photo = cell.findViewById<ImageView>(R.id.phone_cast_photo)
                    Glide.with(photo).load(member.photoUrl)
                        .placeholder(R.drawable.ic_person_placeholder).centerCrop().into(photo)
                    items.addView(cell)
                }
            }
        }

        if (model.similar.isNotEmpty()) {
            addRail(rails, context.getString(R.string.phone_more_like_this)) { items ->
                model.similar.forEach { item ->
                    val cell = inflater.inflate(R.layout.item_phone_similar, items, false)
                    cell.findViewById<TextView>(R.id.phone_sim_title).text = item.title
                    val poster = cell.findViewById<ImageView>(R.id.phone_sim_poster)
                    Glide.with(poster).load(item.posterUrl).centerCrop().into(poster)
                    cell.setOnClickListener { actions.onSimilar(item) }
                    items.addView(cell)
                }
            }
        }

        // What is absent is stated once, in one muted line. Never an empty carousel, never a row
        // of placeholder avatars.
        val notes = listOfNotNull(
            if (model.cast.isEmpty()) context.getString(R.string.phone_no_cast_data) else null,
            model.similarUnavailableNote,
        )
        if (notes.isNotEmpty()) {
            val note = inflater.inflate(R.layout.item_phone_note, rails, false) as TextView
            note.text = notes.joinToString(" · ")
            rails.addView(note)
        }
    }

    private fun addRail(parent: LinearLayout, title: String, fill: (LinearLayout) -> Unit) {
        val block = inflater.inflate(R.layout.item_phone_detail_rail, parent, false)
        block.findViewById<TextView>(R.id.phone_rail_title).text = title
        fill(block.findViewById(R.id.phone_rail_items))
        parent.addView(block)
    }

    private fun scrollToEpisodes() {
        val scroll = root.findViewById<androidx.core.widget.NestedScrollView>(R.id.phone_detail_scroll)
        val bar = root.findViewById<View>(R.id.phone_dt_season_bar)
        scroll.post { scroll.smoothScrollTo(0, bar.top) }
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private fun runtime(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

    private fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
        }
    }

    private companion object {
        const val PLOT_LINES = 4
        /** Past this, the four-line clamp is certainly hiding something worth a More control. */
        const val PLOT_CLAMP_CHARS = 180
        const val LONG_TITLE_CHARS = 34
    }
}
