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
private fun f1(d: Double) = fmt("%.1f", d)

val PROTOCOLS = listOf(
    "خودکار", "J1850 PWM", "J1850 VPW", "ISO 9141-2", "KWP2000 (5 baud)", "KWP2000 (fast)",
    "CAN 11bit 500k", "CAN 29bit 500k", "CAN 11bit 250k", "CAN 29bit 250k",
)

/** تعریف پارامترهای استاندارد OBD-II (حالت 01). فقط موارد پشتیبانی‌شده توسط ECU نمایش داده می‌شوند. */
class PidDef(
    val key: String, val title: String, val unit: String, val pid: String,
    val min: Int, val dec: (IntArray) -> String?,
) {
    val num = pid.substring(2).toInt(16)
}

val PIDS = listOf(
    PidDef("temp", "دمای آب موتور", "°C", "0105", 1) { "${it[0] - 40}" },
    PidDef("load", "بار موتور", "%", "0104", 1) { "${it[0] * 100 / 255}" },
    PidDef("throttle", "دریچه گاز", "%", "0111", 1) { "${it[0] * 100 / 255}" },
    PidDef("iat", "دمای هوای ورودی", "°C", "010F", 1) { "${it[0] - 40}" },
    PidDef("maf", "هوای ورودی (MAF)", "g/s", "0110", 2) { f1((it[0] * 256 + it[1]) / 100.0) },
    PidDef("map", "فشار منیفولد (MAP)", "kPa", "010B", 1) { it[0].toString() },
    PidDef("stft", "اصلاح سوخت کوتاه‌مدت", "%", "0106", 1) { f1((it[0] - 128) * 100 / 128.0) },
    PidDef("ltft", "اصلاح سوخت بلندمدت", "%", "0107", 1) { f1((it[0] - 128) * 100 / 128.0) },
    PidDef("timing", "زمان‌بندی جرقه", "°", "010E", 1) { f1(it[0] / 2.0 - 64) },
    PidDef("fuelp", "فشار سوخت", "kPa", "010A", 1) { "${it[0] * 3}" },
    PidDef("frp", "فشار ریل سوخت (دیزل)", "kPa", "0123", 2) { "${(it[0] * 256 + it[1]) * 10}" },
    PidDef("o2", "سنسور اکسیژن B1S1", "V", "0114", 1) { fmt("%.2f", it[0] / 200.0) },
    PidDef("egr", "فرمان EGR", "%", "012C", 1) { "${it[0] * 100 / 255}" },
    PidDef("fuel", "سطح سوخت", "%", "012F", 1) { "${it[0] * 100 / 255}" },
    PidDef("baro", "فشار هوای محیط", "kPa", "0133", 1) { it[0].toString() },
    PidDef("cmv", "ولتاژ ECU", "V", "0142", 2) { f1((it[0] * 256 + it[1]) / 1000.0) },
    PidDef("ambient", "دمای محیط", "°C", "0146", 1) { "${it[0] - 40}" },
    PidDef("oil", "دمای روغن", "°C", "015C", 1) { "${it[0] - 40}" },
    PidDef("runtime", "زمان کارکرد موتور", "s", "011F", 2) { "${it[0] * 256 + it[1]}" },
)

class DiagState {
    var status by mutableStateOf("قطع")
    var connected by mutableStateOf(false)
    var demo by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var adapter by mutableStateOf("")
    var protocolSel by mutableIntStateOf(0)
    var protocolName by mutableStateOf("")
    var supported by mutableStateOf(setOf<String>())
    var supportedHex by mutableStateOf("")
    val vals = mutableStateMapOf("rpm" to "0", "speed" to "0", "volt" to "--")
    var rpmHist by mutableStateOf(List(80) { 0f })
    var speedHist by mutableStateOf(List(80) { 0f })
    var accel by mutableStateOf("IDLE") // IDLE READY RUNNING DONE
    var accelTime by mutableStateOf("0.00")
    var accelMsg by mutableStateOf("خودرو را متوقف کنید و دکمه آماده‌باش را بزنید")
    var dtcText by mutableStateOf("یکی از دکمه‌های خواندن خطا را بزنید.")
    var advText by mutableStateOf("برای شروع یکی از دکمه‌ها را بزنید. اگر ECU شناخته نشد، «گزارش تشخیص» را بگیرید.")
    var term by mutableStateOf("--- ELM327 Terminal (AT / Hex) ---\n")
}

