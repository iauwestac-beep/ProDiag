package com.prodiag.app

import android.hardware.usb.UsbDeviceConnection
import androidx.compose.runtime.*
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Locale
import kotlin.math.max

fun fmt(f: String, vararg a: Any): String = String.format(Locale.US, f, *a)

class DiagState {
    var status by mutableStateOf("قطع")
    var connected by mutableStateOf(false)
    var demo by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var adapter by mutableStateOf("")
    val vals = mutableStateMapOf(
        "rpm" to "0", "speed" to "0", "temp" to "--", "throttle" to "--", "load" to "--",
        "iat" to "--", "maf" to "--", "map" to "--", "volt" to "--",
    )
    var rpmHist by mutableStateOf(List(80) { 0f })
    var speedHist by mutableStateOf(List(80) { 0f })
    var accel by mutableStateOf("IDLE") // IDLE READY RUNNING DONE
    var accelTime by mutableStateOf("0.00")
    var accelMsg by mutableStateOf("خودرو را متوقف کنید و دکمه آماده‌باش را بزنید")
    var dtcText by mutableStateOf("برای مشاهده خطاها دکمه «اسکن» را بزنید.")
    var term by mutableStateOf("--- ELM327 Terminal (AT / Hex) ---\n")
}

class Engine(private val ui: DiagState, private val scope: CoroutineScope) {
    private var link: ObdLink? = null
    private val mutex = Mutex()
    private var job: Job? = null

    private suspend fun cmd(c: String, t: Int = 1500): String = mutex.withLock {
        withContext(Dispatchers.IO) { link?.send(c, t) ?: "" }
    }

    private suspend fun pid(p: String): IntArray? {
        val want = "41" + p.substring(2)
        for (line in cmd(p, 1200).lines()) {
            val s = line.replace(" ", "").uppercase()
            if (s.startsWith(want) && (s.length - want.length) % 2 == 0 && s.length > want.length) {
                return runCatching {
                    IntArray((s.length - want.length) / 2) {
                        s.substring(want.length + it * 2, want.length + it * 2 + 2).toInt(16)
                    }
                }.getOrNull()
            }
        }
        return null
    }

    // ---------- اتصال ----------
    fun connectUsb(port: UsbSerialPort, conn: UsbDeviceConnection) {
        scope.launch(Dispatchers.IO) {
            ui.busy = true
            ui.status = "در حال اتصال..."
            try {
                stopInternal()
                port.open(conn)
                var found: UsbElmLink? = null
                for (baud in intArrayOf(38400, 115200, 9600, 57600)) {
                    port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    val l = UsbElmLink(port)
                    val r = l.send("ATZ", 2500)
                    if (r.contains("ELM", true) || r.contains("OBD", true)) { found = l; break }
                }
                if (found == null) {
                    runCatching { port.close() }
                    ui.status = "آداپتور پاسخ نداد (بادریت یا کابل را بررسی کنید)"
                    return@launch
                }
                link = found
                initAndStart()
            } catch (e: Exception) {
                ui.status = "خطا: ${e.message}"
                stopInternal()
            } finally {
                ui.busy = false
            }
        }
    }

    fun startDemo() {
        scope.launch(Dispatchers.IO) {
            stopInternal()
            link = DemoLink()
            ui.demo = true
            initAndStart()
        }
    }

