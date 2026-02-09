package com.tvonnet.debridxtreamiptv.data.local.converters

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Database Type Converters for Room database
 * Phase 3.2: Content Recommendations System
 *
 * Handles conversion of complex data types for database storage
 * - List<String> for preferences and metadata
 * - Map<String, Any> for flexible metadata storage
 */
class DatabaseConverters {

    companion object {

        private val gson = Gson()

        /**
         * Converts List<String> to JSON String for database storage
         */
        @TypeConverter
        @JvmStatic
        fun fromStringList(value: List<String>): String {
            return gson.toJson(value)
        }

        /**
         * Converts JSON String to List<String> from database storage
         */
        @TypeConverter
        @JvmStatic
        fun toStringList(value: String): List<String> {
            val type = object : TypeToken<List<String>>() {}.type
            return gson.fromJson(value, type) ?: emptyList()
        }

        /**
         * Converts Map<String, Any> to JSON String for database storage
         */
        @TypeConverter
        @JvmStatic
        fun fromStringMap(value: Map<String, Any>): String {
            return gson.toJson(value)
        }

        /**
         * Converts JSON String to Map<String, Any> from database storage
         */
        @TypeConverter
        @JvmStatic
        fun toStringMap(value: String): Map<String, Any> {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            return gson.fromJson(value, type) ?: emptyMap()
        }
    }
}