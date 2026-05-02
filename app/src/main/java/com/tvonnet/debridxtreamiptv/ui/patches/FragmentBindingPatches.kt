// =====================================================================
// FragmentBindingPatches.kt
//
// Minimal wiring snippets to drop into existing fragments after switching
// their layouts to the cinematic versions. These are NOT full fragment
// rewrites — they're surgical patches showing the new view IDs and how
// to apply the focus/accent helpers from CinematicTheme.kt.
//
// Each block is annotated with the source fragment it targets. Copy the
// relevant block into your existing fragment, replacing the old wiring.
// =====================================================================
package com.tvonnet.debridxtreamiptv.ui.patches

/* =====================================================================
   HomeFragment.kt
   Replaces: setContentView(R.layout.fragment_home) ->
             setContentView(R.layout.fragment_home_cinematic)
   ===================================================================== */
//
// override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//     super.onViewCreated(view, savedInstanceState)
//
//     // Top section header (CONTINUE WATCHING)
//     view.findViewById<TextView>(R.id.tv_section_continue).applySectionHeader()
//     view.findViewById<TextView>(R.id.tv_section_top10).applySectionHeader()
//     view.findViewById<TextView>(R.id.tv_section_movies).applySectionHeader()
//     view.findViewById<TextView>(R.id.tv_section_series).applySectionHeader()
//
//     // Hero CTA
//     val btnPlayHero = view.findViewById<MaterialButton>(R.id.btn_play_hero)
//     btnPlayHero.setOnClickListener { /* existing play handler */ }
//
//     // Hero overline ("FEATURED TONIGHT")
//     view.findViewById<TextView>(R.id.tv_hero_overline).applyGoldAccent()
//
//     // RecyclerView focus lift wiring (in your adapter onBindViewHolder):
//     // holder.itemView.setOnFocusChangeListener { v, hasFocus -> v.applyFocusLift(hasFocus) }
// }

/* =====================================================================
   LoginFragment.kt
   Layout -> R.layout.fragment_login (cinematic version).
   ===================================================================== */
//
// override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//     super.onViewCreated(view, savedInstanceState)
//
//     val etUsername = view.findViewById<TextInputEditText>(R.id.et_username)
//     val etPassword = view.findViewById<TextInputEditText>(R.id.et_password)
//     val etServer   = view.findViewById<TextInputEditText>(R.id.et_server_url)
//     val btnLogin   = view.findViewById<MaterialButton>(R.id.btn_login_primary)
//     val btnDemo    = view.findViewById<MaterialButton>(R.id.btn_login_demo)
//
//     btnLogin.setOnClickListener { viewModel.login(
//         etServer.text?.toString().orEmpty(),
//         etUsername.text?.toString().orEmpty(),
//         etPassword.text?.toString().orEmpty()
//     ) }
//     btnDemo.setOnClickListener { viewModel.loadDemoCredentials() }
// }

/* =====================================================================
   LiveTvFragment.kt
   Layout -> R.layout.fragment_live (cinematic).
   The new layout adds a now-playing PREVIEW card on the right
   (id: card_now_playing) and demotes the channel grid to a side rail.
   ===================================================================== */
//
// override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//     super.onViewCreated(view, savedInstanceState)
//
//     val rvCategories = view.findViewById<RecyclerView>(R.id.rv_categories)
//     val rvChannels   = view.findViewById<RecyclerView>(R.id.rv_channels)
//     val tvNowTitle   = view.findViewById<TextView>(R.id.tv_now_playing_title)
//     val tvNowMeta    = view.findViewById<TextView>(R.id.tv_now_playing_meta)
//     val pbNowProgress = view.findViewById<ProgressBar>(R.id.pb_now_playing)
//
//     viewModel.selectedChannel.observe(viewLifecycleOwner) { ch ->
//         tvNowTitle.text = ch.programTitle
//         tvNowMeta.text  = "${ch.name} • ${ch.timeRange}"
//         pbNowProgress.progress = ch.progressPercent
//     }
// }

/* =====================================================================
   MovieDetailFragment.kt / SeriesDetailFragment.kt
   Layout -> R.layout.fragment_movie_detail / fragment_series_detail
   New IDs:
     - iv_backdrop_hero, iv_poster_hero
     - tv_title_hero, tv_year_runtime, tv_overline_genre
     - btn_play_primary, btn_trailer_secondary, btn_my_list_secondary
     - rv_cast, rv_similar
     - (series only) rv_seasons, rv_episodes, tv_season_title
   ===================================================================== */

/* =====================================================================
   SettingsFragment.kt
   Layout -> R.layout.fragment_settings.
   Each setting row uses cin_item_settings.xml; bind icon + label + value.
   ===================================================================== */

/* =====================================================================
   PlayerFragment.kt
   Layout -> R.layout.fragment_player.
   The new player overlay fades in/out via existing controlsVisibility logic;
   no behavior change is needed — only the view IDs map to the new chrome.
   New IDs:
     - tv_player_title, tv_player_subtitle
     - sb_player (SeekBar with cin_seekbar_track / cin_seekbar_thumb)
     - btn_player_play, btn_player_skip_back, btn_player_skip_fwd
     - btn_player_subtitles, btn_player_audio, btn_player_quality
   ===================================================================== */