private fun hexBytes(s: String): IntArray? = runCatching {
    IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }
}.getOrNull()

/** در پاسخ خام، خطی را که با want شروع می‌شود پیدا و بایت‌های بعد از آن را برمی‌گرداند. */
private fun bytesAfter(resp: String, want: String): IntArray? {
    for (line in resp.lines()) {
        val s = line.replace(" ", "").uppercase()
        if (s.startsWith(want) && s.length > want.length && (s.length - want.length) % 2 == 0) {
            hexBytes(s.substring(want.length))?.let { return it }
        }
    }
    return null
}

fun parseVin(r: String): String {
    val lines = r.lines().map { it.replace(" ", "").uppercase() }
    val multi = Regex("^[0-9]:")
    val hex = StringBuilder()
    if (lines.any { multi.containsMatchIn(it) }) {
        val joined = lines.filter { multi.containsMatchIn(it) }.joinToString("") { it.substringAfter(":") }
        hex.append(joined.removePrefix("490201"))
    } else {
        for (l in lines) if (l.startsWith("4902") && l.length > 6) hex.append(l.substring(6))
    }
    val sb = StringBuilder()
    var i = 0
    while (i + 2 <= hex.length) {
        val b = hex.substring(i, i + 2).toIntOrNull(16) ?: 0
        if (b in 0x20..0x7E) sb.append(b.toChar())
        i += 2
    }
    return sb.toString()
}

class Engine(private val ui: DiagState, private val scope: CoroutineScope) {
    private var link: ObdLink? = null
    private val mutex = Mutex()
    private var job: Job? = null

    private suspend fun cmd(c: String, t: Int = 1500): String = mutex.withLock {
        withContext(Dispatchers.IO) { link?.send(c, t) ?: "" }
    }

    private suspend fun pid(p: String): IntArray? =
        bytesAfter(cmd(p, 1200), "41" + p.substring(2))

