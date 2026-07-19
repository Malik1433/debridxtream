package com.tvonnet.debridxtreamiptv.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database Migrations for Room
 * Week 12: QA Recommendation - Proper migrations to preserve user data
 */
object DatabaseMigrations {
    
    /**
     * Migration from version 4 to 5
     * Week 12: Added name and iconUrl fields to favorites table
     * 
     * Changes:
     * - Added 'name' column (TEXT NOT NULL with default empty string)
     * - Added 'iconUrl' column (TEXT nullable)
     * 
     * This preserves existing user favorites while adding new fields
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add 'name' column with default empty string
            // Note: Existing favorites will have empty name, but app will continue to work
            db.execSQL(
                "ALTER TABLE favorites ADD COLUMN name TEXT NOT NULL DEFAULT ''"
            )
            
            // Add 'iconUrl' column (nullable)
            db.execSQL(
                "ALTER TABLE favorites ADD COLUMN iconUrl TEXT"
            )
            
            android.util.Log.d("DatabaseMigration", "Successfully migrated database from version 4 to 5")
        }
    }
    
    /**
     * Migration from version 5 to 6
     * Phase 1: Database Infrastructure for VOD and Series
     * 
     * Changes:
     * - Create 'vod_v2' table
     * - Create 'series_v2' table
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create vod_v2 table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `vod_v2` (
                    `streamId` TEXT NOT NULL,
                    `name` TEXT,
                    `streamIcon` TEXT,
                    `rating` TEXT,
                    `rating5based` REAL,
                    `added` TEXT,
                    `categoryId` TEXT,
                    `category_ids` TEXT NOT NULL,
                    `containerExtension` TEXT,
                    `customSid` TEXT,
                    `directSource` TEXT,
                    `releaseDate` TEXT,
                    `duration` TEXT,
                    `plot` TEXT,
                    `cast` TEXT,
                    `director` TEXT,
                    `genre` TEXT,
                    `youtubeTrailer` TEXT,
                    `cover` TEXT,
                    `ratingImdb` TEXT,
                    `num` INTEGER,
                    `cachedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`streamId`)
                )
            """)
            
            // Create series_v2 table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `series_v2` (
                    `seriesId` TEXT NOT NULL,
                    `name` TEXT,
                    `cover` TEXT,
                    `plot` TEXT,
                    `cast` TEXT,
                    `director` TEXT,
                    `genre` TEXT,
                    `releaseDate` TEXT,
                    `rating` TEXT,
                    `rating5based` REAL,
                    `categoryId` TEXT,
                    `category_ids` TEXT NOT NULL,
                    `backdrop_path` TEXT NOT NULL,
                    `youtube_trailer` TEXT,
                    `num` INTEGER,
                    `cachedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`seriesId`)
                )
            """)
            
            android.util.Log.d("DatabaseMigration", "Successfully migrated database from version 5 to 6")
        }
    }
    
    /**
     * Migration from version 6 to 7
     * Phase 2: Series Architecture (Seasons & Episodes)
     *
     * Changes:
     * - Create 'seasons' table
     * - Create 'episodes' table
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create seasons table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `seasons` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `seriesId` TEXT NOT NULL,
                    `seasonNumber` INTEGER NOT NULL,
                    `name` TEXT,
                    `overview` TEXT,
                    `airDate` TEXT,
                    `cover` TEXT,
                    FOREIGN KEY(`seriesId`) REFERENCES `series_v2`(`seriesId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """)
            
            // Create index on seriesId for seasons
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_seasons_seriesId` ON `seasons` (`seriesId`)")

            // Create episodes table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `episodes` (
                    `episodeId` TEXT NOT NULL,
                    `seriesId` TEXT NOT NULL,
                    `seasonNumber` INTEGER NOT NULL,
                    `episodeNumber` INTEGER NOT NULL,
                    `title` TEXT,
                    `containerExtension` TEXT,
                    `streamType` TEXT,
                    `durationSecs` TEXT,
                    `added` TEXT,
                    `customSid` TEXT,
                    `directSource` TEXT,
                    `thumbnail` TEXT,
                    `plot` TEXT,
                    `cast` TEXT,
                    `director` TEXT,
                    `genre` TEXT,
                    `releaseDate` TEXT,
                    `rating` TEXT,
                    PRIMARY KEY(`episodeId`),
                    FOREIGN KEY(`seriesId`) REFERENCES `series_v2`(`seriesId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """)
            
            // Create index on seriesId for episodes
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_seriesId` ON `episodes` (`seriesId`)")
            
            android.util.Log.d("DatabaseMigration", "Successfully migrated database from version 6 to 7")
        }
    }

    /**
     * Get all available migrations
     * Add new migrations here as they are created
     */
    fun getAllMigrations(): Array<Migration> {
        return arrayOf(
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13
        )
    }

