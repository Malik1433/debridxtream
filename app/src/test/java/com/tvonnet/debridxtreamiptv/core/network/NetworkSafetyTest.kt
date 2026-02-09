package com.tvonnet.debridxtreamiptv.core.network

import io.mockk.coEvery
import io.mockk.mockk
import com.tvonnet.debridxtreamiptv.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@ExperimentalCoroutinesApi
class NetworkSafetyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `safeApiCall returns Success when API call is successful`() = runTest {
        // Given
        val mockData = "Test Data"
        val apiCall = mockk<suspend () -> Response<String>>()
        coEvery { apiCall() } returns Response.success(mockData)

        // When
        val result = safeApiCall(apiCall = apiCall)

        // Then
        assertTrue(result is NetworkResult.Success)
        assertEquals(mockData, (result as NetworkResult.Success).data)
    }

    @Test
    fun `safeApiCall returns Error when API call fails with 4xx`() = runTest {
        // Given
        val errorCode = 401
        val errorMsg = "Unauthorized"
        val apiCall = mockk<suspend () -> Response<String>>()
        coEvery { apiCall() } returns Response.error(errorCode, errorMsg.toResponseBody(null))

        // When
        val result = safeApiCall(apiCall = apiCall)

        // Then
        assertTrue(result is NetworkResult.Error)
        val error = result as NetworkResult.Error
        assertEquals(errorCode, error.code)
        // Note: errorBody().string() consumes the stream, and MockK might not fully simulate OkHttp's behavior purely.
        // But logic should extract message/body. 
        // For simple test we verify code mapping.
    }

    @Test
    fun `safeApiCall returns Exception when IOException occurs`() = runTest {
        // Given
        val exception = IOException("Network Failure")
        val apiCall = mockk<suspend () -> Response<String>>()
        coEvery { apiCall() } throws exception

        // When
        val result = safeApiCall(apiCall = apiCall)

        // Then
        assertTrue(result is NetworkResult.Exception)
        assertEquals(exception, (result as NetworkResult.Exception).exception)
    }
}
