package com.tvonnet.debridxtreamiptv.ui.compose.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.ui.compose.SourceUiModel
import com.tvonnet.debridxtreamiptv.ui.sources.SizeFilterOption
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import java.util.Locale

@Composable
fun SourceListSection(
    sources: List<SourceUiModel>,
    onSourceClick: (SourceUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    if (sources.isEmpty()) return

    val showCachedFilter = sources.any { hasCacheConfidence(it) }
    val showSizeFilters = sources.any { it.sizeBytes != null }
    val languageOptions = remember(sources) {
        sources.flatMap { it.languages ?: emptyList() }
            .map { it.lowercase(Locale.US) }
            .distinct()
            .sorted()
    }
    val showLanguageFilters = languageOptions.size > 1

    val filters = remember(sources) { SourceChipFilters() }

    LaunchedEffect(showCachedFilter) {
        if (!showCachedFilter) filters.cachedOnly = false
    }
    LaunchedEffect(showSizeFilters) {
        if (!showSizeFilters) filters.selectedSizeOption = SourceFilterUtils.SIZE_OPTIONS.first()
    }
    LaunchedEffect(showLanguageFilters) {
        if (!showLanguageFilters) filters.preferredLanguage = null
    }

    val filteredSources = remember(
        sources,
        filters.cachedOnly,
        filters.selectedSizeOption,
        filters.preferredLanguage,
        showCachedFilter,
        showSizeFilters,
        showLanguageFilters
    ) {
        applySourceFilters(
            sources = sources,
            cachedOnly = filters.cachedOnly && showCachedFilter,
            maxSizeBytes = if (showSizeFilters) filters.selectedSizeOption.maxSizeBytes else null,
            preferredLanguage = if (showLanguageFilters) filters.preferredLanguage else null
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.movie_detail_sources_header),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        SourceFilterChips(
            visibility = SourceFilterVisibility(showCachedFilter, showSizeFilters, showLanguageFilters),
            filters = filters,
            languageOptions = languageOptions
        )

        SourceRows(filteredSources, onSourceClick)
    }
}

/** Chip-filter state for one sources list — remembered per list, so it resets on new sources. */
private class SourceChipFilters {
    var cachedOnly by mutableStateOf(false)
    var selectedSizeOption by mutableStateOf(SourceFilterUtils.SIZE_OPTIONS.first())
    var preferredLanguage by mutableStateOf<String?>(null)
}

private class SourceFilterVisibility(val cached: Boolean, val size: Boolean, val language: Boolean) {
    val any: Boolean get() = cached || size || language
}

@Composable
private fun SourceFilterChips(
    visibility: SourceFilterVisibility,
    filters: SourceChipFilters,
    languageOptions: List<String>
) {
    if (!visibility.any) return
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (visibility.cached) {
            CachedFilterChip(filters.cachedOnly) { filters.cachedOnly = !filters.cachedOnly }
        }
        if (visibility.size) {
            SizeFilterChipRow(filters.selectedSizeOption) { filters.selectedSizeOption = it }
        }
        if (visibility.language) {
            LanguageFilterChipRow(languageOptions, filters.preferredLanguage) { filters.preferredLanguage = it }
        }
    }
}

