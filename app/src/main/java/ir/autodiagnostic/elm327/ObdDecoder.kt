package ir.autodiagnostic.elm327

import java.util.Locale

object ObdDecoder {
    fun compactHex(response: String): String = response.uppercase(Locale.US)
        .replace(Regex("[^0-9A-F]"), "")

    fun payload(response: String, responseMode: String, pid: String? = null): List<Int>? {
        val compact = compactHex(response)
        val marker = responseMode.uppercase(Locale.US) + (pid?.uppercase(Locale.US) ?: "")
        val start = compact.indexOf(marker)
        if (start < 0) return null
        val body = compact.substring(start + marker.length)
        val bytes = ArrayList<Int>()
        var index = 0
        while (index + 2 <= body.length) {
            val token = body.substring(index, index + 2)
            val value = token.toIntOrNull(16) ?: break
            bytes += value
            index += 2
        }
        return bytes
    }

    fun decodeDtc(response: String, service: String, state: String): List<TroubleCode> {
        val positiveMode = when (service.uppercase(Locale.US)) {
            "03" -> "43"
            "07" -> "47"
            "0A" -> "4A"
            else -> return emptyList()
        }
        val bytes = payload(response, positiveMode) ?: return emptyList()
        val result = ArrayList<TroubleCode>()
        var i = 0
        while (i + 1 < bytes.size) {
            val first = bytes[i]
            val second = bytes[i + 1]
            i += 2
            if (first == 0 && second == 0) continue
            val letter = when ((first shr 6) and 0x03) {
                0 -> 'P'
                1 -> 'C'
                2 -> 'B'
                else -> 'U'
            }
            val code = buildString {
                append(letter)
                append((first shr 4) and 0x03)
                append(first and 0x0F)
                append((second shr 4) and 0x0F)
                append(second and 0x0F)
            }
            result += TroubleCode(code, DtcCatalog.category(code), DtcCatalog.description(code), state)
        }
        return result.distinctBy { it.code }
    }

    fun supportedPids(response: String, basePid: Int = 0): Set<Int> {
        val pidMarker = basePid.toString(16).padStart(2, '0').uppercase(Locale.US)
        val bytes = payload(response, "41", pidMarker) ?: return emptySet()
        if (bytes.size < 4) return emptySet()
        val result = mutableSetOf<Int>()
        for (bit in 0 until 32) {
            val byteIndex = bit / 8
            val bitInByte = 7 - (bit % 8)
            if ((bytes[byteIndex] and (1 shl bitInByte)) != 0) result += basePid + bit + 1
        }
        return result
    }

    fun readiness(response: String): String? {
        val bytes = payload(response, "41", "01") ?: return null
        if (bytes.size < 4) return null
        val milOn = (bytes[0] and 0x80) != 0
        val dtcCount = bytes[0] and 0x7F
        val milText = if (milOn) "روشن" else "خاموش"
        return "چراغ چک: $milText  |  تعداد کدهای خطای آلایندگی: ${persianDigits(dtcCount.toString())}"
    }

    fun vin(response: String): String? {
        val compact = compactHex(response)
        val start = compact.indexOf("4902")
        if (start < 0) return null
        var body = compact.substring(start + 4)
        // در پاسخ چندبخشی CAN، اولین بایت پس از 49 02 شماره بخش است.
        if (body.startsWith("01") && body.length >= 2) body = body.substring(2)
        val chars = StringBuilder()
        var i = 0
        while (i + 2 <= body.length && chars.length < 17) {
            val token = body.substring(i, i + 2)
            val value = token.toIntOrNull(16) ?: break
            if (value in 0x30..0x5A) chars.append(value.toChar())
            i += 2
        }
        return chars.toString().takeIf { it.length == 17 && it.all { ch -> ch.isLetterOrDigit() } }
    }

    private fun persianDigits(input: String): String = input.map { ch ->
        if (ch in '0'..'9') ('۰'.code + (ch - '0')).toChar() else ch
    }.joinToString("")

    fun format(value: Double, decimals: Int = 1): String = when {
        value.isNaN() || value.isInfinite() -> "—"
        else -> String.format(Locale.US, "%1$.${decimals}f", value)
    }
}
