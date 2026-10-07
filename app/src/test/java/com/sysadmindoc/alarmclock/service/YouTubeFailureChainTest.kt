package com.sysadmindoc.alarmclock.service

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class YouTubeFailureChainTest {
    @Test fun suppressedPrimaryIsIncludedAndClassified() {
        val error = IllegalStateException("fallback").apply { addSuppressed(UnknownHostException("private-value")) }
        assertEquals(2, youTubeFailureChain(error).size)
        assertEquals("dns", YouTubeFailureDiagnostics.category(error))
    }
    @Test fun cyclesAreBounded() {
        val a = Exception("a")
        val b = Exception("b")
        a.initCause(b); b.initCause(a)
        assertEquals(2, youTubeFailureChain(a).size)
    }
    @Test fun missingNativeRuntimeIsNotExtractorAdvice() {
        assertEquals("runtime", YouTubeFailureDiagnostics.category(Exception("Cannot link executable")))
    }
}