@Composable
private fun CachedFilterChip(cachedOnly: Boolean, onToggle: () -> Unit) {
    FilterChip(
        label = stringResource(R.string.source_filter_cached_only),
        selected = cachedOnly,
        onClick = onToggle
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SizeFilterChipRow(selected: SizeFilterOption, onSelect: (SizeFilterOption) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        SourceFilterUtils.SIZE_OPTIONS.forEach { option ->
            FilterChip(
                label = option.label,
                selected = option == selected,
                onClick = { onSelect(option) }
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun LanguageFilterChipRow(
    options: List<String>,
    preferred: String?,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        options.forEach { language ->
            FilterChip(
                label = languageLabelFor(language),
                selected = preferred == language,
                // Selecting the active language again clears the filter.
                onClick = { onSelect(if (preferred == language) null else language) }
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun SourceRows(filteredSources: List<SourceUiModel>, onSourceClick: (SourceUiModel) -> Unit) {
    if (filteredSources.isEmpty()) {
        Text(
            text = stringResource(R.string.source_filters_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB0B0B0),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        return
    }
    filteredSources.forEach { source ->
        SourceItem(
            source = source,
            onClick = { onSourceClick(source) }
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun SourceItem(
    source: SourceUiModel,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    val borderBrush = if (isFocused) {
        Brush.horizontalGradient(
            colors = listOf(
                Color(0xFFFFD700),
                Color(0xFFFFA000)
            )
        )
    } else {
        SolidColor(Color.White.copy(alpha = 0.1f))
    }

    val backgroundColor = if (isFocused) {
        Color.White.copy(alpha = 0.15f)
    } else {
        Color.Black.copy(alpha = 0.4f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                brush = borderBrush,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SourceItemHeader(source, isFocused)
            SourceProviderLine(source, isFocused)
            SourceBadgesRow(source, isFocused)
        }
    }
}

@Composable
private fun SourceItemHeader(source: SourceUiModel, isFocused: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = source.name,
            color = if (isFocused) Color.White else Color(0xFFEEEEEE),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )

        source.languageFlag?.takeIf { it.isNotBlank() }?.let { language ->
            BadgeChip(
                label = language,
                isFocused = isFocused
            )
        }
    }
}

@Composable
private fun SourceProviderLine(source: SourceUiModel, isFocused: Boolean) {
    val providerLine = listOfNotNull(
        source.source?.takeIf { it.isNotBlank() },
        source.extension?.uppercase(Locale.US)
    ).joinToString(" - ")
    if (providerLine.isNotBlank()) {
        Text(
            text = providerLine,
            color = if (isFocused) Color(0xFFCCCCCC) else Color.Gray,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun SourceBadgesRow(source: SourceUiModel, isFocused: Boolean) {
    val badges = buildBadges(source)
    if (badges.isEmpty()) return
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        badges.forEach { label ->
            BadgeChip(
                label = label,
                isFocused = isFocused
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.35f)
    val borderColor = if (selected) Color(0xFFFFD700) else Color.White.copy(alpha = 0.2f)
    Text(
        text = label,
        color = if (selected) Color.White else Color(0xFFDDDDDD),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(background, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun BadgeChip(
    label: String,
    isFocused: Boolean
) {
    val background = if (isFocused) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.4f)
    val borderColor = if (isFocused) Color(0xFFFFD700) else Color.White.copy(alpha = 0.2f)
    Text(
        text = label,
        color = if (isFocused) Color.White else Color(0xFFDDDDDD),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(background, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

private fun applySourceFilters(
    sources: List<SourceUiModel>,
    cachedOnly: Boolean,
    maxSizeBytes: Long?,
    preferredLanguage: String?
): List<SourceUiModel> {
    var filtered = sources

    if (cachedOnly) {
        filtered = filtered.filter { it.cacheStatus == DebridCacheStatus.VERIFIED_CACHED }
    }

    if (maxSizeBytes != null) {
        filtered = filtered.filter { source ->
            val size = source.sizeBytes
            size == null || size <= maxSizeBytes
        }
    }

    val preferred = preferredLanguage?.lowercase(Locale.US)
    if (preferred != null) {
        filtered = filtered.sortedWith(
            compareByDescending<SourceUiModel> { matchesPreferredLanguage(it, preferred) }
                .thenByDescending { cachePriority(it) }
                .thenByDescending { it.seeds ?: -1 }
        )
    } else {
        filtered = filtered.sortedWith(
            compareByDescending<SourceUiModel> { cachePriority(it) }
                .thenByDescending { it.seeds ?: -1 }
        )
    }

    return filtered
}

private fun matchesPreferredLanguage(source: SourceUiModel, preferred: String): Boolean {
    val languages = source.languages?.map { it.lowercase(Locale.US) } ?: return false
    return languages.contains(preferred) || languages.contains("multi")
}

// The old when-chain as data; an unknown code falls back to its own uppercase form.
private val LANGUAGE_LABEL_BY_CODE = mapOf(
    "multi" to "MULTI",
    "en" to "EN", "eng" to "EN", "english" to "EN",
    "fr" to "FR", "fre" to "FR", "french" to "FR",
    "de" to "DE", "ger" to "DE", "german" to "DE",
    "es" to "ES", "spa" to "ES", "spanish" to "ES",
    "it" to "IT", "ita" to "IT", "italian" to "IT",
    "pt" to "PT", "por" to "PT", "portuguese" to "PT",
    "ru" to "RU", "rus" to "RU", "russian" to "RU",
    "hi" to "HI", "hin" to "HI", "hindi" to "HI",
    "ja" to "JA", "jpn" to "JA", "japanese" to "JA",
    "ko" to "KO", "kor" to "KO", "korean" to "KO",
    "zh" to "ZH", "chi" to "ZH", "chinese" to "ZH",
    "ar" to "AR", "ara" to "AR", "arabic" to "AR",
    "tr" to "TR", "tur" to "TR", "turkish" to "TR",
    "pl" to "PL", "pol" to "PL", "polish" to "PL",
    "nl" to "NL", "dut" to "NL", "dutch" to "NL",
)

private fun languageLabelFor(code: String): String =
    LANGUAGE_LABEL_BY_CODE[code.lowercase(Locale.US)] ?: code.uppercase(Locale.US)

private fun buildBadges(source: SourceUiModel): List<String> {
    val badges = mutableListOf<String>()

    source.quality?.takeIf { it.isNotBlank() }?.let { badges.add(it.uppercase(Locale.US)) }
    source.size?.takeIf { it.isNotBlank() }?.let { badges.add(it) }
    source.seeds?.takeIf { it > 0 }?.let {
        val label = if (it == 1) "SEED" else "SEEDS"
        badges.add("$it $label")
    }
    when (source.cacheStatus) {
        DebridCacheStatus.VERIFIED_CACHED -> badges.add("VERIFIED")
        DebridCacheStatus.DIRECT_STREAM -> badges.add("DIRECT")
        DebridCacheStatus.NOT_CACHED -> badges.add("UNCACHED")
        DebridCacheStatus.UNKNOWN -> badges.add("UNKNOWN")
    }

    return badges
}

private fun hasCacheConfidence(source: SourceUiModel): Boolean {
    return source.cacheStatus != DebridCacheStatus.UNKNOWN || source.isCached != null
}

private fun cachePriority(source: SourceUiModel): Int {
    return when (source.cacheStatus) {
        DebridCacheStatus.VERIFIED_CACHED -> 4
        DebridCacheStatus.DIRECT_STREAM -> 3
        DebridCacheStatus.UNKNOWN -> 1
        DebridCacheStatus.NOT_CACHED -> 0
    }
}
