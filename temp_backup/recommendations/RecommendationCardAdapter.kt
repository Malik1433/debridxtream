package com.tvonnet.debridxtreamiptv.ui.recommendations

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.recommendation.RecommendationResult
import com.tvonnet.debridxtreamiptv.util.loadPosterOrPlaceholder

/**
 * TV-optimized RecyclerView adapter for recommendation cards
 *
 * Features:
 * - Different card types for various recommendation categories
 * - Focus management for D-pad navigation
 * - Visual feedback and animations
 * - Progress indicators for continue watching
 * - Score badges and algorithm indicators
 */
class RecommendationCardAdapter(
    private var recommendations: List<RecommendationResult>,
    private val onItemClick: (RecommendationResult) -> Unit,
    private val onLongClick: (RecommendationResult) -> Boolean = { false },
    private val cardType: CardType = CardType.DEFAULT
) : RecyclerView.Adapter<RecommendationCardAdapter.RecommendationViewHolder>() {

    enum class CardType {
        DEFAULT,
        PERSONALIZED,
        TRENDING,
        POPULAR,
        SIMILAR,
        CONTINUE_WATCHING
    }

    fun updateRecommendations(newRecommendations: List<RecommendationResult>) {
        recommendations = newRecommendations
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecommendationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recommendation_card, parent, false)
        return RecommendationViewHolder(view, cardType)
    }

    override fun onBindViewHolder(holder: RecommendationViewHolder, position: Int) {
        holder.bind(recommendations[position], onItemClick, onLongClick)
    }

    override fun getItemCount() = recommendations.size

    /**
     * ViewHolder for recommendation cards with TV-optimized focus handling
     */
    class RecommendationViewHolder(
        itemView: View,
        private val cardType: CardType
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivPoster: ImageView = itemView.findViewById(R.id.iv_recommendation_poster)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_recommendation_title)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tv_recommendation_subtitle)
        private val tvScore: TextView = itemView.findViewById(R.id.tv_recommendation_score)
        private val tvReason: TextView = itemView.findViewById(R.id.tv_recommendation_reason)
        private val progressIndicator: View = itemView.findViewById(R.id.progress_indicator)
        private val progressText: TextView = itemView.findViewById(R.id.tv_progress_text)
        private val badgeAlgorithm: TextView = itemView.findViewById(R.id.tv_algorithm_badge)
        private val ivNewBadge: ImageView = itemView.findViewById(R.id.iv_new_badge)
        private val ivTrendingBadge: ImageView = itemView.findViewById(R.id.iv_trending_badge)
        private val focusOverlay: View = itemView.findViewById(R.id.focus_overlay)

        fun bind(
            recommendation: RecommendationResult,
            onClick: (RecommendationResult) -> Unit,
            onLongClick: (RecommendationResult) -> Boolean
        ) {
            // Set title and subtitle
            tvTitle.text = recommendation.contentId // Would be replaced with actual content title

            // Set subtitle based on card type and metadata
            val subtitle = when (cardType) {
                CardType.CONTINUE_WATCHING -> {
                    val progress = (recommendation.metadata["watch_progress"] as? Float)?.times(100)?.toInt() ?: 0
                    "Progress: $progress%"
                }
                CardType.PERSONALIZED -> "Recommended for you"
                CardType.TRENDING -> "Trending now"
                CardType.POPULAR -> "Popular choice"
                CardType.SIMILAR -> "Similar to your favorites"
                else -> recommendation.reason
            }
            tvSubtitle.text = subtitle

            // Set score indicator
            val scorePercentage = (recommendation.score * 100).toInt()
            tvScore.text = "$scorePercentage%"

            // Set recommendation reason
            tvReason.text = recommendation.reason

            // Load poster image
            // In a real implementation, this would load the actual content poster
            ivPoster.loadPosterOrPlaceholder(null)

            // Show progress indicator for continue watching
            if (cardType == CardType.CONTINUE_WATCHING) {
                val progress = recommendation.metadata["watch_progress"] as? Float ?: 0f
                progressIndicator.isVisible = true
                progressText.isVisible = true
                progressText.text = "${(progress * 100).toInt()}% complete"

                // Set progress width (this would be done with proper layout)
                progressIndicator.scaleX = progress
            } else {
                progressIndicator.isGone = true
                progressText.isGone = true
            }

            // Show algorithm badge
            badgeAlgorithm.isVisible = cardType != CardType.DEFAULT
            badgeAlgorithm.text = when (recommendation.algorithmType) {
                "personalized" -> "For You"
                "trending" -> "Trending"
                "popular" -> "Popular"
                "collaborative" -> "Similar Users"
                "content_based" -> "Similar Content"
                "continue_watching" -> "Continue"
                else -> "Recommended"
            }

            // Show special badges based on recommendation type
            ivNewBadge.isVisible = recommendation.metadata.containsKey("is_new") == true
            ivTrendingBadge.isVisible = recommendation.algorithmType == "trending"

            // Set up click listeners
            itemView.setOnClickListener {
                onClick(recommendation)
            }

            itemView.setOnLongClickListener {
                onLongClick(recommendation)
            }

            // Set up focus handling for TV navigation
            setupFocusHandling()
        }

        private fun setupFocusHandling() {
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // Scale up slightly and show overlay when focused
                    itemView.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(150)
                        .start()

                    focusOverlay.isVisible = true

                    // Announce content for accessibility
                    itemView.announceForAccessibility(
                        "${tvTitle.text}, ${tvSubtitle.text}. ${tvReason.text}"
                    )
                } else {
                    // Scale back down when focus is lost
                    itemView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .start()

                    focusOverlay.isVisible = false
                }
            }

            // Ensure proper focus behavior for TV
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true

            // Set next focus directions for better D-pad navigation
            itemView.nextFocusLeftId = R.id.focus_left
            itemView.nextFocusRightId = R.id.focus_right
            itemView.nextFocusUpId = R.id.focus_up
            itemView.nextFocusDownId = R.id.focus_down
        }
    }
}