    /**
     * Migration 12 -> 13 (B-1): browse-catalog indices.
     *
     * Every category open filtered `WHERE categoryId = ?` as a FULL TABLE SCAN of
     * channels / vod_v2 / series_v2 (10k-50k+ rows after a full sync). Purely additive
     * — CREATE INDEX only, no data touched. Index names match Room's auto-generated
     * `index_<table>_<cols>` scheme so the entity `indices` annotations validate.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_categoryId_streamType` ON `channels` (`categoryId`, `streamType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_v2_categoryId` ON `vod_v2` (`categoryId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_v2_categoryId` ON `series_v2` (`categoryId`)")
            android.util.Log.d("DatabaseMigration", "Successfully migrated database from version 12 to 13 (browse indices)")
        }
    }

    /**
     * Migration from version 7 to 8
     * Phase 3: Episode Tracking
     *
     * Changes:
     * - Add 'is_watched' (INTEGER/BOOLEAN)
     * - Add 'resume_position' (INTEGER)
     * - Add 'duration' (INTEGER)
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE episodes ADD COLUMN is_watched INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE episodes ADD COLUMN resume_position INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE episodes ADD COLUMN duration INTEGER NOT NULL DEFAULT 0")
            android.util.Log.d("DatabaseMigration", "Successfully migrated database from version 7 to 8")
        }
    }
    
    /**
     * Migration from version 8 to 9
     * Phase 3: High Performance Series Engine (V2)
     *
     * Changes:
     * - Create 'series_v2_core' table strict schema
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
             db.execSQL("""
                CREATE TABLE IF NOT EXISTS `series_v2_core` (
                    `series_id` TEXT NOT NULL,
                    `num` INTEGER,
                    `name` TEXT,
                    `title` TEXT,
                    `year` TEXT,
                    `stream_type` TEXT,
                    `cover` TEXT,
                    `plot` TEXT,
                    `cast` TEXT,
                    `director` TEXT,
                    `genre` TEXT,
                    `release_date` TEXT,
                    `rating` TEXT,
                    `rating_5based` REAL,
                    `backdrop_path` TEXT NOT NULL,
                    `youtube_trailer` TEXT,
                    `episode_run_time` TEXT,
                    `category_id` TEXT,
                    `last_modified` TEXT,
                    `cached_at` INTEGER NOT NULL,
                    PRIMARY KEY(`series_id`)
                )
            """)
            android.util.Log.d("DatabaseMigration", "Successfully migrated database from version 8 to 9")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
             db.execSQL("""
                CREATE TABLE IF NOT EXISTS `episodes_v2_core` (
                    `episode_id` TEXT NOT NULL,
                    `series_id` TEXT NOT NULL,
                    `season_number` INTEGER NOT NULL,
                    `episode_number` INTEGER NOT NULL,
                    `title` TEXT,
                    `container_extension` TEXT,
                    `stream_type` TEXT,
                    `duration_secs` TEXT,
                    `added` TEXT,
                    `custom_sid` TEXT,
                    `direct_source` TEXT,
                    `thumbnail` TEXT,
                    `plot` TEXT,
                    `cast` TEXT,
                    `director` TEXT,
                    `genre` TEXT,
                    `release_date` TEXT,
                    `rating` TEXT,
                    `is_watched` INTEGER NOT NULL DEFAULT 0,
                    `resume_position` INTEGER NOT NULL DEFAULT 0,
                    `duration` INTEGER NOT NULL DEFAULT 0,
                    `cached_at` INTEGER NOT NULL,
                    PRIMARY KEY(`episode_id`),
                    FOREIGN KEY(`series_id`) REFERENCES `series_v2_core`(`series_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_v2_core_series_id` ON `episodes_v2_core` (`series_id`)")
            android.util.Log.d("DatabaseMigration", "Successfully migrated database from version 9 to 10")
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `watched_state` (
                    `identity_key` TEXT NOT NULL,
                    `content_type` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `is_watched` INTEGER NOT NULL,
                    `progress_ms` INTEGER NOT NULL DEFAULT 0,
                    `duration_ms` INTEGER NOT NULL DEFAULT 0,
                    `watched_at` INTEGER,
                    `updated_at` INTEGER NOT NULL,
                    `manual_state` TEXT,
                    `manual_updated_at` INTEGER,
                    `title` TEXT,
                    `year` TEXT,
                    `tmdb_id` TEXT,
                    `imdb_id` TEXT,
                    `iptv_stream_id` TEXT,
                    `series_id` TEXT,
                    `season_number` INTEGER,
                    `episode_number` INTEGER,
                    `episode_id` TEXT,
                    PRIMARY KEY(`identity_key`)
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_watched_state_content_type_source` ON `watched_state` (`content_type`, `source`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_watched_state_series_id_source_season_number` ON `watched_state` (`series_id`, `source`, `season_number`)")
            android.util.Log.d("DatabaseMigration", "Successfully migrated database from version 10 to 11")
        }
    }

    /**
     * Migration from version 11 to 12
     * Fix: episodes_v2_core primary key episode_id → composite (series_id, episode_id).
     *
     * The same Xtream episode stream id can legitimately belong to more than one series
     * listing (a show is listed as separate series_ids per category; the multi-source panel
     * and sibling-episode adoption store a sibling's episodes under another series' id so
     * each category resolves its own stream). With episode_id alone as PK, REPLACE-on-conflict
     * flipped a row's series_id to whichever write came last, so getEpisode(thatSeriesId,…)
     * returned null and "select any category → won't play from there". SQLite can't ALTER a
     * primary key, so recreate the table with the composite key and copy existing rows.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `episodes_v2_core_new` (
                    `episode_id` TEXT NOT NULL,
                    `series_id` TEXT NOT NULL,
                    `season_number` INTEGER NOT NULL,
                    `episode_number` INTEGER NOT NULL,
                    `title` TEXT,
                    `container_extension` TEXT,
                    `stream_type` TEXT,
                    `duration_secs` TEXT,
                    `added` TEXT,
                    `custom_sid` TEXT,
                    `direct_source` TEXT,
                    `thumbnail` TEXT,
                    `plot` TEXT,
                    `cast` TEXT,
                    `director` TEXT,
                    `genre` TEXT,
                    `release_date` TEXT,
                    `rating` TEXT,
                    `is_watched` INTEGER NOT NULL DEFAULT 0,
                    `resume_position` INTEGER NOT NULL DEFAULT 0,
                    `duration` INTEGER NOT NULL DEFAULT 0,
                    `cached_at` INTEGER NOT NULL,
                    PRIMARY KEY(`series_id`, `episode_id`),
                    FOREIGN KEY(`series_id`) REFERENCES `series_v2_core`(`series_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """)
            // Copy existing rows (old episode_id PK is unique, so no collisions here).
            db.execSQL("""
                INSERT OR IGNORE INTO `episodes_v2_core_new` (
                    `episode_id`, `series_id`, `season_number`, `episode_number`, `title`,
                    `container_extension`, `stream_type`, `duration_secs`, `added`, `custom_sid`,
                    `direct_source`, `thumbnail`, `plot`, `cast`, `director`, `genre`,
                    `release_date`, `rating`, `is_watched`, `resume_position`, `duration`, `cached_at`
                )
                SELECT
                    `episode_id`, `series_id`, `season_number`, `episode_number`, `title`,
                    `container_extension`, `stream_type`, `duration_secs`, `added`, `custom_sid`,
                    `direct_source`, `thumbnail`, `plot`, `cast`, `director`, `genre`,
                    `release_date`, `rating`, `is_watched`, `resume_position`, `duration`, `cached_at`
                FROM `episodes_v2_core`
            """)
            db.execSQL("DROP TABLE `episodes_v2_core`")
            db.execSQL("ALTER TABLE `episodes_v2_core_new` RENAME TO `episodes_v2_core`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_v2_core_series_id` ON `episodes_v2_core` (`series_id`)")
            android.util.Log.d("DatabaseMigration", "Successfully migrated database from version 11 to 12 (composite PK)")
        }
    }
}

