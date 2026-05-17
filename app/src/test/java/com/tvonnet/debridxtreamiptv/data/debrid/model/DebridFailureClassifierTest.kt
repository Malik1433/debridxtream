package com.tvonnet.debridxtreamiptv.data.debrid.model

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException

class DebridFailureClassifierTest {

    @Test
    fun `429 is rate limited terminal failure`() {
        val failure = DebridFailureClassifier.classify(httpException(429, retryAfter = "90"))

        assertEquals(DebridFailureType.RATE_LIMITED, failure.type)
        assertEquals(90L, failure.retryAfterSeconds)
        assertTrue(failure.terminal)
        assertFalse(failure.retryable)
    }

    @Test
    fun `451 is legal restriction terminal failure`() {
        val failure = DebridFailureClassifier.classify(httpException(451))

        assertEquals(DebridFailureType.LEGAL_RESTRICTION, failure.type)
        assertTrue(failure.terminal)
    }

    @Test
    fun `copyright removal text is copyright blocked`() {
        val failure = DebridFailureClassifier.classify(
            IllegalStateException("Playback file was removed from debrid service due to copyright")
        )

        assertEquals(DebridFailureType.COPYRIGHT_BLOCKED, failure.type)
        assertTrue(failure.terminal)
    }

    @Test
    fun `network timeout remains retryable`() {
        val failure = DebridFailureClassifier.classify(SocketTimeoutException("timeout"))

        assertEquals(DebridFailureType.NETWORK, failure.type)
        assertTrue(failure.retryable)
        assertFalse(failure.terminal)
    }

    private fun httpException(code: Int, retryAfter: String? = null): HttpException {
        val body = "{}".toResponseBody("application/json".toMediaType())
        val responseBuilder = okhttp3.Response.Builder()
            .request(
                Request.Builder()
                    .url("https://api.real-debrid.com/rest/1.0/test")
                    .build()
            )
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("error")
        if (retryAfter != null) {
            responseBuilder.header("Retry-After", retryAfter)
        }
        return HttpException(Response.error<Any>(body, responseBuilder.build()))
    }
}
