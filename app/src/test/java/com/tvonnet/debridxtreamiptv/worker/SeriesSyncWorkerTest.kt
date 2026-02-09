package com.tvonnet.debridxtreamiptv.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.tvonnet.debridxtreamiptv.data.Result

@RunWith(RobolectricTestRunner::class)
class SeriesSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var mockRepository: XtreamRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockRepository = mockk(relaxed = true)
    }

    @Test
    fun doWork_syncsSeriesDetails_returnsSuccess() = runBlocking {
        // Arrange
        val seriesId = "123"
        val inputData = androidx.work.Data.Builder()
            .putString("series_id", seriesId)
            .build()

        val mockResponse = com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse(null, null, null)
        coEvery { mockRepository.syncSeriesDetail(seriesId) } returns Result.Success(mockResponse)

        val worker = TestListenableWorkerBuilder<SeriesSyncWorker>(context)
            .setInputData(inputData)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker? {
                    return SeriesSyncWorker(appContext, workerParameters, mockRepository)
                }
            })
            .build()

        // Act
        val result = worker.doWork()

        // Assert
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { mockRepository.syncSeriesDetail(seriesId) }
    }

    @Test
    fun doWork_syncFails_returnsRetry() = runBlocking {
        // Arrange
        val seriesId = "456"
        val inputData = androidx.work.Data.Builder()
            .putString("series_id", seriesId)
            .build()

        coEvery { mockRepository.syncSeriesDetail(seriesId) } returns Result.Error(Exception("Network error"))

        val worker = TestListenableWorkerBuilder<SeriesSyncWorker>(context)
            .setInputData(inputData)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker? {
                    return SeriesSyncWorker(appContext, workerParameters, mockRepository)
                }
            })
            .build()

        // Act
        val result = worker.doWork()

        // Assert
        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify { mockRepository.syncSeriesDetail(seriesId) }
    }
}
