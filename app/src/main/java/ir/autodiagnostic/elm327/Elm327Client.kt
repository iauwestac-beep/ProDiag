package ir.autodiagnostic.elm327

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

/** ارتباط سطح پایین با ELM327 از طریق پورت سریال USB؛ تمام فراخوانی‌ها باید سریالی باشند. */
class Elm327Client(
    private val port: UsbSerialPort,
    private val usbConnection: UsbDeviceConnection
) {
    var adapterDescription: String = "ELM327"
        private set
    var supportedPids: Set<Int> = emptySet()
        private set

    private val pidSpecs = listOf(
        PidSpec(0x04, "load", "بار محاسبه‌شده موتور", "%", 1) { d -> d[0] * 100.0 / 255.0 },
        PidSpec(0x05, "coolant", "دمای مایع خنک‌کننده", "°C", 1) { d -> (d[0] - 40).toDouble() },
        PidSpec(0x06, "stft", "اصلاح کوتاه‌مدت سوخت بانک ۱", "%", 1) { d -> (d[0] - 128) * 100.0 / 128.0 },
        PidSpec(0x07, "ltft", "اصلاح بلندمدت سوخت بانک ۱", "%", 1) { d -> (d[0] - 128) * 100.0 / 128.0 },
        PidSpec(0x0A, "fuelPressure", "فشار سوخت", "kPa", 1) { d -> d[0] * 3.0 },
        PidSpec(0x0B, "map", "فشار منیفولد هوا", "kPa", 1) { d -> d[0].toDouble() },
        PidSpec(0x0C, "rpm", "دور موتور", "rpm", 2) { d -> ((d[0] * 256) + d[1]) / 4.0 },
        PidSpec(0x0D, "speed", "سرعت خودرو", "km/h", 1) { d -> d[0].toDouble() },
        PidSpec(0x0E, "timing", "آوانس جرقه", "درجه", 1) { d -> d[0] / 2.0 - 64.0 },
        PidSpec(0x0F, "intake", "دمای هوای ورودی", "°C", 1) { d -> (d[0] - 40).toDouble() },
        PidSpec(0x10, "maf", "دبی جرمی هوای ورودی", "g/s", 2) { d -> ((d[0] * 256) + d[1]) / 100.0 },
        PidSpec(0x11, "throttle", "موقعیت دریچه گاز", "%", 1) { d -> d[0] * 100.0 / 255.0 },
        PidSpec(0x1F, "runtime", "زمان کارکرد موتور", "ثانیه", 2) { d -> ((d[0] * 256) + d[1]).toDouble() },
        PidSpec(0x2F, "fuel", "سطح سوخت", "%", 1) { d -> d[0] * 100.0 / 255.0 },
        PidSpec(0x42, "moduleVoltage", "ولتاژ واحد کنترل", "V", 2) { d -> ((d[0] * 256) + d[1]) / 1000.0 },
        PidSpec(0x46, "ambient", "دمای هوای محیط", "°C", 1) { d -> (d[0] - 40).toDouble() },
        PidSpec(0x5C, "oil", "دمای روغن موتور", "°C", 1) { d -> (d[0] - 40).toDouble() },
        PidSpec(0x5E, "fuelRate", "مصرف لحظه‌ای سوخت موتور", "L/h", 2) { d -> ((d[0] * 256) + d[1]) / 20.0 }
    )

    @Throws(IOException::class)
    fun initialize(): String {
        // ATZ ممکن است چند ثانیه برای راه‌اندازی آداپتور زمان بخواهد.
        transact("ATZ", 3500)
        transact("ATE0", 1000) // خاموش کردن بازتاب فرمان
        transact("ATL0", 1000) // حذف خط خالی اضافی
        transact("ATS1", 1000) // جداکننده فاصله بین بایت‌ها
        transact("ATH0", 1000) // حذف سرآیند CAN از پاسخ
        transact("ATSP0", 1200) // انتخاب خودکار پروتکل خودرو
        transact("ATAT1", 1000) // زمان‌بندی تطبیقی
        transact("ATST32", 1000) // مهلت پاسخ ECU
        adapterDescription = transact("ATI", 1200).lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("ELM", ignoreCase = true) || it.contains("OBD", ignoreCase = true) }
            ?: "آداپتور سازگار با ELM327"
        val response = transact("0100", 1800)
        val discovered = ObdDecoder.supportedPids(response).toMutableSet()
        // صفحات بعدی bitmap فقط در صورت اعلام پشتیبانی PID مرزی خوانده می‌شوند.
        if (0x20 in discovered) {
            val page20 = runCatching { transact("0120", 1500) }.getOrNull().orEmpty()
            discovered += ObdDecoder.supportedPids(page20, 0x20)
        }
        if (0x40 in discovered) {
            val page40 = runCatching { transact("0140", 1500) }.getOrNull().orEmpty()
            discovered += ObdDecoder.supportedPids(page40, 0x40)
        }
        supportedPids = discovered
        return response
    }

    @Throws(IOException::class)
    fun readLiveData(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val useSupportMap = supportedPids.isNotEmpty()
        for (spec in pidSpecs) {
            if (useSupportMap && spec.pid !in supportedPids) continue
            val response = runCatching { transact("01" + spec.pid.toString(16).padStart(2, '0').uppercase(Locale.US), 1100) }
                .getOrNull() ?: continue
            val data = ObdDecoder.payload(
                response,
                "41",
                spec.pid.toString(16).padStart(2, '0').uppercase(Locale.US)
            ) ?: continue
            if (data.size < spec.bytes) continue
            val value = runCatching { spec.decode(data) }.getOrNull() ?: continue
            result[spec.key] = "${ObdDecoder.format(value, if (spec.key == "rpm" || spec.key == "speed" || spec.key == "runtime") 0 else 1)} ${spec.unit}"
        }
        return result
    }

    @Throws(IOException::class)
    fun readTroubleCodes(): List<TroubleCode> {
        val all = ArrayList<TroubleCode>()
        listOf(
            Triple("03", "خطاهای ثبت‌شده", "ذخیره‌شده"),
            Triple("07", "خطاهای در انتظار", "در انتظار"),
            Triple("0A", "خطاهای دائمی", "دائمی")
        ).forEach { (service, _, state) ->
            val response = transact(service, 1800)
            all += ObdDecoder.decodeDtc(response, service, state)
        }
        return all.distinctBy { "${it.code}_${it.state}" }
    }

    @Throws(IOException::class)
    fun readReadiness(): String? = ObdDecoder.readiness(transact("0101", 1500))

    @Throws(IOException::class)
    fun readVin(): String? = ObdDecoder.vin(transact("0902", 3500))

    @Throws(IOException::class)
    fun adapterVoltage(): String? {
        val response = transact("ATRV", 1200)
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*V?", RegexOption.IGNORE_CASE).find(response)
        return match?.groupValues?.getOrNull(1)?.let { "$it V" }
    }

    @Throws(IOException::class)
    fun clearTroubleCodes(): String = transact("04", 2500)

    @Throws(IOException::class)
    private fun transact(command: String, timeoutMs: Int): String {
        if (!port.isOpen) throw IOException("درگاه USB بسته است")
        val bytes = (command + "\r").toByteArray(StandardCharsets.US_ASCII)
        port.write(bytes, 1200)
        return readUntilPrompt(timeoutMs)
    }

    @Throws(IOException::class)
    private fun readUntilPrompt(timeoutMs: Int): String {
        val output = StringBuilder()
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L).toInt()
            val count = port.read(buffer, minOf(150, remaining))
            if (count > 0) {
                val chunk = String(buffer, 0, count, StandardCharsets.US_ASCII)
                output.append(chunk)
                if (chunk.contains('>') || output.contains(">")) break
            }
        }
        return output.toString().replace(">", "").trim()
    }

    fun close() {
        runCatching { if (port.isOpen) port.close() }
        runCatching { usbConnection.close() }
    }
}
