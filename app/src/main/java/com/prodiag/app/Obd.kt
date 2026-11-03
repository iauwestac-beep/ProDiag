package com.prodiag.app

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlin.math.min
import kotlin.math.sin

interface ObdLink {
    fun send(cmd: String, timeoutMs: Int = 1500): String
    fun close()
}

private fun clean(s: String): String =
    s.replace(">", "").replace("\r", "\n").lines()
        .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

/** ارتباط واقعی با ELM327 از طریق USB-Serial (CH340 / FTDI / CP210x / PL2303). */
class UsbElmLink(private val port: UsbSerialPort) : ObdLink {
    private val buf = ByteArray(512)

    override fun send(cmd: String, timeoutMs: Int): String {
        var guard = 0
        while (guard++ < 20 && port.read(buf, 10) > 0) { /* خالی کردن بافر ورودی */ }
        port.write((cmd + "\r").toByteArray(), 1000)
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val n = port.read(buf, 100)
            if (n > 0) {
                sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                if (sb.indexOf(">") >= 0) break
            }
        }
        return clean(sb.toString())
    }

    override fun close() {
        runCatching { port.close() }
    }
}

/** شبیه‌ساز: همان پروتکل ELM327 را بدون سخت‌افزار تقلید می‌کند. */
class DemoLink : ObdLink {
    @Volatile private var armed = false
    @Volatile private var launchAt = 0L
    private val t0 = System.nanoTime()

    fun arm() { armed = true; launchAt = System.nanoTime() + 1_500_000_000L }
    fun disarm() { armed = false }

    private fun speed(): Int {
        val now = System.nanoTime()
        return if (armed) {
            if (now < launchAt) 0 else min(140, ((now - launchAt) / 1e9 * 13).toInt())
        } else (60 + 40 * sin((now - t0) / 1e9 / 3)).toInt()
    }

    override fun send(cmd: String, timeoutMs: Int): String {
        Thread.sleep(40)
        return when (cmd) {
            "ATZ" -> "ELM327 v1.5 (Demo)"
            "ATI" -> "ELM327 v1.5 (Demo)"
            "ATRV" -> "13.9V"
            "ATDPN" -> "A3"
            "ATDP" -> "AUTO, ISO 9141-2 (Demo)"
            "AT@1" -> "DEMO ELM327"
            "0100" -> "4100BE3FA813"
            "0101" -> "41018107E500"
            "010D" -> fmt("410D%02X", speed())
            "010C" -> fmt("410C%04X", (900 + speed() * 50) * 4)
            "0105" -> "41057D"
            "0111" -> "41111E"
            "0104" -> "41043C"
            "0106" -> "410680"
            "0107" -> "410784"
            "010E" -> "410E90"
            "010F" -> "410F4B"
            "0110" -> "411001F4"
            "010B" -> "410B23"
            "011F" -> "411F012C"
            "0121" -> "NO DATA"
            "03" -> "43010503000000"
            "07" -> "47030100000000"
            "0A" -> "NO DATA"
            "04" -> "44"
            "0902" -> "49020100000044\n490202454D4F56\n490203494E3132\n49020433343536\n49020537383930"
            "020200" -> "4202000105"
            "020400" -> "4204003C"
            "020500" -> "4205007D"
            "020B00" -> "420B0023"
            "020C00" -> "420C000FA0"
            "020D00" -> "420D0000"
            "020F00" -> "420F004B"
            "021100" -> "4211001E"
            else -> if (cmd.startsWith("AT")) "OK" else "?"
        }
    }

    override fun close() {}
}
