package com.prodiag.app

val PERSIAN_DTC = mapOf(
    "P0105" to "خطای سنسور فشار هوای منیفولد (MAP) - سیم‌کشی یا خرابی سنسور",
    "P0106" to "عملکرد نامناسب سنسور مپ (MAP) - نشتی خلا در منیفولد",
    "P0110" to "خطای سنسور دمای هوای ورودی (IAT)",
    "P0115" to "خطای سنسور دمای آب موتور (ECT) - قطع بودن سوکت یا خرابی سنسور",
    "P0120" to "خطای سنسور موقعیت دریچه گاز (TPS)",
    "P0130" to "خرابی سنسور اکسیژن بالا (سنسور ۱) - مدار سنسور",
    "P0135" to "سوختن هیتر (گرمکن) سنسور اکسیژن بالا",
    "P0201" to "قطع بودن مدار انژکتور سیلندر ۱",
    "P0202" to "قطع بودن مدار انژکتور سیلندر ۲",
    "P0203" to "قطع بودن مدار انژکتور سیلندر ۳",
    "P0204" to "قطع بودن مدار انژکتور سیلندر ۴",
    "P0300" to "احتراق ناقص تصادفی در چند سیلندر - شمع، وایر یا کوئل",
    "P0301" to "احتراق ناقص (Misfire) در سیلندر ۱",
    "P0302" to "احتراق ناقص (Misfire) در سیلندر ۲",
    "P0303" to "احتراق ناقص (Misfire) در سیلندر ۳",
    "P0304" to "احتراق ناقص (Misfire) در سیلندر ۴",
    "P0325" to "خطای سنسور ضربه (Knock Sensor)",
    "P0335" to "خطای سنسور دور موتور (Crankshaft) - ماشین بد روشن می‌شود",
    "P0340" to "خطای سنسور موقعیت میل‌سوپاپ (Camshaft)",
    "P0420" to "کاهش راندمان کاتالیزور",
    "P0443" to "خطای شیر برقی کنیستر (EVAP)",
    "P0480" to "خطای مدار رله فن دور کند",
    "P0481" to "خطای مدار رله فن دور تند",
    "P0500" to "خطای سنسور سرعت خودرو (VSS)",
    "P0505" to "خطای استپر موتور (کنترل دور آرام - IAC)",
    "P0560" to "ولتاژ سیستم (باتری یا دینام) بیش از حد بالا یا پایین است",
)

fun decodeDtc(b1: Int, b2: Int): String =
    fmt("%c%d%X%02X", "PCBU"[b1 shr 6], (b1 shr 4) and 3, b1 and 0xF, b2)

/** پاسخ خام حالت 03 را به لیست کد خطا تبدیل می‌کند. can=true یعنی بایت تعداد خطا بعد از 43 وجود دارد. */
fun parseDtcs(resp: String, can: Boolean): List<String> {
    val prefixed = Regex("^([0-9]):(.*)$")
    val records = mutableListOf<StringBuilder>()
    for (raw in resp.lines()) {
        val l = raw.replace(" ", "").uppercase()
        if (l.isEmpty() || !l.all { it in "0123456789ABCDEF:" }) continue
        val m = prefixed.find(l)
        if (m != null) {
            if (m.groupValues[1] == "0") records += StringBuilder(m.groupValues[2])
            else records.lastOrNull()?.append(m.groupValues[2])
        } else if (l.startsWith("43")) {
            records += StringBuilder(l)
        }
    }
    val out = linkedSetOf<String>()
    for (rec in records) {
        var s = rec.toString()
        if (!s.startsWith("43")) continue
        s = s.substring(2)
        if (can && s.length >= 2) s = s.substring(2)
        var i = 0
        while (i + 4 <= s.length) {
            val b1 = s.substring(i, i + 2).toInt(16)
            val b2 = s.substring(i + 2, i + 4).toInt(16)
            if (b1 != 0 || b2 != 0) out += decodeDtc(b1, b2)
            i += 4
        }
    }
    return out.toList()
}
