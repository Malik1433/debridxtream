import sys
path = 'app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/DebridRowsAdapter.kt'
content = open(path, encoding='utf-8').read()

content = content.replace('private const val VIEW_TYPE_SKELETON = 1', 'private const val VIEW_TYPE_SKELETON = 1\n        private const val VIEW_TYPE_SEE_ALL = 2')

old_getitemviewtype = '''    override fun getItemViewType(position: Int): Int {
        // Use standard list size for check
        if (canLoadMore && position == super.getItemCount()) {
            return VIEW_TYPE_SKELETON // This is now the "Sentinel"
        }
        return VIEW_TYPE_ITEM
    }'''
new_getitemviewtype = '''    override fun getItemViewType(position: Int): Int {
        if (canLoadMore && position == super.getItemCount()) {
            return VIEW_TYPE_SKELETON
        }
        val item = getItem(position)
        if (item.id.startsWith("SEE_ALL_")) {
            return VIEW_TYPE_SEE_ALL
        }
        return VIEW_TYPE_ITEM
    }'''
content = content.replace(old_getitemviewtype, new_getitemviewtype)

old_oncreate = '''    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SKELETON) {
            val view = inflater.inflate(R.layout.item_debrid_loading, parent, false)
            DebridLoadingViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_debrid_content, parent, false)
            DebridItemViewHolder(view, onLeftBoundary)
        }
    }'''
new_oncreate = '''    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SKELETON -> {
                val view = inflater.inflate(R.layout.item_debrid_loading, parent, false)
                DebridLoadingViewHolder(view)
            }
            VIEW_TYPE_SEE_ALL -> {
                val view = inflater.inflate(R.layout.item_debrid_see_all, parent, false)
                DebridSeeAllViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_debrid_content, parent, false)
                DebridItemViewHolder(view, onLeftBoundary)
            }
        }
    }'''
content = content.replace(old_oncreate, new_oncreate)

old_onbind = '''    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is DebridItemViewHolder) {
            // Safe check for index
            if (position < super.getItemCount()) {
                val item = getItem(position)
                holder.bind(item, onItemClick, onItemFocused)
            }
        } else if (holder is DebridLoadingViewHolder) {
            holder.bind()
            // Trigger load when bound (and it will also be focusable)
            onPrefetch()
        }
    }'''
new_onbind = '''    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is DebridItemViewHolder) {
            if (position < super.getItemCount()) {
                val item = getItem(position)
                holder.bind(item, onItemClick, onItemFocused)
            }
        } else if (holder is DebridSeeAllViewHolder) {
            holder.bind(rowId)
        } else if (holder is DebridLoadingViewHolder) {
            holder.bind()
            onPrefetch()
        }
    }'''
content = content.replace(old_onbind, new_onbind)

content = content.replace('private var canLoadMore = false', 'private var canLoadMore = false\n    var rowId: String = ""')
content = content.replace('currentLoadMoreCallback = { onRowLoadMore(row.id) }', 'currentLoadMoreCallback = { onRowLoadMore(row.id) }\n        itemsAdapter.rowId = row.id')

see_all_class = '''
class DebridSeeAllViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
    fun bind(rowId: String) {
        itemView.setOnClickListener {
            val type = if (rowId.contains("series") || rowId.contains("tv")) "series" else "movie"
            val catalogue = when {
                rowId.contains("top_rated") -> "top_rated"
                rowId.contains("new") -> "newest"
                else -> "popular"
            }
            val intent = android.content.Intent(itemView.context, com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity::class.java).apply {
                putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_TYPE, type)
                putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_CATALOGUE, catalogue)
                
                when {
                    rowId.contains("bollywood") -> {
                        putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_LANGUAGE, "hi")
                        putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_REGION, "IN")
                        putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_RELEASE_DATE_GTE, "2024-01-01")
                    }
                    rowId.contains("punjabi") -> {
                        putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_LANGUAGE, "pa")
                        putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_REGION, "IN")
                        putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_RELEASE_DATE_GTE, "2024-01-01")
                    }
                    rowId.contains("tamil") -> {
                        putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_LANGUAGE, "ta")
                        putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_REGION, "IN")
                        putExtra(com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.EXTRA_RELEASE_DATE_GTE, "2024-01-01")
                    }
                }
            }
            itemView.context.startActivity(intent)
        }
    }
}
'''
content += see_all_class

open(path, 'w', encoding='utf-8').write(content)
