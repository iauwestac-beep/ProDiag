package ir.autodiagnostic.elm327

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val background = Color.rgb(9, 17, 31)
    private val panel = Color.rgb(18, 31, 49)
    private val panelLight = Color.rgb(25, 42, 63)
    private val accent = Color.rgb(50, 199, 165)
    private val textPrimary = Color.rgb(238, 244, 250)
    private val textSecondary = Color.rgb(158, 176, 196)
    private val warning = Color.rgb(255, 190, 90)

    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshots = Collections.synchronizedList(mutableListOf<LiveSnapshot>())
    @Volatile private var isLogging = false

    private lateinit var usbManager: UsbManager
    private lateinit var statusView: TextView
    private lateinit var connectionInfoView: TextView
    private lateinit var dtcView: TextView
    private lateinit var vinView: TextView
    private lateinit var readinessView: TextView
    private lateinit var logView: TextView
    private lateinit var baudSpinner: Spinner
    private val metricViews = linkedMapOf<String, TextView>()
    @Volatile private var client: Elm327Client? = null
    private var connectedDeviceId: Int? = null
    private var pollTask: ScheduledFuture<*>? = null
    private var latestDtc: List<TroubleCode> = emptyList()
    private var exportText: String = ""
    private val exportRequest = 6103
    private val permissionAction: String by lazy { "$packageName.USB_PERMISSION" }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = usbDeviceFromIntent(intent)
                    if (granted && device != null) connectToDevice(device)
                    else setStatus("مجوز دسترسی USB داده نشد؛ اتصال را دوباره انجام دهید.", warning)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = usbDeviceFromIntent(intent)
                    if (device != null && device.deviceId == connectedDeviceId) disconnectNow("آداپتور USB جدا شد.")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceFromIntent(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        registerUsbReceiver()
        setContentView(buildScreen())
        showAttachedDevices()
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(background)
            isFillViewport = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(30))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        header.addView(label("عیب‌یاب هوشمند خودرو", 25f, textPrimary, true))
        header.addView(label("ارتباط مستقیم با ECU از طریق ELM327 و کابل USB-OTG", 13f, textSecondary))
        content.addView(header)

        val connectionCard = newCard()
        connectionCard.addView(sectionTitle("اتصال به خودرو"))
        statusView = label("در حال بررسی درگاه USB...", 15f, warning, true)
        connectionCard.addView(statusView)
        connectionInfoView = label("آداپتور را به درگاه OBD خودرو و سپس با کابل OTG به گوشی وصل کنید.", 12f, textSecondary)
        connectionCard.addView(connectionInfoView)
        connectionCard.addView(label("سرعت سریال آداپتور", 12f, textSecondary, true).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        baudSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("38400", "9600", "115200", "57600")
            )
            setSelection(0)
        }
        connectionCard.addView(baudSpinner)
        connectionCard.addView(buttonRow(
            actionButton("اتصال USB", accent) { connectUsb() },
            actionButton("قطع اتصال", Color.rgb(82, 103, 126)) { disconnectNow("ارتباط به درخواست شما قطع شد.") }
        ))
        content.addView(connectionCard)

        val liveCard = newCard()
        liveCard.addView(sectionTitle("اطلاعات زنده موتور"))
        liveCard.addView(label("مقادیر فقط در صورت پشتیبانی خودرو و ECU نمایش داده می‌شوند.", 11f, textSecondary))
        val metricRows = listOf(
            listOf(Triple("rpm", "دور موتور", "دور/دقیقه"), Triple("speed", "سرعت خودرو", "کیلومتر/ساعت")),
            listOf(Triple("coolant", "دمای مایع خنک‌کننده", "°C"), Triple("load", "بار موتور", "%")),
            listOf(Triple("throttle", "دریچه گاز", "%"), Triple("intake", "دمای هوای ورودی", "°C")),
            listOf(Triple("map", "فشار منیفولد", "kPa"), Triple("maf", "جریان هوای ورودی", "g/s")),
            listOf(Triple("fuel", "سطح سوخت", "%"), Triple("oil", "دمای روغن", "°C")),
            listOf(Triple("fuelPressure", "فشار سوخت", "kPa"), Triple("timing", "آوانس جرقه", "درجه")),
            listOf(Triple("stft", "اصلاح کوتاه‌مدت سوخت", "%"), Triple("ltft", "اصلاح بلندمدت سوخت", "%")),
            listOf(Triple("moduleVoltage", "ولتاژ واحد کنترل", "V"), Triple("ambient", "دمای محیط", "°C")),
            listOf(Triple("fuelRate", "مصرف لحظه‌ای", "لیتر/ساعت"), Triple("runtime", "زمان کارکرد موتور", "ثانیه"))
        )
        metricRows.forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (key, title, unit) -> line.addView(metricCard(key, title, unit), LinearLayout.LayoutParams(0, dp(84), 1f)) }
            liveCard.addView(line)
        }
        content.addView(liveCard)

        val dtcCard = newCard()
        dtcCard.addView(sectionTitle("کدهای خطا و وضعیت چراغ چک"))
        readinessView = label("وضعیت چراغ چک هنوز خوانده نشده است.", 12f, textSecondary)
        dtcCard.addView(readinessView)
        dtcView = label("برای دریافت کدهای خطا، ابتدا به آداپتور و ECU متصل شوید.", 13f, textPrimary)
        dtcView.setPadding(0, dp(8), 0, dp(8))
        dtcCard.addView(dtcView)
        dtcCard.addView(buttonRow(
            actionButton("خواندن کدهای خطا", accent) { readDtc() },
            actionButton("پاک‌کردن خطاها", Color.rgb(176, 89, 82)) { confirmClearDtc() }
        ))
        content.addView(dtcCard)

        val vehicleCard = newCard()
        vehicleCard.addView(sectionTitle("مشخصات خودرو"))
        vinView = label("شماره شناسایی VIN هنوز دریافت نشده است.", 13f, textSecondary)
        vehicleCard.addView(vinView)
        vehicleCard.addView(buttonRow(actionButton("خواندن VIN", accent) { readVin() }))
        content.addView(vehicleCard)

        val logCard = newCard()
        logCard.addView(sectionTitle("ثبت اطلاعات و گزارش"))
        logView = label("ثبت سفر خاموش است. با شروع ثبت، داده‌های زنده در فایل CSV ذخیره خواهند شد.", 12f, textSecondary)
        logCard.addView(logView)
        logCard.addView(buttonRow(
            actionButton("شروع ثبت سفر", accent) { startLogging() },
            actionButton("توقف ثبت", Color.rgb(82, 103, 126)) { stopLogging() }
        ))
        logCard.addView(buttonRow(actionButton("خروجی CSV / اکسل", panelLight) { exportCsv() }))
        content.addView(logCard)

        val guideCard = newCard()
        guideCard.addView(sectionTitle("راهنمای اتصال و نکات مهم"))
        guideCard.addView(label(
            "۱) خودرو باید از عیب‌یابی OBD-II پشتیبانی کند.\n" +
                "۲) سوئیچ را در وضعیت باز قرار دهید؛ برای مشاهده دور موتور، موتور را روشن کنید.\n" +
                "۳) آداپتور ELM327 USB را به سوکت OBD خودرو متصل کنید.\n" +
                "۴) کابل OTG را به گوشی وصل کنید، سپس دکمه «اتصال USB» را بزنید و مجوز را تأیید کنید.\n" +
                "۵) اگر آداپتور شناسایی نشد، پشتیبانی OTG گوشی و نوع چیپ USB آداپتور را بررسی کنید و سرعت سریال را تغییر دهید.",
            12f, textSecondary
        ))
        guideCard.addView(label(
            "توجه: نسخه‌های تقلبی یا محدود ELM327 ممکن است با همه خودروها و همه فرمان‌ها سازگار نباشند. پاک‌کردن کد خطا، چراغ چک را خاموش می‌کند اما عیب مکانیکی را تعمیر نمی‌کند و داده‌های آمادگی آلایندگی را بازنشانی می‌کند. هنگام رانندگی با گوشی کار نکنید.",
            11f, warning
        ).apply { setPadding(0, dp(12), 0, 0) })
        content.addView(guideCard)

        content.addView(label("نسخه ۱٫۰٫۰  •  تشخیص عمومی OBD-II  •  بدون نیاز به اینترنت", 10f, textSecondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        return scroll
    }

    private fun newCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(panel, 16)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun sectionTitle(text: String): TextView = label(text, 16f, textPrimary, true).apply {
        setPadding(0, 0, 0, dp(10))
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
        textDirection = View.TEXT_DIRECTION_RTL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(0, dp(2), 0, dp(2))
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun metricCard(key: String, title: String, unit: String): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(7))
            background = rounded(panelLight, 11)
            val p = LinearLayout.LayoutParams(0, dp(84), 1f)
            p.setMargins(dp(3), dp(5), dp(3), dp(3))
            layoutParams = p
        }
        tile.addView(label(title, 11f, textSecondary))
        val value = label("—", 19f, textPrimary, true)
        tile.addView(value)
        tile.addView(label(unit, 10f, textSecondary))
        metricViews[key] = value
        return tile
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        buttons.forEachIndexed { index, button ->
            val p = LinearLayout.LayoutParams(0, dp(46), 1f)
            if (index > 0) p.marginEnd = dp(7)
            p.topMargin = dp(8)
            addView(button, p)
        }
    }

    private fun actionButton(text: String, color: Int, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setTextColor(if (color == panelLight) textPrimary else Color.rgb(8, 23, 32))
        background = rounded(color, 12)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()


    private fun showAttachedDevices() {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            setStatus("آداپتور USB شناسایی نشد.", warning)
            connectionInfoView.text = "ELM327 را به خودرو و کابل OTG را به گوشی وصل کنید؛ سپس اتصال USB را بزنید."
        } else {
            val names = devices.joinToString("، ") { "${it.productName ?: "USB"} (VID:${it.vendorId}, PID:${it.productId})" }
            setStatus("دستگاه USB متصل است؛ آماده بررسی آداپتور.", accent)
            connectionInfoView.text = names
        }
    }

    private fun connectUsb() {
        if (!packageManager.hasSystemFeature("android.hardware.usb.host")) {
            setStatus("این گوشی از USB Host / OTG پشتیبانی نمی‌کند.", warning)
            return
        }
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            val deviceInfo = usbManager.deviceList.values.joinToString("\n") {
                "${it.deviceName}  •  VID ${it.vendorId} / PID ${it.productId}"
            }
            setStatus("درایور سریال سازگار پیدا نشد.", warning)
            connectionInfoView.text = if (deviceInfo.isBlank()) {
                "هیچ USB متصلی دیده نمی‌شود. کابل OTG، سوکت USB و نوع آداپتور را بررسی کنید."
            } else {
                "دستگاه USB دیده شده اما چیپ سریال آن شناسایی نشد:\n$deviceInfo\nممکن است آداپتور شما از نوع USB پشتیبانی‌نشده باشد."
            }
            return
        }
        val driver = drivers.first()
        if (!usbManager.hasPermission(driver.device)) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(permissionAction).setPackage(packageName), flags
            )
            setStatus("در انتظار تأیید مجوز USB...", warning)
            usbManager.requestPermission(driver.device, permissionIntent)
        } else {
            connectToDevice(driver.device)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        setStatus("در حال برقراری ارتباط سریال با ELM327...", warning)
        val selectedBaud = (baudSpinner.selectedItem?.toString() ?: "38400").toIntOrNull() ?: 38400
        worker.execute {
            try {
                val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                    .firstOrNull { it.device.deviceId == device.deviceId }
                    ?: throw IllegalStateException("درایور سریال این آداپتور پیدا نشد.")
                val connection = usbManager.openDevice(device)
                    ?: throw IllegalStateException("بازکردن USB ممکن نشد؛ مجوز یا اتصال OTG را بررسی کنید.")
                val port = driver.ports.firstOrNull()
                    ?: throw IllegalStateException("آداپتور درگاه سریال قابل استفاده ندارد.")
                try {
                    port.open(connection)
                    port.setParameters(selectedBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    runCatching { port.setDTR(true) }
                    runCatching { port.setRTS(true) }
                } catch (error: Exception) {
                    runCatching { port.close() }
                    runCatching { connection.close() }
                    throw error
                }
                val newClient = Elm327Client(port, connection)
                val supportedResponse = try {
                    newClient.initialize()
                } catch (initializationError: Exception) {
                    newClient.close()
                    throw initializationError
                }
                client?.close()
                client = newClient
                connectedDeviceId = device.deviceId
                val ecuResponded = supportedResponse.contains("41 00", ignoreCase = true) ||
                    ObdDecoder.supportedPids(supportedResponse).isNotEmpty()
                mainHandler.post {
                    setStatus(
                        if (ecuResponded) "اتصال به آداپتور و ECU برقرار است."
                        else "آداپتور متصل شد؛ پاسخ ECU دریافت نشد.",
                        if (ecuResponded) accent else warning
                    )
                    connectionInfoView.text = "${device.productName ?: "آداپتور USB"}  •  $selectedBaud baud  •  ${newClient.adapterDescription}\n" +
                        "پارامترهای پشتیبانی‌شده: ${newClient.supportedPids.size}"
                    startPolling()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    setStatus("اتصال ناموفق: ${friendlyError(error)}", warning)
                    connectionInfoView.text = "سرعت سریال را تغییر دهید یا از سالم‌بودن کابل OTG و آداپتور مطمئن شوید."
                }
            }
        }
    }

    private fun startPolling() {
        pollTask?.cancel(true)
        pollTask = worker.scheduleWithFixedDelay({
            val activeClient = client ?: return@scheduleWithFixedDelay
            try {
                val values = activeClient.readLiveData()
                // ولتاژ ATRV مربوط به تغذیه آداپتور است، نه الزاماً ولتاژ دقیق باتری ECU.
                val voltage = runCatching { activeClient.adapterVoltage() }.getOrNull()
                if (values.isNotEmpty()) {
                    val snapshot = LiveSnapshot(System.currentTimeMillis(), values)
                    if (isLogging) snapshots.add(snapshot)
                    mainHandler.post {
                        values.forEach { (key, value) -> metricViews[key]?.text = value }
                        if (voltage != null) connectionInfoView.text = connectionInfoView.text.toString().substringBefore("\nولتاژ آداپتور:") +
                            "\nولتاژ آداپتور: $voltage"
                    }
                }
            } catch (error: Exception) {
                mainHandler.post { setStatus("ارتباط ناپایدار است: ${friendlyError(error)}", warning) }
            }
        }, 0, 4, TimeUnit.SECONDS)
    }

    private fun readDtc() {
        val activeClient = client
        if (activeClient == null) {
            toast("ابتدا به ELM327 وصل شوید.")
            return
        }
        setStatus("در حال خواندن کدهای خطا...", warning)
        worker.execute {
            try {
                val codes = activeClient.readTroubleCodes()
                val readiness = runCatching { activeClient.readReadiness() }.getOrNull()
                mainHandler.post {
                    latestDtc = codes
                    readinessView.text = readiness ?: "وضعیت چراغ چک از ECU دریافت نشد."
                    dtcView.text = if (codes.isEmpty()) {
                        "در گروه خطاهای ثبت‌شده، در انتظار و دائمی، کدی از ECU دریافت نشد. این نتیجه سلامت قطعی همه قطعات و سامانه‌های اختصاصی خودرو را ثابت نمی‌کند."
                    } else {
                        codes.joinToString("\n\n────────────────────\n\n") { code ->
                            val explanation = DtcCatalog.explanation(code.code)
                            "کد استاندارد: ${code.code}\n" +
                                "خوانش فارسی کد: ${DtcCatalog.persianCode(code.code)}\n" +
                                "گروه خرابی: ${code.category}\n" +
                                "وضعیت: ${code.state}\n" +
                                "اولویت بررسی: ${explanation.urgency}\n" +
                                "شرح خطا: ${explanation.description}\n" +
                                "علت‌های احتمالی: ${explanation.possibleCauses}\n" +
                                "اقدام پیشنهادی: ${explanation.recommendedChecks}"
                        }
                    }
                    setStatus("خواندن کدهای خطا پایان یافت.", accent)
                }
            } catch (error: Exception) {
                mainHandler.post { setStatus("خطا در خواندن کدهای خطا: ${friendlyError(error)}", warning) }
            }
        }
    }

    private fun confirmClearDtc() {
        if (client == null) {
            toast("ابتدا به ELM327 وصل شوید.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("پاک‌کردن کدهای خطا؟")
            .setMessage(
                "فرمان پاک‌سازی به ECU ارسال می‌شود. این کار ممکن است کدهای ذخیره‌شده و Freeze Frame را پاک و پایشگرهای آمادگی آلایندگی را بازنشانی کند. علت خرابی را برطرف کنید و فقط در صورت آگاهی ادامه دهید."
            )
            .setNegativeButton("انصراف", null)
            .setPositiveButton("پاک‌کردن خطاها") { _, _ -> clearDtcNow() }
            .show()
    }

    private fun clearDtcNow() {
        val activeClient = client ?: return
        setStatus("در حال ارسال فرمان پاک‌سازی به ECU...", warning)
        worker.execute {
            try {
                val response = activeClient.clearTroubleCodes()
                val cleared = response.contains("44") || response.contains("OK", ignoreCase = true)
                mainHandler.post {
                    setStatus(
                        if (cleared) "فرمان پاک‌سازی پذیرفته شد؛ برای بررسی مجدد خطاها را بخوانید."
                        else "پاسخ پاک‌سازی قطعی نیست: $response",
                        if (cleared) accent else warning
                    )
                    readDtc()
                }
            } catch (error: Exception) {
                mainHandler.post { setStatus("پاک‌سازی انجام نشد: ${friendlyError(error)}", warning) }
            }
        }
    }

    private fun readVin() {
        val activeClient = client
        if (activeClient == null) {
            toast("ابتدا به ELM327 وصل شوید.")
            return
        }
        setStatus("در حال دریافت VIN...", warning)
        worker.execute {
            try {
                val vin = activeClient.readVin()
                mainHandler.post {
                    vinView.text = vin ?: "ECU شماره VIN را ارائه نکرد یا پاسخ چندبخشی قابل‌خواندن نبود."
                    setStatus(if (vin != null) "VIN دریافت شد." else "VIN از ECU دریافت نشد.", if (vin != null) accent else warning)
                }
            } catch (error: Exception) {
                mainHandler.post { setStatus("خطا در دریافت شماره شناسایی خودرو: ${friendlyError(error)}", warning) }
            }
        }
    }

    private fun startLogging() {
        if (client == null) {
            toast("برای ثبت داده، ابتدا به خودرو وصل شوید.")
            return
        }
        snapshots.clear()
        isLogging = true
        logView.text = "ثبت سفر فعال است؛ داده‌های دریافتی در حافظه موقت برنامه جمع‌آوری می‌شوند."
        setStatus("ثبت اطلاعات سفر آغاز شد.", accent)
    }

    private fun stopLogging() {
        isLogging = false
        val count = synchronized(snapshots) { snapshots.size }
        logView.text = "ثبت متوقف شد. تعداد نمونه‌های ذخیره‌شده: $count  •  برای ساخت فایل، خروجی CSV را بزنید."
    }

    private fun exportCsv() {
        if (isLogging) stopLogging()
        val rows = synchronized(snapshots) { snapshots.toList() }
        if (rows.isEmpty()) {
            toast("هنوز داده‌ای برای خروجی ثبت نشده است.")
            return
        }
        val keys = listOf("timestamp", "rpm", "speed", "coolant", "load", "throttle", "intake", "map", "maf", "fuel", "oil", "fuelPressure", "timing", "stft", "ltft", "moduleVoltage", "ambient", "fuelRate", "runtime")
        val headers = listOf("زمان", "دور موتور", "سرعت کیلومتر بر ساعت", "دمای مایع خنک‌کننده °C", "بار موتور %", "دریچه گاز %", "دمای هوای ورودی °C", "فشار منیفولد kPa", "دبی هوا g/s", "سطح سوخت %", "دمای روغن °C", "فشار سوخت kPa", "آوانس جرقه درجه", "اصلاح کوتاه‌مدت سوخت %", "اصلاح بلندمدت سوخت %", "ولتاژ واحد کنترل V", "دمای محیط °C", "مصرف سوخت لیتر بر ساعت", "زمان کارکرد ثانیه")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val csv = StringBuilder("\uFEFF")
        csv.append(headers.joinToString(";")).append('\n')
        rows.forEach { row ->
            val values = keys.map { key ->
                if (key == "timestamp") dateFormat.format(Date(row.timestampMillis))
                else row.values[key].orEmpty()
            }
            csv.append(values.joinToString(";") { value -> "\"${value.replace("\"", "\"\"")}\"" }).append('\n')
        }
        exportText = csv.toString()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "گزارش_عیب‌یابی_${System.currentTimeMillis()}.csv")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, exportRequest)
    }

    @Deprecated("استفاده از فراخوانی فایل‌ساز برای سازگاری با نسخه‌های مختلف اندروید")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == exportRequest && resultCode == RESULT_OK && data?.data != null) {
            try {
                contentResolver.openOutputStream(data.data!!)?.use { output: OutputStream ->
                    output.write(exportText.toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: throw IllegalStateException("فایل خروجی باز نشد.")
                toast("فایل CSV ذخیره شد.")
            } catch (error: Exception) {
                toast("ذخیره فایل ناموفق بود: ${friendlyError(error)}")
            }
        }
    }

    private fun disconnectNow(message: String) {
        pollTask?.cancel(true)
        pollTask = null
        worker.execute {
            runCatching { client?.close() }
            client = null
            connectedDeviceId = null
            mainHandler.post {
                metricViews.values.forEach { it.text = "—" }
                setStatus(message, textSecondary)
                connectionInfoView.text = "برای اتصال، آداپتور ELM327 را از طریق USB-OTG وصل کنید."
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        val normalized = message.lowercase(Locale.ROOT)
        return when {
            normalized.contains("permission") || normalized.contains("access denied") ->
                "مجوز دسترسی به آداپتور USB صادر نشده است."
            normalized.contains("no such device") || normalized.contains("disconnected") ->
                "آداپتور یا کابل USB جدا شده است."
            normalized.contains("timeout") || normalized.contains("timed out") ->
                "پاسخ در زمان مجاز دریافت نشد؛ سوئیچ، سرعت سریال و سازگاری آداپتور را بررسی کنید."
            normalized.contains("busy") ->
                "درگاه USB مشغول است؛ اتصال را قطع و دوباره برقرار کنید."
            normalized.contains("not open") || normalized.contains("closed") ->
                "درگاه ارتباطی بسته شده است؛ دوباره متصل شوید."
            message.isBlank() -> "خطای نامشخص در ارتباط با آداپتور یا واحد کنترل خودرو."
            else -> "جزئیات فنی: $message"
        }
    }

    private fun setStatus(message: String, color: Int) {
        if (!::statusView.isInitialized) return
        statusView.text = message
        statusView.setTextColor(color)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        pollTask?.cancel(true)
        runCatching { client?.close() }
        client = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
