package ir.autodiagnostic.elm327

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDecoderTest {
    @Test
    fun decodesRpmPayload() {
        val bytes = ObdDecoder.payload("010C\r41 0C 1A F8\r>", "41", "0C")
        assertEquals(listOf(0x1A, 0xF8), bytes)
        val rpm = ((bytes!![0] * 256) + bytes[1]) / 4.0
        assertEquals(1726.0, rpm, 0.001)
    }

    @Test
    fun decodesPowertrainDtc() {
        val codes = ObdDecoder.decodeDtc("43 01 33 00 00", "03", "ذخیره‌شده")
        assertEquals(1, codes.size)
        assertEquals("P0133", codes.first().code)
        assertTrue(codes.first().description.contains("کند"))
    }

    @Test
    fun decodesPendingAndPermanentCodes() {
        assertEquals("P0300", ObdDecoder.decodeDtc("47 03 00", "07", "موقت").first().code)
        assertEquals("P0420", ObdDecoder.decodeDtc("4A 04 20", "0A", "دائمی").first().code)
    }

    @Test
    fun decodesSupportedPidBitmapPages() {
        val firstPage = ObdDecoder.supportedPids("41 00 BE 3E B8 13")
        assertTrue(0x04 in firstPage)
        assertTrue(0x0C in firstPage)
        assertTrue(0x20 in firstPage)
        val secondPage = ObdDecoder.supportedPids("41 20 80 00 00 01", 0x20)
        assertTrue(0x21 in secondPage)
        assertTrue(0x40 in secondPage)
    }

    @Test
    fun readinessReportsMilAndDtcCount() {
        val status = ObdDecoder.readiness("41 01 82 07 65 00")
        assertTrue(status!!.contains("روشن"))
        assertTrue(status.contains("۲"))
    }

    @Test
    fun decodesVinFromMode09Response() {
        val vin = ObdDecoder.vin("49 02 01 31 48 47 43 4D 38 32 36 33 33 41 30 30 30 30 30 31")
        assertEquals("1HGCM82633A000001", vin)
    }


    @Test
    fun rendersTroubleCodeInPersianWhilePreservingStandardCode() {
        assertEquals("پی‌۰۱۳۳", DtcCatalog.persianCode("P0133"))
        assertEquals("سی‌۰۱۲۳", DtcCatalog.persianCode("C0123"))
        assertEquals("P0133", ObdDecoder.decodeDtc("43 01 33", "03", "ذخیره‌شده").first().code)
    }

    @Test
    fun knownDtcHasPersianCausesAndRecommendedChecks() {
        val info = DtcCatalog.explanation("P0420")
        assertTrue(info.description.contains("کاتالیست"))
        assertTrue(info.possibleCauses.isNotBlank())
        assertTrue(info.recommendedChecks.isNotBlank())
    }

    @Test
    fun urgentOverheatCodeIsMarkedAsImmediate() {
        assertEquals("فوری", DtcCatalog.explanation("P0217").urgency)
    }

    @Test
    fun unknownDtcDoesNotInventDescription() {
        assertTrue(DtcCatalog.description("P9999").contains("موجود نیست"))
        assertFalse(DtcCatalog.description("P0420").contains("موجود نیست"))
        assertNull(ObdDecoder.readiness("NO DATA"))
    }
}
