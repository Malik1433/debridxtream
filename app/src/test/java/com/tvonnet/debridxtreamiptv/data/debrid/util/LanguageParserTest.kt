package com.tvonnet.debridxtreamiptv.data.debrid.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageParserTest {

    @Test
    fun `detects single language correctly`() {
        val title = "Avengers.Endgame.2019.1080p.BluRay.x264.DDP5.1.Atmos.Hin-Telly"
        val languages = LanguageParser.extractLanguages(title)
        assertEquals(listOf("hi"), languages)
    }

    @Test
    fun `detects multiple languages correctly`() {
        val title = "Leo.2023.1080p.Netflix.WEB-DL.DDP5.1.Atmos.Hin.Tam.Tel.Kan.Mal-Telly"
        val languages = LanguageParser.extractLanguages(title)
        // Order might matter depending on implementation, but for now checking content
        val expected = listOf("hi", "ta", "te", "kn", "ml")
        assertEquals(expected.size, languages.size)
        expected.forEach { assert(languages.contains(it)) }
    }

    @Test
    fun `detects English by default if no other language found`() {
        val title = "Inception.2010.1080p.BluRay.x264-SPARKS"
        val languages = LanguageParser.extractLanguages(title)
        assertEquals(listOf("en"), languages)
    }

    @Test
    fun `detects Multi Audio tag`() {
        val title = "Some.Movie.2024.Multi.Audio.1080p.WEB-DL"
        val languages = LanguageParser.extractLanguages(title)
        assert(languages.contains("multi"))
    }

    @Test
    fun `handles empty title safely`() {
        val languages = LanguageParser.extractLanguages("")
        assertEquals(listOf("en"), languages)
    }

    @Test
    fun `detects language from full words`() {
        val title = "Parasite.2019.Korean.1080p.BluRay"
        val languages = LanguageParser.extractLanguages(title)
        assertEquals(listOf("ko"), languages)
    }
    
    @Test
    fun `detects Spanish variations`() {
        val title1 = "Money.Heist.S01.Spanish.1080p"
        val title2 = "Elite.S01.Spa.1080p"
        val title3 = "Velvet.S01.Espanol.1080p"
        
        assertEquals(listOf("es"), LanguageParser.extractLanguages(title1))
        assertEquals(listOf("es"), LanguageParser.extractLanguages(title2))
        assertEquals(listOf("es"), LanguageParser.extractLanguages(title3))
    }
    @Test
    fun `detects languages with plus sign and brackets`() {
        val title = "Predator Badlands 2025 HQ PreDVD - 1080p - x264 - [Tam + Tel + Hin + Eng] - HQ"
        val languages = LanguageParser.extractLanguages(title)
        val expected = listOf("ta", "te", "hi", "en")
        
        // We expect at least these languages. Order doesn't strictly matter for correctness but list equality checks order.
        // Let's check that all expected languages are present.
        expected.forEach { 
            assert(languages.contains(it)) { "Expected language $it not found in $languages" }
        }
    }
}
