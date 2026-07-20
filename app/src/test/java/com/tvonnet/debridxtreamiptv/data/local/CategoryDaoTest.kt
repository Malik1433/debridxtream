package com.tvonnet.debridxtreamiptv.data.local

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.local.dao.CategoryDao
import com.tvonnet.debridxtreamiptv.data.local.entity.CategoryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 8: REAL Room DAO test (was mockk(relaxed=true) + coVerify, which asserted nothing about SQL).
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CategoryDaoTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase
    private lateinit var categoryDao: CategoryDao

    private val live = CategoryEntity(categoryId = "cat1", categoryName = "Sports", type = "live")
    private val vod = CategoryEntity(categoryId = "cat2", categoryName = "Movies", type = "vod")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        categoryDao = db.categoryDao()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `insertCategory then getCategoryById round-trips`() = runTest {
        categoryDao.insertCategory(live)

        val result = categoryDao.getCategoryById("cat1")
        assertEquals("Sports", result?.categoryName)
        assertEquals("live", result?.type)
    }

    @Test
    fun `getCategoryById returns null for a missing id`() = runTest {
        assertNull(categoryDao.getCategoryById("nope"))
    }

    @Test
    fun `getCategoriesByTypeSync filters by type`() = runTest {
        categoryDao.insertCategories(listOf(live, vod))

        val liveCats = categoryDao.getCategoriesByTypeSync("live")
        assertEquals(1, liveCats.size)
        assertEquals("cat1", liveCats.first().categoryId)
    }

    @Test
    fun `getAllCategories flow emits every inserted row`() = runTest {
        categoryDao.insertCategories(listOf(live, vod))

        assertEquals(2, categoryDao.getAllCategories().first().size)
    }

    @Test
    fun `deleteCategoriesByType removes only that type`() = runTest {
        categoryDao.insertCategories(listOf(live, vod))

        categoryDao.deleteCategoriesByType("live")

        assertEquals(0, categoryDao.getCategoriesByTypeSync("live").size)
        assertEquals(1, categoryDao.getCategoriesByTypeSync("vod").size)
    }

    @Test
    fun `deleteAllCategories empties the table`() = runTest {
        categoryDao.insertCategories(listOf(live, vod))

        categoryDao.deleteAllCategories()

        assertEquals(0, categoryDao.getAllCategories().first().size)
    }

    @Test
    fun `insertCategory REPLACEs on conflicting id`() = runTest {
        categoryDao.insertCategory(live)
        categoryDao.insertCategory(live.copy(categoryName = "Sports HD"))

        assertEquals("Sports HD", categoryDao.getCategoryById("cat1")?.categoryName)
        assertEquals(1, categoryDao.getAllCategories().first().size)
    }
}
