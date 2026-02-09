package com.tvonnet.debridxtreamiptv.ui.live

import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import java.text.Normalizer
import java.util.Locale

/**
 * Adapter for the vertical sidebar in the Live TV 3-column layout.
 * Shows category icon/flags, titles, and live channel counts.
 */
class SidebarCategoryAdapter(
    categories: List<XtreamCategory>,
    channelCounts: Map<String, Int>,
    initialSelectedCategoryId: String?,
    private val onCategoryClick: (String) -> Unit,
    private val onCategoryFocused: ((String, Int) -> Unit)? = null
) : RecyclerView.Adapter<SidebarCategoryViewHolder>() {

    private val categories: MutableList<XtreamCategory> = categories.toMutableList()
    private var selectedCategoryId: String? = initialSelectedCategoryId
    private var channelCounts: Map<String, Int> = channelCounts

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarCategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar_category, parent, false)
        return SidebarCategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: SidebarCategoryViewHolder, position: Int) {
        val category = categories[position]
        val categoryId = category.category_id
        val isSelected = categoryId != null && categoryId == selectedCategoryId
        val count = categoryId?.let { channelCounts[it] }
        holder.bind(category, count, isSelected) { clickedId ->
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition == RecyclerView.NO_POSITION || clickedId.isNullOrBlank()) return@bind
            if (clickedId != selectedCategoryId) {
                val previousId = selectedCategoryId
                selectedCategoryId = clickedId
                previousId?.let { previous ->
                    val previousIndex = categories.indexOfFirst { it.category_id == previous }
                    if (previousIndex != -1) notifyItemChanged(previousIndex)
                }
                notifyItemChanged(adapterPosition)
            }
            onCategoryClick(clickedId)
        }

        holder.itemView.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !categoryId.isNullOrBlank()) {
                onCategoryFocused?.invoke(categoryId, position)
            }
        }
        
    }

    override fun getItemCount() = categories.size

    fun updateCategories(newCategories: List<XtreamCategory>, selectedCategoryId: String?) {
        categories.clear()
        categories.addAll(newCategories)
        this.selectedCategoryId = selectedCategoryId
        notifyDataSetChanged()
    }

    fun updateSelection(categoryId: String?) {
        if (selectedCategoryId == categoryId) return
        val previousId = selectedCategoryId
        selectedCategoryId = categoryId
        previousId?.let { previous ->
            val previousIndex = categories.indexOfFirst { it.category_id == previous }
            if (previousIndex != -1) notifyItemChanged(previousIndex)
        }
        categoryId?.let { newId ->
            val newIndex = categories.indexOfFirst { it.category_id == newId }
            if (newIndex != -1) notifyItemChanged(newIndex)
        }
    }

    fun updateChannelCounts(newCounts: Map<String, Int>) {
        if (channelCounts == newCounts) return
        val oldCounts = channelCounts
        channelCounts = newCounts

        categories.forEachIndexed { index, category ->
            val categoryId = category.category_id ?: return@forEachIndexed
            if (oldCounts[categoryId] != newCounts[categoryId]) {
                notifyItemChanged(index)
            }
        }
    }
}

class SidebarCategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val iconContainer = itemView.findViewById<View>(R.id.category_icon_container)
    private val ivCategoryFlag = itemView.findViewById<ImageView>(R.id.iv_category_flag)
    private val tvCategoryIcon = itemView.findViewById<TextView>(R.id.tv_category_icon)
    private val tvCategoryName = itemView.findViewById<TextView>(R.id.tv_category_name_sidebar)
    private val tvChannelCount = itemView.findViewById<TextView>(R.id.tv_channel_count_sidebar)

    fun bind(
        category: XtreamCategory,
        channelCount: Int?,
        isSelected: Boolean,
        onClick: (String?) -> Unit
    ) {
        val context = itemView.context
        val categoryName = category.category_name?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.live_category_placeholder)
        val countLabel = when {
            channelCount == null -> context.getString(R.string.live_channel_count_placeholder)
            else -> context.getString(R.string.live_channel_count_format, channelCount)
        }

        val flagRes = resolveFlagRes(categoryName)
        if (flagRes != null) {
            ivCategoryFlag.setImageResource(flagRes)
            ivCategoryFlag.visibility = View.VISIBLE
            tvCategoryIcon.visibility = View.GONE
        } else {
            ivCategoryFlag.visibility = View.GONE
            tvCategoryIcon.visibility = View.VISIBLE
            tvCategoryIcon.text = getCategoryIcon(categoryName)
        }
        tvCategoryName.text = categoryName
        tvChannelCount.text = countLabel

        val selectedTextColor = ContextCompat.getColor(context, R.color.tv_sidebar_text_selected)
        val normalTextColor = ContextCompat.getColor(context, R.color.tv_sidebar_text_normal)
        val iconNormalColor = ContextCompat.getColor(context, R.color.tv_sidebar_text_normal)
        val nameColor = if (isSelected) selectedTextColor else ContextCompat.getColor(context, R.color.tv_text_primary)
        val countColor = if (isSelected) selectedTextColor else normalTextColor

        tvCategoryName.setTextColor(nameColor)
        tvChannelCount.setTextColor(countColor)
        tvCategoryIcon.setTextColor(if (isSelected) selectedTextColor else iconNormalColor)
        tvCategoryName.setTypeface(tvCategoryName.typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        itemView.isSelected = isSelected

        val resources = context.resources
        val iconSize = resources.getDimensionPixelSize(
            if (isSelected) R.dimen.live_sidebar_icon_size_selected
            else R.dimen.live_sidebar_icon_size
        )
        val iconParams = iconContainer.layoutParams
        if (iconParams.width != iconSize || iconParams.height != iconSize) {
            iconParams.width = iconSize
            iconParams.height = iconSize
            iconContainer.layoutParams = iconParams
        }
        val iconTextSize = if (isSelected) 20f else 18f
        tvCategoryIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconTextSize)

        val nameSize = if (isSelected) 17f else 15f
        val countSize = if (isSelected) 12f else 11f
        tvCategoryName.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSize)
        tvChannelCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, countSize)

        itemView.contentDescription = context.getString(
            R.string.live_sidebar_item_description,
            categoryName,
            countLabel
        )
        itemView.setOnClickListener { onClick(category.category_id) }
    }

    private fun getCategoryIcon(categoryName: String): String {
        val trimmed = categoryName.trim()
        val upper = trimmed.uppercase(Locale.getDefault())
        val lower = trimmed.lowercase(Locale.getDefault())

        val alphaCode = upper.filter { it in 'A'..'Z' }
        if (alphaCode.length == 2) {
            return alphaCode
        }
        return when {
            "sport" in lower -> "\u26BD"
            "news" in lower -> "\uD83D\uDCF0"
            "movie" in lower || "film" in lower -> "\uD83C\uDFAC"
            "entertain" in lower || "show" in lower -> "\u2728"
            "music" in lower -> "\uD83C\uDFB5"
            "kids" in lower || "child" in lower -> "\uD83D\uDC76"
            "doc" in lower -> "\uD83D\uDCD6"
            "relig" in lower -> "\u271D"
            "edu" in lower -> "\uD83C\uDF93"
            "animal" in lower || "nature" in lower -> "\uD83C\uDF0D"
            else -> categoryName
                .split(" ")
                .firstOrNull()
                ?.take(2)
                ?.uppercase(Locale.getDefault())
                ?: "#"
        }
    }

    private fun resolveFlagRes(categoryName: String): Int? {
        val upper = categoryName.trim().uppercase(Locale.getDefault())
        val normalizedUpper = normalizeLabel(upper)
        when {
            "DEUTSCHLAND" in normalizedUpper || "GERMANY" in normalizedUpper -> return R.drawable.flag_de
            "OSTERREICH" in normalizedUpper || "AUSTRIA" in normalizedUpper -> return R.drawable.flag_at
            "SCHWEIZ" in normalizedUpper || "SWITZERLAND" in normalizedUpper -> return R.drawable.flag_ch
            "POLSKA" in normalizedUpper || "POLAND" in normalizedUpper -> return R.drawable.flag_pl
            "ISLAND" in normalizedUpper || "ICELAND" in normalizedUpper -> return R.drawable.flag_is
            "IRLAND" in normalizedUpper || "IRELAND" in normalizedUpper -> return R.drawable.flag_ie
            "BELGIQUE" in normalizedUpper || "BELGIUM" in normalizedUpper -> return R.drawable.flag_be
            "LUXEMBOURG" in normalizedUpper || "LETZEBUERG" in normalizedUpper -> return R.drawable.flag_lu
            "PORTUGAL" in normalizedUpper -> return R.drawable.flag_pt
            "FRANCE" in normalizedUpper -> return R.drawable.flag_fr
            "ITALY" in normalizedUpper -> return R.drawable.flag_it
            "SPAIN" in normalizedUpper || "ESPANA" in normalizedUpper -> return R.drawable.flag_es
            "NETHERLANDS" in normalizedUpper -> return R.drawable.flag_nl
            "RUSSIA" in normalizedUpper -> return R.drawable.flag_ru
            "TURKEY" in normalizedUpper -> return R.drawable.flag_tr
            "NORWAY" in normalizedUpper -> return R.drawable.flag_no
            "CANADA" in normalizedUpper -> return R.drawable.flag_ca
            "HONG KONG" in normalizedUpper || "HONGKONG" in normalizedUpper -> return R.drawable.flag_hk
            "FINLAND" in normalizedUpper -> return R.drawable.flag_fi
            "DENMARK" in normalizedUpper -> return R.drawable.flag_dk
            "SWEDEN" in normalizedUpper -> return R.drawable.flag_se
            "ARGENTINA" in normalizedUpper -> return R.drawable.flag_ar
            "MEXICO" in normalizedUpper -> return R.drawable.flag_mx
            "BRAZIL" in normalizedUpper -> return R.drawable.flag_br
            "MOROCCO" in normalizedUpper -> return R.drawable.flag_ma
            "ALBANIA" in normalizedUpper -> return R.drawable.flag_al
            "AUSTRALIA" in normalizedUpper -> return R.drawable.flag_au
            "JAPAN" in normalizedUpper -> return R.drawable.flag_jp
            "KOREA" in normalizedUpper -> return R.drawable.flag_kr
            "UAE" in normalizedUpper || "UNITED ARAB EMIRATES" in normalizedUpper -> return R.drawable.flag_ae
            "SAUDI" in normalizedUpper || "SAUDI ARABIA" in normalizedUpper -> return R.drawable.flag_sa
            "EGYPT" in normalizedUpper -> return R.drawable.flag_eg
            "CHINA" in normalizedUpper -> return R.drawable.flag_cn
            "EUROPE" in normalizedUpper -> return R.drawable.flag_eu
            "AFRICA" in normalizedUpper -> return R.drawable.flag_af
            "AMERICA" in normalizedUpper -> return R.drawable.flag_am
        }
        val prefixRaw = normalizedUpper.substringBefore('|', "").trim()
        val prefix = if (prefixRaw.length == 3 && prefixRaw.endsWith("I")) {
            prefixRaw.dropLast(1)
        } else {
            prefixRaw
        }
        val normalizedCode = when (prefix) {
            "USA" -> "US"
            "UK" -> "GB"
            "ALB" -> "AL"
            "SW", "SU" -> "SE"
            else -> prefix
        }

        FLAG_RES_BY_CODE[normalizedCode]?.let { return it }

        return when {
            "UNITED STATES" in normalizedUpper || "USA" in normalizedUpper -> R.drawable.flag_us
            "UNITED KINGDOM" in normalizedUpper || "BRITAIN" in normalizedUpper || "ENGLAND" in normalizedUpper -> R.drawable.flag_gb
            else -> null
        }
    }

    private fun normalizeLabel(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    companion object {
        private val FLAG_RES_BY_CODE = mapOf(
            "US" to R.drawable.flag_us,
            "GB" to R.drawable.flag_gb,
            "EU" to R.drawable.flag_eu,
            "DE" to R.drawable.flag_de,
            "ES" to R.drawable.flag_es,
            "PL" to R.drawable.flag_pl,
            "NL" to R.drawable.flag_nl,
            "FR" to R.drawable.flag_fr,
            "IT" to R.drawable.flag_it,
            "RU" to R.drawable.flag_ru,
            "TR" to R.drawable.flag_tr,
            "NO" to R.drawable.flag_no,
            "CA" to R.drawable.flag_ca,
            "BE" to R.drawable.flag_be,
            "AT" to R.drawable.flag_at,
            "CH" to R.drawable.flag_ch,
            "HK" to R.drawable.flag_hk,
            "IE" to R.drawable.flag_ie,
            "FI" to R.drawable.flag_fi,
            "DK" to R.drawable.flag_dk,
            "SE" to R.drawable.flag_se,
            "AR" to R.drawable.flag_ar,
            "MX" to R.drawable.flag_mx,
            "BR" to R.drawable.flag_br,
            "MA" to R.drawable.flag_ma,
            "AL" to R.drawable.flag_al,
            "AU" to R.drawable.flag_au,
            "JP" to R.drawable.flag_jp,
            "KR" to R.drawable.flag_kr,
            "AE" to R.drawable.flag_ae,
            "SA" to R.drawable.flag_sa,
            "EG" to R.drawable.flag_eg,
            "PT" to R.drawable.flag_pt,
            "CN" to R.drawable.flag_cn,
            "AF" to R.drawable.flag_af,
            "AM" to R.drawable.flag_am,
            "IE" to R.drawable.flag_ie,
            "IS" to R.drawable.flag_is,
            "LU" to R.drawable.flag_lu
        )
    }
}
