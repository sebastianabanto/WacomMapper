package com.example.wacommapper.output

import java.util.ArrayDeque

data class LatencySummary(
    val averageMillis: Float = 0f,
    val p50Millis: Float = 0f,
    val p95Millis: Float = 0f,
    val maxMillis: Float = 0f,
)

/** Bounded monotonic latency samples; p50/p95 are computed from the most recent 512 outputs. */
class LatencyWindow(private val capacity: Int = 512) {
    private val samples = ArrayDeque<Float>()

    @Synchronized
    fun add(nanos: Long): LatencySummary {
        samples.addLast((nanos.coerceAtLeast(0L) / 1_000_000f))
        while (samples.size > capacity) samples.removeFirst()
        return summary()
    }

    @Synchronized
    fun summary(): LatencySummary {
        if (samples.isEmpty()) return LatencySummary()
        val sorted = samples.sorted()
        fun percentile(fraction: Double): Float {
            val index = kotlin.math.ceil(fraction * sorted.size).toInt().coerceAtLeast(1) - 1
            return sorted[index.coerceIn(sorted.indices)]
        }
        return LatencySummary(
            averageMillis = sorted.average().toFloat(),
            p50Millis = percentile(0.50),
            p95Millis = percentile(0.95),
            maxMillis = sorted.last(),
        )
    }

    @Synchronized
    fun clear() = samples.clear()
}