    private suspend fun initAndStart() {
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) cmd(c, 1000)
        ui.adapter = cmd("ATI", 1000).lines().firstOrNull() ?: ""
        ui.status = "آداپتور متصل؛ در حال جستجوی ECU..."
        ui.connected = true
        cmd("0100", 10000) // شروع جستجوی پروتکل
        startPolling()
    }

    fun disconnect() {
        stopInternal()
        ui.status = "قطع"
    }

    private fun stopInternal() {
        job?.cancel(); job = null
        val l = link; link = null
        if (l != null) scope.launch(Dispatchers.IO) { l.close() }
        ui.connected = false; ui.demo = false; ui.accel = "IDLE"
    }

    // ---------- خواندن زنده ----------
    private fun startPolling() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            var i = 0; var fails = 0
            var prevT = 0L; var prevS = -1; var t0 = 0L
            val slow = listOf("temp", "throttle", "load", "iat", "maf", "map")
            while (isActive) {
                try {
                    val accelMode = ui.accel == "READY" || ui.accel == "RUNNING"
                    val sp = pid("010D")
                    val t = System.nanoTime()
                    if (sp == null) {
                        if (++fails > 10) ui.status = "ECU پاسخ نمی‌دهد (سوئیچ را باز کنید)"
                        delay(200); continue
                    }
                    fails = 0
                    if (ui.demo) ui.status = "حالت شبیه‌ساز فعال" else ui.status = "متصل"
                    val s = sp[0]
                    ui.vals["speed"] = s.toString()
                    ui.speedHist = ui.speedHist.drop(1) + s.toFloat()

                    // ----- تست شتاب: زمان‌گیری دقیق با درون‌یابی -----
                    if (ui.accel == "READY" && s > 0) {
                        t0 = if (prevT != 0L) (prevT + t) / 2 else t
                        ui.accel = "RUNNING"
                        ui.accelMsg = "در حال شتاب‌گیری..."
                    }
                    if (ui.accel == "RUNNING") {
                        val el = (t - t0) / 1e9
                        if (s >= 100) {
                            val t100 = if (prevS in 0..99 && s > prevS)
                                prevT + ((100 - prevS).toDouble() / (s - prevS) * (t - prevT)).toLong() else t
                            val res = max(0.0, (t100 - t0) / 1e9)
                            ui.accelTime = fmt("%.2f", res)
                            ui.accel = "DONE"
                            ui.accelMsg = "رکورد ثبت شد: ${ui.accelTime} ثانیه"
                            (link as? DemoLink)?.disarm()
                        } else if (el > 60 || (s == 0 && el > 3)) {
                            ui.accel = "IDLE"; ui.accelMsg = "تست لغو شد. دوباره آماده‌باش بزنید."
                        } else ui.accelTime = fmt("%.2f", el)
                    }
                    prevT = t; prevS = s

                    if (!accelMode) { // در تست شتاب فقط سرعت پرسیده می‌شود
                        pid("010C")?.let {
                            if (it.size >= 2) {
                                val r = (it[0] * 256 + it[1]) / 4
                                ui.vals["rpm"] = r.toString()
                                ui.rpmHist = ui.rpmHist.drop(1) + r.toFloat()
                            }
                        }
                        when (slow[i++ % slow.size]) {
                            "temp" -> pid("0105")?.let { ui.vals["temp"] = (it[0] - 40).toString() }
                            "throttle" -> pid("0111")?.let { ui.vals["throttle"] = (it[0] * 100 / 255).toString() }
                            "load" -> pid("0104")?.let { ui.vals["load"] = (it[0] * 100 / 255).toString() }
                            "iat" -> pid("010F")?.let { ui.vals["iat"] = (it[0] - 40).toString() }
                            "maf" -> pid("0110")?.let {
                                if (it.size >= 2) ui.vals["maf"] = fmt("%.1f", (it[0] * 256 + it[1]) / 100.0)
                            }
                            "map" -> pid("010B")?.let { ui.vals["map"] = it[0].toString() }
                        }
                        if (i % 12 == 0) {
                            Regex("[0-9]+\\.?[0-9]*").find(cmd("ATRV", 800))?.value?.let { ui.vals["volt"] = it }
                        }
                        delay(30)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    ui.status = "ارتباط با کابل قطع شد"
                    stopInternal()
                    return@launch
                } catch (e: Exception) {
                    delay(300)
                }
            }
        }
    }

    // ---------- تست شتاب ----------
    fun armAccel() {
        if (link == null) { ui.accelMsg = "ابتدا متصل شوید یا حالت شبیه‌ساز را روشن کنید"; return }
        ui.accelTime = "0.00"
        ui.accel = "READY"
        ui.accelMsg = "آماده! با حرکت خودرو تایمر شروع می‌شود..."
        (link as? DemoLink)?.arm()
    }

    // ---------- خطاها ----------
    fun readDtcs() {
        scope.launch(Dispatchers.IO) {
            if (link == null) { ui.dtcText = "❌ ابتدا به خودرو متصل شوید."; return@launch }
            ui.dtcText = "در حال اسکن ایسیو..."
            try {
                val p = cmd("ATDPN", 1000).trim().removePrefix("A")
                val can = p.firstOrNull()?.let { it in '6'..'9' } ?: false
                val r = cmd("03", 6000)
                val codes = parseDtcs(r, can)
                ui.dtcText = when {
                    codes.isNotEmpty() -> buildString {
                        append("⚠️ تعداد ${codes.size} خطا یافت شد:\n\n")
                        for (c in codes) {
                            append("🔴 $c\n")
                            append(PERSIAN_DTC[c] ?: "ترجمه فارسی موجود نیست").append("\n\n")
                        }
                    }
                    r.contains("UNABLE", true) || r.contains("ERROR", true) ->
                        "❌ ارتباط با ECU برقرار نشد:\n$r"
                    else -> "✅ هیچ خطایی در حافظه ایسیو یافت نشد."
                }
            } catch (e: Exception) {
                ui.dtcText = "❌ خطا: ${e.message}"
            }
        }
    }

    fun clearDtcs() {
        scope.launch(Dispatchers.IO) {
            if (link == null) { ui.dtcText = "❌ ابتدا به خودرو متصل شوید."; return@launch }
            try {
                val r = cmd("04", 4000)
                ui.dtcText = if (r.contains("44")) "✅ خطاها پاک شدند. اگر عیب باقی باشد دوباره ثبت می‌شود."
                else "⚠️ پاسخ ECU: $r"
            } catch (e: Exception) {
                ui.dtcText = "❌ خطا: ${e.message}"
            }
        }
    }

    // ---------- ترمینال ----------
    fun sendRaw(c: String) {
        if (c.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val r = try {
                if (link == null) "Not connected" else cmd(c, 3000).ifEmpty { "(no response)" }
            } catch (e: Exception) { "Error: ${e.message}" }
            ui.term += "> $c\n$r\n"
        }
    }
}
