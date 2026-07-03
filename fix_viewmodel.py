import sys
path = 'app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/discover/DebridDiscoverViewModel.kt'
content = open(path, encoding='utf-8').read()

content = content.replace('private var currentType = "movie"', 'private var currentType = "movie"\n    private var currentLanguage: String? = null\n    private var currentRegion: String? = null\n    private var currentReleaseDateGte: String? = null')
content = content.replace('suspend fun loadGenres(type: String)', 'fun setCustomFilters(language: String?, region: String?, releaseDateGte: String?) {\n        currentLanguage = language\n        currentRegion = region\n        currentReleaseDateGte = releaseDateGte\n    }\n\n    suspend fun loadGenres(type: String)')

old_loadcontent = '''val result = catalogRepo.getDiscoveryContent(
                type = currentType,
                genreId = currentGenreId,
                catalogue = currentCatalogue,
                year = currentYear,
                page = currentPage
            )'''

new_loadcontent = '''val result = catalogRepo.getDiscoveryContent(
                type = currentType,
                genreId = currentGenreId,
                catalogue = currentCatalogue,
                year = currentYear,
                withOriginalLanguage = currentLanguage,
                region = currentRegion,
                releaseDateGte = currentReleaseDateGte,
                page = currentPage
            )'''
content = content.replace(old_loadcontent, new_loadcontent)

open(path, 'w', encoding='utf-8').write(content)
