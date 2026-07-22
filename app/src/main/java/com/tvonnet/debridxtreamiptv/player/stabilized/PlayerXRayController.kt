package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R

/**
 * Owns the in-player X-Ray panel (Phase P9): the header toggle, the slide-in/out
 * animation, and rendering the metadata + cast list the ViewModel emits.
 *
 * Extracted verbatim from [PlayerActivity]; the Activity keeps the couplings this
 * panel has to the rest of the screen (episode browser, playback kind) as callbacks
 * instead of the controller reaching back into the Activity.
 */
internal class PlayerXRayController(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val isSeriesPlayback: () -> Boolean,
    private val showEpisodeBrowserIfHidden: () -> Unit,
    private val hideEpisodeBrowserIfVisible: () -> Unit,
    private val fallbackTitle: () -> String?
) {

    private val panel: View?
        get() = activity.findViewById(R.id.view_xray_panel)

    private val switch: SwitchCompat?
        get() = playerView.findViewById(R.id.switch_xray)

    /** Wires the header toggle so it flips the switch and the panel together. */
    fun bindToggle() {
        val xrayToggle = playerView.findViewById<View>(R.id.layout_xray_toggle)
        xrayToggle?.setOnClickListener {
            val isChecked = !(switch?.isChecked ?: false)
            switch?.isChecked = isChecked
            toggle(isChecked)
        }
    }

    fun isPanelVisible(): Boolean = panel?.visibility == View.VISIBLE

    /** Turns the panel off and unchecks the switch — used by PiP and the BACK key. */
    fun collapse() {
        switch?.isChecked = false
        toggle(false)
    }

    fun toggle(show: Boolean) {
        val xrayPanel = panel ?: return
        val isSeries = isSeriesPlayback()

        if (show) {
            xrayPanel.bringToFront()
            xrayPanel.visibility = View.VISIBLE
            xrayPanel.translationX = -xrayPanel.width.toFloat()
            xrayPanel.animate()
                .translationX(0f)
                .setDuration(300)
                .start()

            if (isSeries) {
                showEpisodeBrowserIfHidden()
            } else {
                val rvCast = xrayPanel.findViewById<RecyclerView>(R.id.rv_xray_cast)
                rvCast?.post { rvCast.requestFocus() }
            }
        } else {
            xrayPanel.animate()
                .translationX(-xrayPanel.width.toFloat())
                .setDuration(300)
                .withEndAction { xrayPanel.visibility = View.GONE }
                .start()

            if (isSeries) {
                hideEpisodeBrowserIfVisible()
            }
        }
    }

    /** Renders one metadata emission; a null state clears the panel back to the title. */
    fun render(state: XRayMetadataUiState?) {
        val xrayPanel = panel ?: return
        if (state == null) {
            xrayPanel.findViewById<TextView>(R.id.tv_xray_title)?.text = fallbackTitle()
            xrayPanel.findViewById<TextView>(R.id.tv_xray_plot)?.text = ""
            xrayPanel.findViewById<TextView>(R.id.tv_xray_meta)?.text = ""
            xrayPanel.findViewById<RecyclerView>(R.id.rv_xray_cast)?.adapter = null
            return
        }

        xrayPanel.findViewById<TextView>(R.id.tv_xray_title)?.text = state.title
        xrayPanel.findViewById<TextView>(R.id.tv_xray_plot)?.text = state.overview ?: "No description available."
        xrayPanel.findViewById<TextView>(R.id.tv_xray_meta)?.text = state.meta ?: ""

        val castTitle = xrayPanel.findViewById<TextView>(R.id.tv_xray_cast_title)
        val rvCast = xrayPanel.findViewById<RecyclerView>(R.id.rv_xray_cast)

        if (state.cast.isEmpty()) {
            castTitle?.visibility = View.GONE
            rvCast?.visibility = View.GONE
        } else {
            castTitle?.visibility = View.VISIBLE
            rvCast?.visibility = View.VISIBLE
            rvCast?.adapter = CastAdapter(state.cast)
        }
    }

    private class CastAdapter(
        private val items: List<XRayCastMember>
    ) : RecyclerView.Adapter<CastViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CastViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_player_cast, parent, false)
            return CastViewHolder(view)
        }
        override fun onBindViewHolder(holder: CastViewHolder, position: Int) {
            holder.bind(items[position])
        }
        override fun getItemCount(): Int = items.size
    }

    private class CastViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivAvatar: ImageView = view.findViewById(R.id.iv_cast_avatar)
        private val tvName: TextView = view.findViewById(R.id.tv_cast_name)
        private val tvCharacter: TextView = view.findViewById(R.id.tv_cast_character)

        init {
            itemView.setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.1f else 1.0f
                v.scaleY = if (hasFocus) 1.1f else 1.0f
            }
        }

        fun bind(item: XRayCastMember) {
            tvName.text = item.name
            tvCharacter.text = item.character
            Glide.with(ivAvatar)
                .load(item.avatarUrl)
                .placeholder(R.drawable.ic_person_placeholder)
                .error(R.drawable.ic_person_placeholder)
                .circleCrop()
                .into(ivAvatar)
        }
    }
}
