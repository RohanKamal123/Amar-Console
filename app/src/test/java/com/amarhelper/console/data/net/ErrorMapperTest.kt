package com.amarhelper.console.data.net

import com.amarhelper.console.core.result.AppError
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMapperTest {

    @Test
    fun `every documented status maps to its own error`() {
        assertTrue(ErrorMapper.fromStatus(401) is AppError.Unauthorized)
        assertTrue(ErrorMapper.fromStatus(403) is AppError.Forbidden)
        assertTrue(ErrorMapper.fromStatus(404) is AppError.NotFound)
        assertTrue(ErrorMapper.fromStatus(408) is AppError.Timeout)
        assertTrue(ErrorMapper.fromStatus(409) is AppError.Conflict)
        assertTrue(ErrorMapper.fromStatus(429) is AppError.RateLimited)
        assertTrue(ErrorMapper.fromStatus(500) is AppError.ServerError)
        assertTrue(ErrorMapper.fromStatus(502) is AppError.ServerError)
        assertTrue(ErrorMapper.fromStatus(503) is AppError.ServerError)
        assertTrue(ErrorMapper.fromStatus(504) is AppError.ServerError)
    }

    @Test
    fun `unknown status falls through to a generic http error`() {
        val error = ErrorMapper.fromStatus(418)
        assertTrue(error is AppError.Http)
        assertEquals(418, (error as AppError.Http).code)
    }

    @Test
    fun `auth failures are not retryable but server failures are`() {
        assertFalse(ErrorMapper.fromStatus(401).retryable)
        assertFalse(ErrorMapper.fromStatus(403).retryable)
        assertTrue(ErrorMapper.fromStatus(503).retryable)
        assertTrue(ErrorMapper.fromStatus(429).retryable)
    }

    @Test
    fun `transport failures map to offline or timeout`() {
        assertTrue(ErrorMapper.fromThrowable(UnknownHostException("no dns")) is AppError.Offline)
        assertTrue(ErrorMapper.fromThrowable(ConnectException("refused")) is AppError.Offline)
        assertTrue(ErrorMapper.fromThrowable(IOException("broken pipe")) is AppError.Offline)
        assertTrue(ErrorMapper.fromThrowable(SocketTimeoutException()) is AppError.Timeout)
    }

    @Test
    fun `a cleartext block is not reported as a VPN problem`() {
        val error = ErrorMapper.fromThrowable(
            java.net.UnknownServiceException("CLEARTEXT communication to 100.87.52.65 not permitted"),
        )
        assertTrue(error.message.contains("plain-HTTP"))
        assertFalse(error.message.contains("VPN"))
    }

    @Test
    fun `cancellation is not reported as a failure of the backend`() {
        assertEquals(AppError.Cancelled, ErrorMapper.fromThrowable(CancellationException("scope closed")))
    }

    @Test
    fun `error messages never echo raw response bodies`() {
        val message = ErrorMapper.fromStatus(401).message
        assertFalse(message.contains("Bearer"))
        assertTrue(message.contains("Settings"))
    }
}
