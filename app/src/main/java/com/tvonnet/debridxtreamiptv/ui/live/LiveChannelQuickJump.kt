package com.tvonnet.debridxtreamiptv.ui.live

import com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner

/**
 * The **decisions** behind the channel grid's D-pad movement and quick-jump, lifted out of
 * [LiveChannelFocusController] (roadmap B3).
 *
 * The controller itself is view-bound — it needs a Fragment, a RecyclerView, a paging adapter and
 * the `FocusCoordinator` gate — so nothing inside it could be tested. What actually goes wrong on a
 * remote, though, is arithmetic and scanning: where does the next row land when D-pad DOWN is held
 * faster than focus can settle, which channel does "1-4-2" mean, and which row does typing "b" jump
 * to. Those are pure, and they live here.
 *
 * Behaviour-preserving: every function is the old inline expression, moved verbatim.
 */
object LiveChannelQuickJump {

    /** Longest number-zap the buffer accepts before it starts over. */
    const val MAX_DIGITS = 4

    /**
     * Where a D-pad step lands. [base] is the *pending* position when a move is already in flight —
     * holding DOWN queues steps faster than focus can settle, and counting from the settled row
     * instead would drop every keypress after the first.
     */
    fun targetFor(base: Int, delta: Int, itemCount: Int): Int? {
        if (itemCount <= 0) return null
        val target = (base + delta).coerceIn(0, itemCount - 1)
        return target.takeIf { it != base }
    }

    /**
     * Number-zap accumulation. Returns the new buffer, or null when the digit is ignored.
     * A leading zero is ignored (there is no channel 0), and a fifth digit starts a new number
     * rather than silently extending an unreachable one.
     */
    fun appendDigit(buffer: String, digit: Int): String? {
        if (buffer.isEmpty() && digit == 0) return null
        val base = if (buffer.length >= MAX_DIGITS) "" else buffer
        return base + digit
    }

    /** Channel numbers are 1-based on the remote, positions are 0-based in the list. */
    fun positionForNumber(number: Int, itemCount: Int): Int? {
        if (itemCount <= 0) return null
        return (number - 1).coerceIn(0, itemCount - 1)
    }

    /**
     * A–Z type-ahead: the next channel whose cleaned name starts with [letter], scanning forward
     * from the row AFTER [fromPosition] and wrapping, so pressing the same letter again advances to
     * the next match instead of sticking.
     */
    fun nextIndexStartingWith(names: List<String?>, fromPosition: Int, letter: Char): Int? {
        val size = names.size
        if (size == 0) return null
        for (offset in 1..size) {
            val idx = (fromPosition + offset).mod(size)
            if (firstLetterOf(names[idx]) == letter) return idx
        }
        return null
    }

    /** First alphabetic character of a channel name, provider tags (e.g. "|UK|") stripped. */
    fun firstLetterOf(name: String?): Char? =
        MediaTitleCleaner.clean(name)
            .firstOrNull { it.isLetter() }
            ?.lowercaseChar()
}
