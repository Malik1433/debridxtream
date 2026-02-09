package com.tvonnet.debridxtreamiptv.data.local

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.local.dao.VodDao
import com.tvonnet.debridxtreamiptv.data.local.entity.VodEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VodDaoTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AppDatabase
    private lateinit var vodDao: VodDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vodDao = database.vodDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndGetMovies() = runTest {
        val movies = listOf(
            createMovie("1", "Action Movie", "cat1"),
            createMovie("2", "Comedy Movie", "cat1")
        )
        vodDao.insertMovies(movies)

        // searchMovies returns List<VodEntity>
        val result = vodDao.searchMovies("Movie")
        assertEquals(2, result.size)
    }

    @Test
    fun deleteMoviesByCategory() = runTest {
        val movies = listOf(
            createMovie("1", "Movie 1", "cat1"),
            createMovie("2", "Movie 2", "cat2")
        )
        vodDao.insertMovies(movies)

        vodDao.deleteMoviesByCategory("cat1")

        val result1 = vodDao.searchMovies("Movie 1")
        val result2 = vodDao.searchMovies("Movie 2")

        assertEquals(0, result1.size)
        assertEquals(1, result2.size)
    }

    @Test
    fun searchMoviesFiltering() = runTest {
        val movies = listOf(
            createMovie("1", "Spider-Man", "cat1"),
            createMovie("2", "The Dark Knight", "cat1"),
            createMovie("3", "Iron Man", "cat1")
        )
        vodDao.insertMovies(movies)

        val spiderResult = vodDao.searchMovies("Spider")
        val manResult = vodDao.searchMovies("Man")

        assertEquals(1, spiderResult.size)
        assertEquals("Spider-Man", spiderResult[0].name)
        assertEquals(2, manResult.size) // Spider-Man and Iron Man
    }

    private fun createMovie(id: String, name: String, categoryId: String): VodEntity {
        return VodEntity(
            streamId = id,
            name = name,
            categoryId = categoryId,
            num = id.toIntOrNull() ?: 0,
            streamIcon = "icon.png",
            rating = "8.0",
            rating5based = 4.0,
            added = "123456",
            category_ids = emptyList(),
            containerExtension = "mp4",
            customSid = null,
            directSource = null,
            releaseDate = "2023",
            duration = "120",
            plot = "Plot",
            cast = "Cast",
            director = "Director",
            genre = "Genre",
            youtubeTrailer = null,
            cover = "cover.jpg",
            ratingImdb = "8.0"
        )
    }
}
