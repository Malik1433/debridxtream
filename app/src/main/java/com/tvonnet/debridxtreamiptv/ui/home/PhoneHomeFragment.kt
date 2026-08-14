package com.tvonnet.debridxtreamiptv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.FeaturedItem
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem
import com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PHONE Home — portrait, built to the "DX Play Home" handoff (2026-08-13).
 *
 * A SEPARATE fragment from [HomeFragment] rather than more device bools inside it, which is what
 * the handoff asks for and what the two-rulebooks rule has wanted all along: the TV screen is a
 * left rail with 10-foot type and a focus model, this is an app bar, a hero, rails and a bottom
 * bar for a thumb. They no longer share an arrangement, so sharing a file would mean two designs
 * fighting over one set of views.
 *
 * What they DO share is the data: this observes the same [HomeViewModel] state the TV screen does,
 * so there is one place that knows how to build a home screen and two ways of drawing it.
 */
@AndroidEntryPoint
class PhoneHomeFragment : Fragment(), HomeRouterHost, com.tvonnet.debridxtreamiptv.util.PortraitScreen {

    @javax.inject.Inject
    lateinit var repository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

    @javax.inject.Inject
    lateinit var episodeDaoV2: com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2

    /**
     * Favorites is a row in the handoff but not a field on HomeUiState — the TV Home has never
     * shown one. Read straight from the DAO rather than widening the shared state for one screen.
     */
    @javax.inject.Inject
    lateinit var favoriteDao: com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var credentialsPrefs:
        com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences

    /**
     * The same router the TV Home uses, so Continue Watching resumes by the one play-then-repair
     * path and detail pages open the one way. Only the drawing differs between the two screens.
     */
    private lateinit var router: HomeNavigationRouter

    // ── HomeRouterHost ── the phone has no hero focus memory and no navigation latch, so those
    // two hooks stay at their no-op defaults.
    override val routerFragment: Fragment get() = this
    override val routerRepository get() = repository
    override val routerCredentials get() = credentialsPrefs
    override val routerEpisodeDao get() = episodeDaoV2