/**
 * Factory for creating different types of recommendation card adapters
 */
object RecommendationCardAdapterFactory {

    fun createPersonalizedAdapter(
        recommendations: List<RecommendationResult>,
        onItemClick: (RecommendationResult) -> Unit
    ) = RecommendationCardAdapter(
        recommendations = recommendations,
        onItemClick = onItemClick,
        cardType = RecommendationCardAdapter.CardType.PERSONALIZED
    )

    fun createTrendingAdapter(
        recommendations: List<RecommendationResult>,
        onItemClick: (RecommendationResult) -> Unit
    ) = RecommendationCardAdapter(
        recommendations = recommendations,
        onItemClick = onItemClick,
        cardType = RecommendationCardAdapter.CardType.TRENDING
    )

    fun createPopularAdapter(
        recommendations: List<RecommendationResult>,
        onItemClick: (RecommendationResult) -> Unit
    ) = RecommendationCardAdapter(
        recommendations = recommendations,
        onItemClick = onItemClick,
        cardType = RecommendationCardAdapter.CardType.POPULAR
    )

    fun createSimilarAdapter(
        recommendations: List<RecommendationResult>,
        onItemClick: (RecommendationResult) -> Unit
    ) = RecommendationCardAdapter(
        recommendations = recommendations,
        onItemClick = onItemClick,
        cardType = RecommendationCardAdapter.CardType.SIMILAR
    )

    fun createContinueWatchingAdapter(
        recommendations: List<RecommendationResult>,
        onItemClick: (RecommendationResult) -> Unit
    ) = RecommendationCardAdapter(
        recommendations = recommendations,
        onItemClick = onItemClick,
        cardType = RecommendationCardAdapter.CardType.CONTINUE_WATCHING
    )
}