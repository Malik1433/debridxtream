package com.tvonnet.debridxtreamiptv.ui.live.guide

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.databinding.FragmentLiveTvGuideBinding

/**
 * C7: the guide's two navigation rows — the category chips (with the search chip) and the day tabs.
 *
 * Both are built once and then only re-styled, because the state flow re-emits on every category or
 * day change and rebuilding the views would destroy focus mid-navigation. The chips carry two
 * details that are easy to lose and immediately visible on a TV:
 *  - **explicit `nextFocusLeft/RightId` wiring.** Inside a `HorizontalScrollView`, an off-screen
 *    chip otherwise needs *two* D-pad presses — one to scroll it into view, one to focus it.
 *  - **a single sliding highlight pill** instead of a per-chip focus background. It moves with
 *    `translationX/Y` + `scaleX/Y` around a top-left pivot, never by rewriting `layoutParams` per
 *    frame — the latter forces a full layout pass on every animation frame.
 *
 * Bodies moved verbatim from `LiveTvGuideFragment`.
 */
internal class GuideChipsController(
    private val fragment: Fragment,
    private val binding: FragmentLiveTvGuideBinding,
    private val onCategorySelected: (String) -> Unit,
    private val onDaySelected: (Int) -> Unit,
    private val onSearchClicked: () -> Unit,
) {
    private companion object {
        const val TAG_SEARCH_CHIP = "__guide_search__"
    }

    private var highlightAnim: ValueAnimator? = null
    private var highlightShown = false

    private var builtCategoriesSignature: String? = null
    private var dayTabsBuilt = false

    private val dayLabels = listOf("Today", "Tomorrow", "+2 day", "+3 day")

    /** Called from the state observer; both builders are no-ops once their input is unchanged. */
    fun bind(state: GuideUiState) {
        buildChipsIfNeeded(state)
        buildDayTabsIfNeeded(state)
        highlightChips(state.selectedCategoryId)
        highlightDayTabs(state.dayIndex)
    }

    /** Where the grid sends focus when the user exits it leftwards or upwards. */
    fun focusSelectedChip(selectedCategoryId: String) {
        binding.chipsContainer.getChildAt(indexOfChip(selectedCategoryId))?.requestFocus()
    }

    // ── Category chips ─────────────────────────────────────────────────────────

    private fun buildChipsIfNeeded(state: GuideUiState) {
        val signature = state.categories.joinToString("|") { it.id }
        if (signature == builtCategoriesSignature) return
        builtCategoriesSignature = signature
        binding.chipsContainer.removeAllViews()

        val chips = ArrayList<TextView>()
        // Unified search — always the first chip; OK opens the search overlay.
        chips.add(addChip(searchChip()))
        state.categories.forEach { cat -> chips.add(addChip(categoryChip(cat, state.selectedCategoryId))) }
        wireChipFocusOrder(chips)
    }

    private fun addChip(chip: TextView): TextView {
        binding.chipsContainer.addView(chip)
        return chip
    }

    private fun searchChip(): TextView = baseChip().apply {
        text = "🔍  Search"
        tag = TAG_SEARCH_CHIP
        setOnClickListener { onSearchClicked() }
    }

    private fun categoryChip(cat: GuideCategory, selectedCategoryId: String): TextView = baseChip().apply {
        text = if (cat.count >= 0) "${cat.name}  ${cat.count}" else cat.name
        tag = cat.id
        isSelected = cat.id == selectedCategoryId
        setOnClickListener { onCategorySelected(cat.id) }
    }

    private fun baseChip(): TextView = TextView(fragment.requireContext()).apply {
        id = View.generateViewId()
        textSize = 13f
        setPadding(dp(16), dp(9), dp(16), dp(9))
        isFocusable = true
        isSingleLine = true
        // Static outline; the visible focus is the single sliding highlight pill.
        background = chipOutline()
        setTextColor(pillTextColors())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(10) }
        // Glide the shared highlight onto this chip; hide it when focus leaves the row.
        setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) moveHighlightTo(v)
            else v.post { if (binding.chipsContainer.findFocus() == null) hideHighlight() }
        }
    }

    /**
     * Explicit L/R focus order → a single D-pad press always lands on the sibling.
     * (A HorizontalScrollView otherwise needs two presses to reveal an off-screen chip.)
     */
    private fun wireChipFocusOrder(chips: List<TextView>) {
        for (i in chips.indices) {
            if (i + 1 < chips.size) chips[i].nextFocusRightId = chips[i + 1].id
            if (i - 1 >= 0) chips[i].nextFocusLeftId = chips[i - 1].id
            chips[i].nextFocusDownId = R.id.epg_grid
        }
    }

    private fun highlightChips(selectedId: String) {
        for (i in 0 until binding.chipsContainer.childCount) {
            val chip = binding.chipsContainer.getChildAt(i) as? TextView ?: continue
            chip.isSelected = chip.tag == selectedId
        }
    }

    private fun indexOfChip(selectedId: String): Int {
        for (i in 0 until binding.chipsContainer.childCount) {
            if ((binding.chipsContainer.getChildAt(i) as? TextView)?.tag == selectedId) return i
        }
        return 0
    }

    // ── The sliding highlight pill ─────────────────────────────────────────────

    /**
     * Glide + morph the single highlight pill onto [target]. First appearance snaps in;
     * subsequent moves animate position AND size so it flows from one chip into the next.
     */
    private fun moveHighlightTo(target: View) {
        val hl = binding.chipHighlight
        if (target.width == 0) { target.post { moveHighlightTo(target) }; return }
        val tx = target.left.toFloat()
        val ty = target.top.toFloat()
        val tw = target.width
        val th = target.height
        hl.visibility = View.VISIBLE
        hl.alpha = 1f
        if (!highlightShown) {
            highlightShown = true
            hl.pivotX = 0f
            hl.pivotY = 0f
            hl.scaleX = 1f
            hl.scaleY = 1f
            hl.layoutParams = hl.layoutParams.apply { width = tw; height = th }
            hl.translationX = tx
            hl.translationY = ty
            return
        }
        // Grow/shrink via scaleX/Y around the top-left pivot instead of writing layoutParams every
        // frame — a per-frame layoutParams mutation forces a full layout pass each frame, while
        // scale is a compositor-only transform. Pivot (0,0) keeps the box anchored at (tx,ty), so
        // the geometry matches the old translation-positioned top-left exactly.
        highlightAnim?.cancel()
        val startW = hl.width * hl.scaleX
        val startH = hl.height * hl.scaleY
        hl.pivotX = 0f
        hl.pivotY = 0f
        hl.layoutParams = hl.layoutParams.apply { width = tw; height = th }
        hl.scaleX = if (tw > 0) startW / tw else 1f
        hl.scaleY = if (th > 0) startH / th else 1f
        hl.animate().translationX(tx).translationY(ty).scaleX(1f).scaleY(1f)
            .setDuration(300).setInterpolator(OvershootInterpolator(0.6f)).start()
    }

    private fun hideHighlight() {
        highlightShown = false
        highlightAnim?.cancel()
        binding.chipHighlight.animate().alpha(0f).setDuration(150).withEndAction {
            if (binding.chipsContainer.findFocus() == null) {
                binding.chipHighlight.visibility = View.INVISIBLE
            }
        }.start()
    }

    // ── Day tabs ───────────────────────────────────────────────────────────────

    private fun buildDayTabsIfNeeded(state: GuideUiState) {
        if (dayTabsBuilt) return
        dayTabsBuilt = true
        binding.dayTabs.removeAllViews()
        dayLabels.forEachIndexed { index, label ->
            val tab = TextView(fragment.requireContext()).apply {
                text = label
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isFocusable = true
                isSingleLine = true
                background = pillStateBackground(dp(8).toFloat())
                setTextColor(pillTextColors())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(4) }
                isSelected = index == state.dayIndex
                setOnClickListener { onDaySelected(index) }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f)
                        .setDuration(160).start()
                }
            }
            binding.dayTabs.addView(tab)
        }
    }

    private fun highlightDayTabs(selected: Int) {
        for (i in 0 until binding.dayTabs.childCount) {
            val tab = binding.dayTabs.getChildAt(i) as? TextView ?: continue
            tab.isSelected = i == selected
        }
    }

    // ── Shared chrome ──────────────────────────────────────────────────────────

    /** Pill background with distinct focused / selected / default states. */
    private fun pillStateBackground(radius: Float): StateListDrawable {
        fun pill(bg: Int, border: Int) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color(bg))
            setStroke(dp(2), color(border))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(R.color.epg_chip_active_bg, R.color.epg_cyan))
            addState(intArrayOf(android.R.attr.state_selected), pill(R.color.epg_chip_active_bg, R.color.epg_chip_active_border))
            addState(intArrayOf(), pill(R.color.epg_chip_bg, R.color.epg_chip_border))
        }
    }

    private fun pillTextColors(): ColorStateList {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf()
        )
        val colors = intArrayOf(color(R.color.epg_cyan), color(R.color.epg_cyan), color(R.color.epg_text_secondary))
        return ColorStateList(states, colors)
    }

    /** Static chip outline; the moving highlight provides the focus indicator. */
    private fun chipOutline() = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(color(R.color.epg_chip_bg))
        setStroke(dp(1), color(R.color.epg_chip_border))
    }

    private fun dp(v: Int) = (v * fragment.resources.displayMetrics.density).toInt()
    private fun color(res: Int) = ContextCompat.getColor(fragment.requireContext(), res)
}
