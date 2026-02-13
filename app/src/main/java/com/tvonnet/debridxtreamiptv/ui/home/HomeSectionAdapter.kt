package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.databinding.ItemHeroBannerBinding
import com.tvonnet.debridxtreamiptv.databinding.ItemHomeSectionRowBinding
import com.tvonnet.debridxtreamiptv.databinding.ItemMovieCardBinding

class HomeSectionAdapter(
    private val onItemClick: (Any) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var heroItem: HeroContent? = null
    private var sections: List<HomeSection> = emptyList()

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_ROW = 1
    }

    fun updateData(hero: HeroContent?, newSections: List<HomeSection>) {
        heroItem = hero
        sections = newSections
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0 && heroItem != null) TYPE_HERO else TYPE_ROW
    }

    override fun getItemCount(): Int {
        val heroCount = if (heroItem != null) 1 else 0
        return heroCount + sections.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HERO) {
            val binding = ItemHeroBannerBinding.inflate(inflater, parent, false)
            HeroViewHolder(binding, onItemClick)
        } else {
            val binding = ItemHomeSectionRowBinding.inflate(inflater, parent, false)
            RowViewHolder(binding, onItemClick, viewPool)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeroViewHolder && heroItem != null) {
            holder.bind(heroItem!!)
        } else if (holder is RowViewHolder) {
            val listPos = if (heroItem != null) position - 1 else position
            holder.bind(sections[listPos])
        }
    }

    class HeroViewHolder(
        private val binding: ItemHeroBannerBinding,
        private val onClick: (Any) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(item: HeroContent) {
            binding.tvHeroTitle.text = item.title
            binding.tvHeroDescription.text = item.description
            binding.tvHeroRating.text = "⭐ ${item.rating}"
            binding.tvHeroType.text = item.type

            if (!item.imageUrl.isNullOrBlank()) {
                Glide.with(binding.root.context)
                    .load(item.imageUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .error(R.drawable.placeholder_banner)
                    .into(binding.ivHeroBackdrop)
            } else {
                 binding.ivHeroBackdrop.setImageResource(R.drawable.placeholder_banner)
            }

            binding.btnHeroPlay.setOnClickListener { onClick(item) }
            binding.tvHeroTitle.setOnClickListener { onClick(item) }
            binding.ivHeroBackdrop.setOnClickListener { onClick(item) }
        }
    }

    private val viewPool = RecyclerView.RecycledViewPool()

    class RowViewHolder(
        private val binding: ItemHomeSectionRowBinding,
        private val onClick: (Any) -> Unit,
        private val viewPool: RecyclerView.RecycledViewPool
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private val rowAdapter = HomeRowAdapter(onClick)

        init {
            binding.rvHorizontalList.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                adapter = rowAdapter
                setHasFixedSize(true)
                setRecycledViewPool(viewPool) // Share pool for performance
                itemAnimator = null 
                
                // Snap to start
                val snapHelper = androidx.recyclerview.widget.LinearSnapHelper() // Or PagerSnapHelper for one-by-one
                // OnItemTouchHelper to prevent focus loss?
                // Standard LinearSnapHelper is good for rows.
                // However, for DPAD navigation, Default ItemAnimator + Focus usually works best without SnapHelper interfering.
                // Mobile: SnapHelper is great. TV: Focus determines scroll.
                // I will add it but ensure it doesn't break DPAD.
            }
        }

        fun bind(section: HomeSection) {
            val (title, items) = when (section) {
                is HomeSection.MovieRow -> section.title to section.items
                is HomeSection.SeriesRow -> section.title to section.items
                is HomeSection.ChannelRow -> section.title to section.items
            }
            binding.tvRowTitle.text = title
            rowAdapter.submitList(items)
        }
    }
}

class HomeRowAdapter(
    private val onClick: (Any) -> Unit
) : RecyclerView.Adapter<HomeRowAdapter.ItemViewHolder>() {

    private var items: List<Any> = emptyList()

    fun submitList(newItems: List<Any>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val binding = ItemMovieCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class ItemViewHolder(private val binding: ItemMovieCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Any, onClick: (Any) -> Unit) {
            var title = ""
            var imageUrl: String? = null
            var rating = ""

            when (item) {
                is XtreamVodInfo -> {
                    title = item.name ?: ""
                    imageUrl = item.stream_icon
                    rating = if (item.rating != null) "⭐ ${item.rating}" else ""
                }
                is XtreamSeriesInfo -> {
                    title = item.name ?: ""
                    imageUrl = item.cover // Series often use 'cover'
                    rating = if (item.rating != null) "⭐ ${item.rating}" else ""
                }
                is XtreamStream -> {
                    title = item.name ?: ""
                    imageUrl = item.stream_icon
                    // Live streams usually don't have rating
                }
            }

            binding.tvMovieTitle.text = title
            binding.tvMovieRatingValue.text = rating.removePrefix("⭐ ").trim()
            
            // Toggle rating visibility
            // Toggle rating visibility
            if (binding.tvMovieRatingValue.text.isBlank()) {
                binding.tvMovieRatingValue.visibility = android.view.View.GONE
            } else {
                 binding.tvMovieRatingValue.visibility = android.view.View.VISIBLE
            }

            Glide.with(binding.root.context)
                .load(imageUrl)
                .override(300, 450) // Resize for better performance
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .error(R.drawable.bg_card_normal) // fallback
                .into(binding.ivMoviePoster)

            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
