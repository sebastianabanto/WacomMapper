package com.example.wacommapper

import com.example.wacommapper.output.LiveParsedTestSummary
import com.example.wacommapper.output.LiveWacomMetrics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveParsedTestSummaryTest {
    @Test
    fun finalSummaryUsesTheSingleFinalMetricsSnapshotAndTabletReportCount() {
        val finalPanelSnapshot = LiveWacomMetrics(
            hidReports = 1_177,
            tabletReports = 1_158,
            outOfRangeReports = 17,
            genericReports = 2,
            invalidReports = 0,
            parsedEvents = 1_177,
            xValueChanges = 1_110,
            yValueChanges = 1_090,
            usbErrors = 0,
        )

        val summary = LiveParsedTestSummary.from(finalPanelSnapshot)

        assertTrue(summary.passed)
        assertTrue(summary.tabletReports > 0)
        assertTrue(summary.xChanges > 0)
        assertTrue(summary.yChanges > 0)
        assertTrue(summary.usbErrors == 0L)
    }

    @Test
    fun staleSmallSnapshotCannotOverrideTheFinalSnapshot() {
        val staleSnapshot = LiveWacomMetrics(hidReports = 2, parsedEvents = 2)
        val finalSnapshot = LiveWacomMetrics(
            hidReports = 1_177,
            tabletReports = 1_158,
            xValueChanges = 1_110,
            yValueChanges = 1_090,
        )

        assertFalse(LiveParsedTestSummary.from(staleSnapshot).passed)
        assertTrue(LiveParsedTestSummary.from(finalSnapshot).passed)
    }

    @Test
    fun passRequiresTabletReportsBothAxisChangesAndNoUsbErrors() {
        assertFalse(LiveParsedTestSummary.from(LiveWacomMetrics(tabletReports = 1)).passed)
        assertFalse(LiveParsedTestSummary.from(LiveWacomMetrics(tabletReports = 1, xValueChanges = 1)).passed)
        assertFalse(LiveParsedTestSummary.from(LiveWacomMetrics(tabletReports = 1, xValueChanges = 1, yValueChanges = 1, usbErrors = 1)).passed)
        assertTrue(LiveParsedTestSummary.from(LiveWacomMetrics(tabletReports = 1, xValueChanges = 1, yValueChanges = 1)).passed)
    }
}
