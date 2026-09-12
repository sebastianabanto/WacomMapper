package com.example.wacommapper.usb

enum class Ctl472ReportType {
    TABLET_REPORT,
    OUT_OF_RANGE,
    GENERIC_REPORT,
    INVALID,
}

/** Physical HID fields only. Contact/tip interpretation intentionally lives elsewhere. */
data class Ctl472Report(
    val type: Ctl472ReportType,
    val reportId: Int?,
    val statusByte: Int?,
    val statusBit0: Boolean?,
    val x: Int?,
    val y: Int?,
    val pressure: Int?,
    val sideButton1: Boolean,
    val sideButton2: Boolean,
    val eraser: Boolean,
    val nearProximity: Boolean,
    val hoverDistance: Int?,
    val unknownByte9: Int?,
    val raw: ByteArray,
) {
    val rawHex: String get() = raw.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    val xInReferenceRange: Boolean? get() = x?.let { it in 0..MAX_X }
    val yInReferenceRange: Boolean? get() = y?.let { it in 0..MAX_Y }
    val pressureInReferenceRange: Boolean? get() = pressure?.let { it in 0..MAX_PRESSURE }

    companion object {
        const val REPORT_LENGTH = 10
        const val REPORT_ID = 0x02
        const val MAX_X = 15_200
        const val MAX_Y = 9_500
        const val MAX_PRESSURE = 2_047
    }
}

/** Decodes the CTL-472's 10-byte report layout used by OpenTabletDriver 0.6.7. */
class Ctl472RawReportParser {
    fun parse(raw: ByteArray): Ctl472Report {
        val bytes = raw.copyOf()
        if (bytes.size != Ctl472Report.REPORT_LENGTH) {
            return empty(Ctl472ReportType.INVALID, bytes)
        }

        fun u8(index: Int) = bytes[index].toInt() and 0xFF
        val reportId = u8(0)
        val status = u8(1)
        if (reportId != Ctl472Report.REPORT_ID) {
            return empty(Ctl472ReportType.GENERIC_REPORT, bytes, reportId, status)
        }

        // Preserve OTD v0.6.7 order: DetectMask (0x40) takes precedence over exact 0x80.
        if ((status and DETECT_MASK) != 0) {
            return Ctl472Report(
                type = Ctl472ReportType.TABLET_REPORT,
                reportId = reportId,
                statusByte = status,
                statusBit0 = (status and 0x01) != 0,
                x = u8(2) or (u8(3) shl 8),
                y = u8(4) or (u8(5) shl 8),
                pressure = u8(6) or (u8(7) shl 8),
                sideButton1 = (status and 0x02) != 0,
                sideButton2 = (status and 0x04) != 0,
                eraser = (status and 0x08) != 0,
                nearProximity = (status and 0x80) != 0,
                hoverDistance = u8(8),
                unknownByte9 = u8(9),
                raw = bytes,
            )
        }

        if (status == OUT_OF_RANGE_STATUS) {
            return Ctl472Report(
                type = Ctl472ReportType.OUT_OF_RANGE,
                reportId = reportId,
                statusByte = status,
                statusBit0 = false,
                x = null,
                y = null,
                pressure = 0,
                sideButton1 = false,
                sideButton2 = false,
                eraser = false,
                nearProximity = false,
                hoverDistance = null,
                unknownByte9 = u8(9),
                raw = bytes,
            )
        }

        return empty(Ctl472ReportType.GENERIC_REPORT, bytes, reportId, status)
    }

    private fun empty(type: Ctl472ReportType, raw: ByteArray, reportId: Int? = null, status: Int? = null) =
        Ctl472Report(
            type = type,
            reportId = reportId,
            statusByte = status,
            statusBit0 = status?.let { (it and 1) != 0 },
            x = null,
            y = null,
            pressure = null,
            sideButton1 = false,
            sideButton2 = false,
            eraser = false,
            nearProximity = false,
            hoverDistance = null,
            unknownByte9 = raw.getOrNull(9)?.toInt()?.and(0xFF),
            raw = raw,
        )

    companion object {
        const val DETECT_MASK = 0x40
        const val OUT_OF_RANGE_STATUS = 0x80
    }
}