    private suspend fun isCan(): Boolean =
        cmd("ATDPN", 1000).trim().removePrefix("A").firstOrNull()?.let { it in '6'..'9' } ?: false

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
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) cmd(c, 1000)
        ui.adapter = cmd("ATI", 1000).lines().firstOrNull() ?: ""
        ui.connected = true
        startSession()
    }

    /** پس از تغییر پروتکل، جستجوی ECU را دوباره انجام می‌دهد. */
    fun renegotiate() {
        if (link != null) startSession()
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
        ui.supported = emptySet(); ui.protocolName = ""
    }

    private fun startSession() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                if (negotiate()) {
                    ui.protocolName = cmd("ATDP", 1000)
                    val sup = scanSupported()
                    ui.supportedHex = sup.sorted().joinToString(" ") { fmt("%02X", it) }
                    ui.supported = if (sup.isEmpty()) emptySet()
                    else PIDS.filter { it.num in sup }.map { it.key }.toSet()
                }
                pollLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ui.status = "ارتباط با کابل قطع شد"
                stopInternal()
            } catch (e: Exception) {
                ui.status = "خطا: ${e.message}"
            }
        }
    }

    private suspend fun negotiate(): Boolean {
        val sel = ui.protocolSel
        val order = if (sel != 0) listOf(sel) else listOf(0, 6, 8, 7, 9, 5, 4, 3, 1, 2)
        for (p in order) {
            ui.status = if (p == 0) "جستجوی خودکار پروتکل..." else "تست پروتکل: ${PROTOCOLS[p]}"
            cmd("ATSP$p", 1000)
            val r = cmd("0100", if (p == 0 || p in 3..5) 15000 else 6000)
            if (r.lines().any { it.replace(" ", "").uppercase().startsWith("4100") }) return true
        }
        ui.status = "ECU پاسخ نداد؛ در تب «پیشرفته» گزارش تشخیص بگیرید"
        return false
    }

    private suspend fun scanSupported(): Set<Int> {
        val out = mutableSetOf<Int>()
        var base = 0
        while (base <= 0xC0) {
            val a = pid("01" + fmt("%02X", base)) ?: break
            if (a.size < 4) break
            for (i in 0 until 32) if (((a[i / 8] shr (7 - i % 8)) and 1) == 1) out += base + i + 1
            if ((a[3] and 1) == 0) break
            base += 0x20
        }
        return out
    }

    // ---------- خواندن زنده ----------
    private suspend fun pollLoop() {
        var i = 0; var fails = 0
        var prevT = 0L; var prevS = -1; var t0 = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val accelMode = ui.accel == "READY" || ui.accel == "RUNNING"
            val sp = pid("010D")
            val t = System.nanoTime()
            if (sp == null) {
                if (++fails > 10 && ui.status.startsWith("متصل")) ui.status = "ECU پاسخ نمی‌دهد (سوئیچ را باز کنید)"
                delay(200); continue
            }
            fails = 0
            ui.status = if (ui.demo) "حالت شبیه‌ساز فعال" else "متصل ${ui.protocolName}"
            val s = sp[0]
            ui.vals["speed"] = s.toString()
            ui.speedHist = ui.speedHist.drop(1) + s.toFloat()

            // ----- تست شتاب: زمان‌گیری با درون‌یابی -----
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

            if (!accelMode) {
                pid("010C")?.let {
                    if (it.size >= 2) {
                        val r = (it[0] * 256 + it[1]) / 4
                        ui.vals["rpm"] = r.toString()
                        ui.rpmHist = ui.rpmHist.drop(1) + r.toFloat()
                    }
                }
                val active = PIDS.filter { ui.supported.isEmpty() || it.key in ui.supported }
                if (active.isNotEmpty()) {
                    val d = active[i++ % active.size]
                    pid(d.pid)?.let { b -> if (b.size >= d.min) d.dec(b)?.let { v -> ui.vals[d.key] = v } }
                }
                if (i % 12 == 0) {
                    Regex("[0-9]+\\.?[0-9]*").find(cmd("ATRV", 800))?.value?.let { ui.vals["volt"] = it }
                }
                delay(30)
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
    /** mode: 03 ذخیره‌شده، 07 در انتظار، 0A دائمی */
    fun readDtcs(mode: String, title: String) {
        scope.launch(Dispatchers.IO) {
            if (link == null) { ui.dtcText = "❌ ابتدا به خودرو متصل شوید."; return@launch }
            ui.dtcText = "در حال خواندن خطاهای $title ..."
            try {
                val can = isCan()
                val r = cmd(mode, 6000)
                val codes = parseDtcs(r, can, fmt("%02X", mode.toInt(16) + 0x40))
                ui.dtcText = when {
                    codes.isNotEmpty() -> buildString {
                        append("⚠️ تعداد ${codes.size} خطای $title:\n\n")
                        for (c in codes) append("🔴 $c\n${describeDtc(c)}\n\n")
                    }
                    r.contains("UNABLE", true) || r.contains("ERROR", true) ->
                        "❌ ارتباط با ECU برقرار نشد:\n$r"
                    r.contains("?") -> "⚠️ این ECU از این دستور پشتیبانی نمی‌کند."
                    else -> "✅ خطای $title یافت نشد."
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

    // ---------- امکانات پیشرفته ----------
    fun runReport() {
        scope.launch(Dispatchers.IO) {
            if (link == null) { ui.advText = "❌ ابتدا متصل شوید."; return@launch }
            ui.advText = "در حال تهیه گزارش (ممکن است تا یک دقیقه طول بکشد)..."
            val sb = StringBuilder("📋 گزارش تشخیص ECU\n(این متن را کپی و برای پشتیبانی بفرستید)\n\n")
            try {
                for ((c, t) in listOf(
                    "ATI" to 1500, "AT@1" to 1500, "ATRV" to 1500, "ATDPN" to 1500, "ATDP" to 1500,
                    "0100" to 15000, "0120" to 4000, "0140" to 4000, "0160" to 4000,
                    "0101" to 3000, "0902" to 5000, "03" to 5000,
                )) {
                    sb.append("> $c\n").append(cmd(c, t).ifEmpty { "(no response)" }).append("\n\n")
                }
            } catch (e: Exception) {
                sb.append("خطا: ${e.message}")
            }
            ui.advText = sb.toString()
        }
    }

    fun readVin() {
        scope.launch(Dispatchers.IO) {
            if (link == null) { ui.advText = "❌ ابتدا متصل شوید."; return@launch }
            ui.advText = "در حال خواندن VIN..."
            try {
                val r = cmd("0902", 6000)
                val vin = parseVin(r)
                ui.advText = if (vin.length >= 11) "🔖 شماره شاسی (VIN):\n$vin"
                else "⚠️ این ECU شماره شاسی را از طریق OBD نمی‌دهد.\nپاسخ خام:\n$r"
            } catch (e: Exception) { ui.advText = "❌ خطا: ${e.message}" }
        }
    }

    fun readMil() {
        scope.launch(Dispatchers.IO) {
            if (link == null) { ui.advText = "❌ ابتدا متصل شوید."; return@launch }
            try {
                val a = pid("0101")
                if (a == null) { ui.advText = "⚠️ این ECU وضعیت چراغ چک را گزارش نمی‌کند."; return@launch }
                val sb = StringBuilder()
                sb.append(if (a[0] and 0x80 != 0) "🔴 چراغ چک: روشن\n" else "🟢 چراغ چک: خاموش\n")
                sb.append("تعداد خطای ذخیره‌شده: ${a[0] and 0x7F}\n")
                pid("0121")?.takeIf { it.size >= 2 }?.let { sb.append("مسافت با چراغ روشن: ${it[0] * 256 + it[1]} km\n") }
                pid("0131")?.takeIf { it.size >= 2 }?.let { sb.append("مسافت از آخرین پاک کردن خطا: ${it[0] * 256 + it[1]} km\n") }
                ui.advText = sb.toString()
            } catch (e: Exception) { ui.advText = "❌ خطا: ${e.message}" }
        }
    }

    fun readFreeze() {
        scope.launch(Dispatchers.IO) {
            if (link == null) { ui.advText = "❌ ابتدا متصل شوید."; return@launch }
            ui.advText = "در حال خواندن Freeze Frame..."
            try {
                val sb = StringBuilder("📸 Freeze Frame (وضعیت لحظه‌ی ثبت خطا)\n\n")
                val dtc = bytesAfter(cmd("020200", 3000), "420200")
                if (dtc == null || dtc.size < 2 || (dtc[0] == 0 && dtc[1] == 0)) {
                    ui.advText = "ℹ️ Freeze Frame ذخیره نشده یا ECU آن را پشتیبانی نمی‌کند."
                    return@launch
                }
                sb.append("خطای عامل: ${decodeDtc(dtc[0], dtc[1])}\n${describeDtc(decodeDtc(dtc[0], dtc[1]))}\n\n")
                for ((p, name) in listOf(
                    "04" to "بار موتور %", "05" to "دمای آب °C", "0B" to "فشار MAP kPa",
                    "0C" to "دور موتور RPM", "0D" to "سرعت km/h", "0F" to "دمای هوای ورودی °C", "11" to "دریچه گاز %",
                )) {
                    val b = bytesAfter(cmd("02${p}00", 2000), "42${p}00") ?: continue
                    val v = when (p) {
                        "04", "11" -> b[0] * 100 / 255
                        "05", "0F" -> b[0] - 40
                        "0C" -> if (b.size >= 2) (b[0] * 256 + b[1]) / 4 else continue
                        else -> b[0]
                    }
                    sb.append("$name: $v\n")
                }
                ui.advText = sb.toString()
            } catch (e: Exception) { ui.advText = "❌ خطا: ${e.message}" }
        }
    }

    fun showPids() {
        ui.advText = if (ui.supportedHex.isEmpty())
            "هنوز پارامتری از ECU خوانده نشده. ابتدا متصل شوید و پروتکل درست را انتخاب کنید."
        else "PIDهای استاندارد پشتیبانی‌شده توسط ECU (هگز):\n${ui.supportedHex}\n\n" +
            "پارامترهایی که در داشبورد نمایش داده می‌شوند: ${PIDS.filter { it.key in ui.supported }.joinToString("، ") { it.title }}"
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
