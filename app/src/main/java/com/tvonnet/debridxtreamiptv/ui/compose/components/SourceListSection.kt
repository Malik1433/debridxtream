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

    var cachedOnly by remember(sources) { mutableStateOf(false) }
    var selectedSizeOption by remember(sources) { mutableStateOf(SourceFilterUtils.SIZE_OPTIONS.first()) }
    var preferredLanguage by remember(sources) { mutableStateOf<String?>(null) }

    LaunchedEffect(showCachedFilter) {
        if (!showCachedFilter) cachedOnly = false
    }
    LaunchedEffect(showSizeFilters) {
        if (!showSizeFilters) selectedSizeOption = SourceFilterUtils.SIZE_OPTIONS.first()
    }
    LaunchedEffect(showLanguageFilters) {
        if (!showLanguageFilters) preferredLanguage = null
    }

    val filteredSources = remember(
        sources,
        cachedOnly,
        selectedSizeOption,
        preferredLanguage,
        showCachedFilter,
        showSizeFilters,
        showLanguageFilters
    ) {
        applySourceFilters(
            sources = sources,
            cachedOnly = cachedOnly && showCachedFilter,
            maxSizeBytes = if (showSizeFilters) selectedSizeOption.maxSizeBytes else null,
            preferredLanguage = if (showLanguageFilters) preferredLanguage else null
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.movie_detail_sources_header),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (showCachedFilter || showSizeFilters || showLanguageFilters) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (showCachedFilter) {
                    FilterChip(
                        label = stringResource(R.string.source_filter_cached_only),
                        selected = cachedOnly,
                        onClick = { cachedOnly = !cachedOnly }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (showSizeFilters) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp)
                    ) {
                        SourceFilterUtils.SIZE_OPTIONS.forEach { option ->
                            FilterChip(
                                label = option.label,
                                selected = option == selectedSizeOption,
                                onClick = { selectedSizeOption = option }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }

                if (showLanguageFilters) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp)
                    ) {
                        languageOptions.forEach { language ->
                            val label = languageLabelFor(language)
                            FilterChip(
                                label = label,
                                selected = preferredLanguage == language,
                                onClick = {
                                    preferredLanguage = if (preferredLanguage == language) null else language
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }
            }
        }

        if (filteredSources.isEmpty()) {
            Text(
                text = stringResource(R.string.source_filters_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0B0B0),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            filteredSources.forEach { source ->
                SourceItem(
                    source = source,
                    onClick = { onSourceClick(source) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
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

            val badges = buildBadges(source)
            if (badges.isNotEmpty()) {
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

private fun languageLabelFor(code: String): String {
    return when (code.lowercase(Locale.US)) {
        "multi" -> "MULTI"
        "en", "eng", "english" -> "EN"
        "fr", "fre", "french" -> "FR"
        "de", "ger", "german" -> "DE"
        "es", "spa", "spanish" -> "ES"
        "it", "ita", "italian" -> "IT"
        "pt", "por", "portuguese" -> "PT"
        "ru", "rus", "russian" -> "RU"
        "hi", "hin", "hindi" -> "HI"
        "ja", "jpn", "japanese" -> "JA"
        "ko", "kor", "korean" -> "KO"
        "zh", "chi", "chinese" -> "ZH"
        "ar", "ara", "arabic" -> "AR"
        "tr", "tur", "turkish" -> "TR"
        "pl", "pol", "polish" -> "PL"
        "nl", "dut", "dutch" -> "NL"
        else -> code.uppercase(Locale.US)
    }
}

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