    private var heroItems: List<FeaturedItem> = emptyList()
    private var heroIndex = 0
    private var favorites: List<com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity> =
        emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        uiInflater = unscaledInflater(inflater)
        return uiInflater.inflate(R.layout.fragment_home_phone, container, false)
    }

    /** Every card on this screen inflates from here, so none of them picks the scale back up. */
    private lateinit var uiInflater: LayoutInflater

    /**
     * MainActivity multiplies every `sp` by 1.6 so the TV layout's 7sp type is legible in the
     * hand. This screen is drawn at real phone sizes with nothing below 12sp, so that multiplier
     * would only blow it apart — it inflates against the application's configuration instead,
     * which still carries the USER's own accessibility font scale, just not our rescue factor.
     */
    private fun unscaledInflater(inflater: LayoutInflater): LayoutInflater {
        val userScale = requireContext().applicationContext.resources.configuration.fontScale
        val override = android.content.res.Configuration().apply { fontScale = userScale }
        val unscaled = requireContext().createConfigurationContext(override)
        return inflater.cloneInContext(
            android.view.ContextThemeWrapper(unscaled, R.style.AppTheme)
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        credentialsPrefs =
            com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(requireContext())
        router = HomeNavigationRouter(this)
        initializeRepository()

        buildBottomNav(view)
        bindAppBar(view)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(view, it) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                favoriteDao.getAllFavorites().collect {
                    favorites = it
                    render(view, viewModel.uiState.value)
                }
            }
        }

        // Hero rotation, 7s per the handoff. Tied to STARTED so it stops when the screen is not
        // in front of the user rather than spinning in the background.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(HERO_ROTATE_MS)
                    if (heroItems.size > 1) {
                        heroIndex = (heroIndex + 1) % heroItems.size
                        bindHero(view, heroItems[heroIndex])
                    }
                }
            }
        }
    }

    // ── chrome ──────────────────────────────────────────────────────────────

    private fun bindAppBar(view: View) {
        view.findViewById<View>(R.id.phone_action_search)?.setOnClickListener {
            router.navigateToSection(SectionNavigator.SECTION_SEARCH)
        }
        // The avatar is the account and everything behind it. Settings is here rather than in the
        // bottom bar because the bar holds the five places you go to WATCH something.
        view.findViewById<View>(R.id.phone_action_avatar)?.setOnClickListener {
            router.navigateToSection(SectionNavigator.SECTION_SETTINGS)
        }
        view.findViewById<TextView>(R.id.phone_avatar_initial)?.text = accountInitial()
    }

    private fun accountInitial(): String {
        val email = runCatching {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val name = email
            ?: com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(requireContext()).getUsername()
        return name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    }

    /**
     * The bar is shared with the other phone destinations (see PhoneBottomNav) so the five
     * slots, their order and the Debrid gate live in exactly one place.
     */
    private fun buildBottomNav(view: View) {
        com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneBottomNav.build(
            bar = view.findViewById(R.id.phone_bottom_nav),
            inflater = uiInflater,
            active = SectionNavigator.SECTION_HOME,
        ) { section -> router.navigateToSection(section) }
    }

    // ── content ─────────────────────────────────────────────────────────────

    private fun render(view: View, state: HomeUiState) {
        val rows = view.findViewById<LinearLayout>(R.id.phone_rows) ?: return
        rows.removeAllViews()

        // Before the catalogue resolves there is a hero-shaped hole and empty rails. Draw the
        // skeleton at the real geometry instead, so the screen does not jump when data lands.
        val nothingYet = state.top10Movies.isEmpty() && state.top10Series.isEmpty() &&
            state.continueWatching.isEmpty() && state.recentLiveChannels.isEmpty()
        view.findViewById<View>(R.id.phone_hero)?.isVisible = !nothingYet
        if (nothingYet) {
            rows.addView(uiInflater.inflate(R.layout.item_phone_skeleton, rows, false))
            return
        }

        heroItems = (state.top10Movies + state.top10Series).take(HERO_COUNT)
        heroItems.getOrNull(heroIndex.coerceAtMost(heroItems.lastIndex.coerceAtLeast(0)))
            ?.let { bindHero(view, it) }

        addContentRows(rows, state)
        addFavoritesRow(rows)
    }

    /** The four data-backed rows, in the order the handoff fixes. An empty one is simply absent. */
    private fun addContentRows(rows: LinearLayout, state: HomeUiState) {
        if (state.continueWatching.isNotEmpty()) {
            addRow(rows, getString(R.string.section_continue_watching), state.continueWatching.size) { rail ->
                state.continueWatching.forEach { rail.addView(continueCard(rail, it)) }
            }
        }
        if (state.top10Movies.isNotEmpty()) {
            addRow(rows, getString(R.string.nav_movies), state.top10Movies.size) { rail ->
                state.top10Movies.forEach { rail.addView(posterCard(rail, it)) }
            }
        }
        if (state.top10Series.isNotEmpty()) {
            addRow(rows, getString(R.string.nav_series), state.top10Series.size) { rail ->
                state.top10Series.forEach { rail.addView(posterCard(rail, it)) }
            }
        }
        if (state.recentLiveChannels.isNotEmpty()) {
            addRow(rows, getString(R.string.section_recent_live), state.recentLiveChannels.size) { rail ->
                state.recentLiveChannels.forEach { rail.addView(channelCard(rail, it)) }
            }
        }
    }

    /**
     * Favorites is the one row that renders when it is EMPTY: the handoff gives it a panel that
     * says what it is for. A blank gap where a row header should be reads as a bug.
     */
    private fun addFavoritesRow(rows: LinearLayout) {
        if (favorites.isEmpty()) {
            addRow(rows, getString(R.string.section_favorites), 0) {}
            val panel = uiInflater.inflate(R.layout.item_phone_empty_panel, rows, false)
            panel.findViewById<View>(R.id.phone_empty_action).setOnClickListener {
                router.navigateToSection(SectionNavigator.SECTION_MOVIES)
            }
            rows.addView(panel)
            return
        }
        addRow(rows, getString(R.string.section_favorites), favorites.size) { rail ->
            favorites.forEach { rail.addView(favoriteCard(rail, it)) }
        }
    }

    private fun favoriteCard(
        parent: ViewGroup,
        item: com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity,
    ): View {
        val card = uiInflater.inflate(R.layout.item_phone_poster, parent, false)
        card.findViewById<TextView>(R.id.phone_poster_title).text = item.name
        card.findViewById<TextView>(R.id.phone_poster_meta).isVisible = false
        Glide.with(this).load(item.iconUrl).centerCrop()
            .into(card.findViewById<ImageView>(R.id.phone_poster_image))
        card.setOnClickListener { openFavorite(item) }
        return card
    }

    /**
     * A favourite carries only a stream id and a type, so it goes to the detail page for that
     * type and lets that screen load the rest — the same route the browse grids use.
     */
    private fun openFavorite(item: com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity) {
        val type = when (item.type) {
            "series" -> com.tvonnet.debridxtreamiptv.data.model.ContentType.SERIES
            "live" -> com.tvonnet.debridxtreamiptv.data.model.ContentType.LIVE_TV
            else -> com.tvonnet.debridxtreamiptv.data.model.ContentType.MOVIE
        }
        router.openFeaturedDetails(
            FeaturedItem(
                contentId = item.streamId,
                contentType = type,
                title = item.name,
                posterUrl = item.iconUrl,
                backdropUrl = item.iconUrl,
                description = null,
                streamUrl = null,
            )
        )
    }

    private fun bindHero(view: View, item: FeaturedItem) {
        view.findViewById<TextView>(R.id.phone_hero_title)?.text = item.title
        view.findViewById<TextView>(R.id.phone_hero_synopsis)?.text =
            item.description ?: getString(R.string.c_hero_desc_fallback)
        view.findViewById<TextView>(R.id.phone_hero_rating)?.apply {
            val r = item.rating?.takeIf { it.isNotBlank() }
            isVisible = r != null
            text = r?.let { "★ $it" }
        }
        // The catalogue gives us no runtime, year or codec, so these two stay hidden rather than
        // rendering as an empty pill and a blank gap.
        view.findViewById<View>(R.id.phone_hero_meta)?.isVisible = false
        view.findViewById<View>(R.id.phone_hero_quality)?.isVisible = false
        view.findViewById<ImageView>(R.id.phone_hero_backdrop)?.let { img ->
            Glide.with(this).load(item.backdropUrl ?: item.posterUrl).centerCrop().into(img)
        }
        view.findViewById<View>(R.id.phone_hero_play)?.setOnClickListener {
            router.onFeaturedItemClick(item)
        }
        view.findViewById<View>(R.id.phone_hero_info)?.setOnClickListener {
            router.openFeaturedDetails(item)
        }
        bindHeroSave(view, item)
    }

    /**
     * The bookmark writes to the same favourites table the detail pages use, so what it saves
     * appears in the Favorites row below without any extra plumbing — and a decorative button
     * that does nothing is worse than no button.
     */
    private fun bindHeroSave(view: View, item: FeaturedItem) {
        val save = view.findViewById<ImageView>(R.id.phone_hero_save) ?: return
        val saved = favorites.any { it.streamId == item.contentId }
        save.setColorFilter(color(if (saved) R.color.phone_cyan else R.color.phone_text_primary))
        save.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (saved) {
                    favoriteDao.deleteFavoriteByStreamId(item.contentId)
                } else {
                    favoriteDao.insertFavorite(
                        com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity(
                            streamId = item.contentId,
                            type = if (item.contentType ==
                                com.tvonnet.debridxtreamiptv.data.model.ContentType.SERIES
                            ) "series" else "vod",
                            name = item.title,
                            iconUrl = item.posterUrl ?: item.backdropUrl,
                        )
                    )
                }
            }
        }
    }

    /** Header + horizontally scrolling rail, the repeated block the whole screen is made of. */
    private fun addRow(parent: LinearLayout, title: String, count: Int, fill: (LinearLayout) -> Unit) {
        val block = uiInflater.inflate(R.layout.item_phone_row, parent, false)
        block.findViewById<TextView>(R.id.phone_row_title).text = title
        block.findViewById<TextView>(R.id.phone_row_count).text = count.toString()
        fill(block.findViewById(R.id.phone_row_rail))
        parent.addView(block)
    }

    private fun continueCard(parent: ViewGroup, item: ContinueWatchingItem): View {
        val card = uiInflater.inflate(R.layout.item_phone_continue, parent, false)
        card.findViewById<TextView>(R.id.phone_cw_title).text = item.title
        card.findViewById<TextView>(R.id.phone_cw_time).text =
            "${clock(item.currentPosition)} / ${clock(item.totalDuration)}"
        card.findViewById<ProgressBar>(R.id.phone_cw_progress).progress =
            if (item.totalDuration > 0) {
                ((item.currentPosition * 100) / item.totalDuration).toInt().coerceIn(0, 100)
            } else 0
        card.findViewById<TextView>(R.id.phone_cw_sub).isVisible = false
        Glide.with(this).load(item.backdropUrl ?: item.posterUrl).centerCrop()
            .into(card.findViewById<ImageView>(R.id.phone_cw_image))
        card.findViewById<View>(R.id.phone_cw_tile).setOnClickListener { router.onContinueWatchingItemClick(item) }
        return card
    }

    private fun posterCard(parent: ViewGroup, item: FeaturedItem): View {
        val card = uiInflater.inflate(R.layout.item_phone_poster, parent, false)
        card.findViewById<TextView>(R.id.phone_poster_title).text = item.title
        card.findViewById<TextView>(R.id.phone_poster_meta).isVisible = false
        Glide.with(this).load(item.posterUrl ?: item.backdropUrl).centerCrop()
            .into(card.findViewById<ImageView>(R.id.phone_poster_image))
        card.setOnClickListener { router.openFeaturedDetails(item) }
        return card
    }

    private fun channelCard(parent: ViewGroup, item: RecentLiveChannelItem): View {
        val card = uiInflater.inflate(R.layout.item_phone_channel, parent, false)
        card.findViewById<TextView>(R.id.phone_channel_name).text = item.channelName
        Glide.with(this).load(item.channelLogo).fitCenter()
            .into(card.findViewById<ImageView>(R.id.phone_channel_logo))
        card.setOnClickListener { router.onRecentLiveItemClick(item) }
        return card
    }

    override fun onDestroyView() {
        super.onDestroyView()
        router.cleanup()
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private fun initializeRepository() {
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        if (serverUrl != null && username != null && password != null) {
            repository.initialize(serverUrl, username, password)
        }
    }

    private fun color(res: Int) = androidx.core.content.ContextCompat.getColor(requireContext(), res)

    private fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        // ROOT, not the default locale: this is a clock readout, and a locale with non-Western
        // digits would render it in a numeral set the rest of the card is not using.
        return if (h > 0) {
            String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
        }
    }

    private companion object {
        const val HERO_ROTATE_MS = 7_000L
        const val HERO_COUNT = 3
    }
}
