package com.tvonnet.debridxtreamiptv.ui.live.phone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.util.PortraitScreen
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The full-day guide for ONE channel — the handoff's answer to "where does the guide live on a
 * phone".
 *
 * A TV-style timeline grid is unreadable at 411dp, and this app's `EpgGridView` implements
 * `onKeyDown` and nothing else: shipping it to a touchscreen would ship a screen that renders and
 * then ignores every finger. What the data actually supports is this channel's programmes, read
 * downwards, with the current one marked and a day switcher on top.
 */
@AndroidEntryPoint
class PhoneGuideFragment : Fragment(), PortraitScreen {

    @Inject
    lateinit var epgDao: com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao

    @Inject
    lateinit var favoriteDao: com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao

    private lateinit var adapter: GuideAdapter
    private var dayOffset = 0
    private var programmes: List<EpgEntity> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = PhoneUi.unscaled(this, inflater)
        .inflate(R.layout.fragment_guide_phone, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val channelName = requireArguments().getString(ARG_NAME).orEmpty()
        val number = requireArguments().getString(ARG_NUMBER).orEmpty()
        view.findViewById<TextView>(R.id.phone_guide_channel).text = channelName
        view.findViewById<TextView>(R.id.phone_guide_subtitle).text =
            if (number.isBlank()) {
                getString(R.string.phone_full_guide).uppercase(Locale.ROOT)
            } else {
                "CH $number · " + getString(R.string.phone_full_guide).uppercase(Locale.ROOT)
            }

        adapter = GuideAdapter()
        view.findViewById<RecyclerView>(R.id.phone_guide_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PhoneGuideFragment.adapter
        }

        view.findViewById<View>(R.id.phone_guide_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.phone_guide_prev_day).setOnClickListener {
            dayOffset -= 1
            render(view)
        }
        view.findViewById<View>(R.id.phone_guide_next_day).setOnClickListener {
            dayOffset += 1
            render(view)
        }
        bindStar(view)

        loadProgrammes(view)
    }

    private fun bindStar(view: View) {
        val id = requireArguments().getString(ARG_STREAM_ID) ?: return
        val star = view.findViewById<ImageView>(R.id.phone_guide_star) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { favoriteDao.isFavorite(id) }
            star.setImageResource(
                if (saved) R.drawable.ic_live_star_filled else R.drawable.ic_live_star
            )
            star.setColorFilter(
                ContextCompat.getColor(
                    requireContext(),
                    if (saved) R.color.phone_amber else R.color.phone_text_muted,
                )
            )
        }
        star.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { favoriteDao.isFavorite(id) }
                if (saved) {
                    favoriteDao.deleteFavoriteByStreamId(id)
                } else {
                    favoriteDao.insertFavorite(
                        com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity(
                            streamId = id,
                            type = "live",
                            name = requireArguments().getString(ARG_NAME).orEmpty(),
                            iconUrl = requireArguments().getString(ARG_LOGO),
                        )
                    )
                }
                bindStar(view)
            }
        }
    }

    /**
     * Read from the EPG table off the main thread, bounded to a week around now — a channel can
     * carry a fortnight of programmes and the switcher only ever walks a few days.
     */
    private fun loadProgrammes(view: View) {
        val key = requireArguments().getString(ARG_EPG_ID)?.takeIf { it.isNotBlank() }
            ?: requireArguments().getString(ARG_STREAM_ID)
            ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val from = startOfDay(-1)
            programmes = withContext(Dispatchers.IO) {
                runCatching {
                    epgDao.getProgramsForChannelsInRange(listOf(key), from, from + 8 * DAY_MS)
                }.getOrDefault(emptyList())
            }
            render(view)
        }
    }

    private fun render(view: View) {
        val dayStart = startOfDay(dayOffset)
        val dayEnd = dayStart + DAY_MS
        val forDay = programmes
            .filter { it.start < dayEnd && it.stop > dayStart }
            .sortedBy { it.start }

        view.findViewById<TextView>(R.id.phone_guide_day).text = when (dayOffset) {
            0 -> getString(R.string.phone_today)
            1 -> getString(R.string.c_tomorrow)
            else -> DAY_NAME.format(Date(dayStart))
        }
        view.findViewById<TextView>(R.id.phone_guide_date).text =
            DATE.format(Date(dayStart)).uppercase(Locale.ROOT)
        view.findViewById<View>(R.id.phone_guide_empty).isVisible = forDay.isEmpty()
        adapter.submit(forDay)

        // Open on what is on NOW rather than at midnight — the day the user asked for is almost
        // always the one they are in the middle of.
        val nowIndex = forDay.indexOfFirst { it.isPlaying() }
        if (nowIndex > 0) {
            view.findViewById<RecyclerView>(R.id.phone_guide_list)?.scrollToPosition(nowIndex)
        }
    }

    private fun startOfDay(offset: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private inner class GuideAdapter : RecyclerView.Adapter<GuideAdapter.VH>() {
        private var items: List<EpgEntity> = emptyList()

        fun submit(list: List<EpgEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_phone_guide_row, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) =
            holder.bind(items[position], items.getOrNull(position - 1))

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val time: TextView = itemView.findViewById(R.id.phone_pg_time)
            private val title: TextView = itemView.findViewById(R.id.phone_pg_title)
            private val badge: TextView = itemView.findViewById(R.id.phone_pg_badge)
            private val duration: TextView = itemView.findViewById(R.id.phone_pg_duration)
            private val progress: ProgressBar = itemView.findViewById(R.id.phone_pg_progress)

            fun bind(programme: EpgEntity, previous: EpgEntity?) {
                val ctx = itemView.context
                time.text = TIME.format(Date(programme.start))
                title.text = programme.title.orEmpty()
                duration.text = "${(programme.stop - programme.start) / 60_000} MIN"

                val playing = programme.isPlaying()
                val isNext = !playing && previous?.isPlaying() == true
                badge.isVisible = playing || isNext
                badge.text = getString(
                    if (playing) R.string.phone_now_badge else R.string.phone_next_badge
                )
                badge.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (playing) R.color.phone_cyan else R.color.phone_text_secondary,
                    )
                )
                time.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (playing) R.color.phone_cyan else R.color.phone_text_secondary,
                    )
                )
                itemView.setBackgroundResource(
                    if (playing) R.drawable.bg_phone_row_playing else R.drawable.bg_phone_row_default
                )

                progress.isVisible = playing
                if (playing) {
                    val span = (programme.stop - programme.start).coerceAtLeast(1)
                    val elapsed = System.currentTimeMillis() - programme.start
                    progress.progress = ((elapsed * 100) / span).toInt().coerceIn(0, 100)
                }
            }
        }
    }

    companion object {
        private const val ARG_STREAM_ID = "streamId"
        private const val ARG_EPG_ID = "epgId"
        private const val ARG_NAME = "name"
        private const val ARG_NUMBER = "number"
        private const val ARG_LOGO = "logo"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private val TIME = SimpleDateFormat("HH:mm", Locale.ROOT)
        private val DATE = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        private val DAY_NAME = SimpleDateFormat("EEEE", Locale.getDefault())

        fun newInstance(
            channel: com.tvonnet.debridxtreamiptv.data.model.XtreamStream
        ) = PhoneGuideFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_STREAM_ID, channel.stream_id)
                putString(ARG_EPG_ID, channel.epg_channel_id)
                putString(ARG_NAME, channel.name)
                putString(ARG_NUMBER, channel.num?.toString())
                putString(ARG_LOGO, channel.stream_icon)
            }
        }
    }
}
