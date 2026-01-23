package dev.flagkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorCodeTest {
    @Test
    fun `test error code values`() {
        assertEquals("INIT_FAILED", ErrorCode.INIT_FAILED.code)
        assertEquals("INIT_ALREADY_INITIALIZED", ErrorCode.INIT_ALREADY_INITIALIZED.code)
        assertEquals("AUTH_INVALID_KEY", ErrorCode.AUTH_INVALID_KEY.code)
        assertEquals("NETWORK_ERROR", ErrorCode.NETWORK_ERROR.code)
        assertEquals("CIRCUIT_OPEN", ErrorCode.CIRCUIT_OPEN.code)
    }

    @Test
    fun `test recoverable errors`() {
        assertTrue(ErrorCode.NETWORK_ERROR.isRecoverable)
        assertTrue(ErrorCode.NETWORK_TIMEOUT.isRecoverable)
        assertTrue(ErrorCode.CIRCUIT_OPEN.isRecoverable)
        assertTrue(ErrorCode.CACHE_EXPIRED.isRecoverable)
        assertTrue(ErrorCode.EVAL_STALE_VALUE.isRecoverable)
    }

    @Test
    fun `test non-recoverable errors`() {
        assertFalse(ErrorCode.INIT_FAILED.isRecoverable)
        assertFalse(ErrorCode.AUTH_INVALID_KEY.isRecoverable)
        assertFalse(ErrorCode.CONFIG_INVALID_API_KEY.isRecoverable)
        assertFalse(ErrorCode.EVAL_FLAG_NOT_FOUND.isRecoverable)
    }
}
