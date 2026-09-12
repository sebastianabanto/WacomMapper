package com.example.wacommapper.output

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyWindowTest {
    @Test fun computesBoundedMonotonicPercentiles() {
        val window = LatencyWindow(capacity = 4)
        window.add(1_000_000L)
        window.add(2_000_000L)
        window.add(3_000_000L)
        window.add(4_000_000L)
        val result = window.add(5_000_000L)
        assertEquals(3.5f, result.averageMillis, 0.001f)
        assertEquals(3f, result.p50Millis, 0.001f)
        assertEquals(5f, result.p95Millis, 0.001f)
        assertEquals(5f, result.maxMillis, 0.001f)
    }
}
