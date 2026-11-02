package com.prodiag.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val ui = DiagState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: Engine
    private lateinit var usb: UsbManager
    private var pending: UsbSerialDriver? = null
    private val action = "com.prodiag.app.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != action) return
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) pending?.let { open(it) }
            else ui.status = "دسترسی USB رد شد"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        engine = Engine(ui, scope)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Root(ui, engine) { connect() }
                    }
                }
            }
        }
    }

    private fun connect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        if (drivers.isEmpty()) {
            ui.status = "کابل USB پیدا نشد (مبدل OTG و کابل را بررسی کنید)"
            return
        }
        val d = drivers[0]
        pending = d
        if (usb.hasPermission(d.device)) open(d) else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName), flags)
            usb.requestPermission(d.device, pi)
        }
    }

    private fun open(d: UsbSerialDriver) {
        val conn = usb.openDevice(d.device)
        if (conn == null) { ui.status = "باز کردن دستگاه USB ممکن نشد"; return }
        engine.connectUsb(d.ports[0], conn)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(receiver) }
        engine.disconnect()
        scope.cancel()
    }
}

// ======================= UI =======================

private val cardColors = listOf(
    Color(0xFF3498DB), Color(0xFF2ECC71), Color(0xFFE67E22), Color(0xFF9B59B6), Color(0xFFF1C40F),
    Color(0xFF1ABC9C), Color(0xFFE74C3C), Color(0xFF95A5A6), Color(0xFF16A085),
)
private val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, textDirection = TextDirection.Ltr)

@Composable
fun Root(ui: DiagState, engine: Engine, onConnect: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("داشبورد", "نمودار", "خطاها", "شتاب ۰-۱۰۰", "ترمینال")
    val live = ui.connected && !ui.demo
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ui.status, fontWeight = FontWeight.Bold,
                    color = if (ui.connected) Color(0xFF2ECC71) else Color(0xFFE74C3C))
                if (ui.adapter.isNotEmpty()) Text(ui.adapter, fontSize = 11.sp, color = Color.Gray)
            }
            Button(onClick = { if (live) engine.disconnect() else onConnect() }, enabled = !ui.busy) {
                Text(if (live) "قطع اتصال" else "اتصال USB")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("حالت شبیه‌ساز (Demo)")
            Spacer(Modifier.width(8.dp))
            Switch(checked = ui.demo, onCheckedChange = { if (it) engine.startDemo() else engine.disconnect() })
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            titles.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1, fontSize = 13.sp) })
            }
        }
        val m = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
        when (tab) {
            0 -> DashTab(ui, m)
            1 -> GraphTab(ui, m)
            2 -> DtcTab(ui, engine, m)
            3 -> AccelTab(ui, engine, m)
            else -> TermTab(ui, engine, m)
        }
    }
}

@Composable
fun DashTab(ui: DiagState, modifier: Modifier) {
    val cards = listOf(
        Triple("rpm", "دور موتور", "RPM"), Triple("speed", "سرعت", "km/h"),
        Triple("temp", "دمای آب", "°C"), Triple("throttle", "دریچه گاز", "%"),
        Triple("load", "بار موتور", "%"), Triple("volt", "ولتاژ باتری", "V"),
        Triple("iat", "دمای هوای ورودی", "°C"), Triple("maf", "هوای ورودی (MAF)", "g/s"),
        Triple("map", "فشار منیفولد (MAP)", "kPa"),
    )
    LazyVerticalGrid(
        GridCells.Fixed(2), modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(cards) { (k, title, unit) ->
            val color = cardColors[cards.indexOfFirst { it.first == k }]
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, fontSize = 14.sp)
                    Text(ui.vals[k] ?: "--", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color)
                    Text(unit, fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun LineChart(data: List<Float>, max: Float, color: Color, title: String, modifier: Modifier) {
    Column(modifier) {
        Text(title, fontSize = 13.sp, color = color)
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp)) {
            val w = size.width
            val h = size.height
            for (k in 0..4) drawLine(Color(0xFF444444), Offset(0f, h * k / 4), Offset(w, h * k / 4))
            val path = Path()
            data.forEachIndexed { idx, v ->
                val x = w * idx / (data.size - 1)
                val y = h - (v.coerceIn(0f, max) / max) * h
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 4f))
        }
    }
}

@Composable
fun GraphTab(ui: DiagState, modifier: Modifier) {
    Column(modifier) {
        LineChart(ui.rpmHist, 7000f, Color(0xFF3498DB), "Engine RPM (0-7000)", Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        LineChart(ui.speedHist, 200f, Color(0xFF2ECC71), "Speed km/h (0-200)", Modifier.weight(1f))
    }
}

@Composable
fun DtcTab(ui: DiagState, engine: Engine, modifier: Modifier) {
    var confirm by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { engine.readDtcs() }, Modifier.weight(1f)) { Text("اسکن و ترجمه خطاها") }
            Button(
                onClick = { confirm = true }, Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C)),
            ) { Text("پاک کردن خطاها") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
            Text(ui.dtcText, fontSize = 15.sp)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("تأیید پاک کردن") },
        text = {
            Text("با پاک کردن خطاها، اطلاعات Freeze Frame و وضعیت آمادگی مانیتورها (برای تست آلایندگی) هم " +
                "ریست می‌شود. سوئیچ باز و موتور خاموش باشد. ادامه می‌دهید؟")
        },
        confirmButton = { TextButton({ confirm = false; engine.clearDtcs() }) { Text("بله، پاک کن") } },
        dismissButton = { TextButton({ confirm = false }) { Text("انصراف") } },
    )
}

@Composable
fun AccelTab(ui: DiagState, engine: Engine, modifier: Modifier) {
    val timerColor = when (ui.accel) {
        "DONE" -> Color(0xFF2ECC71); "RUNNING" -> Color(0xFFE67E22); else -> Color(0xFFF1C40F)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(ui.accelMsg, color = Color(0xFFF39C12), fontSize = 15.sp)
        Spacer(Modifier.height(16.dp))
        Text("${ui.accelTime} s", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = timerColor)
        Text("${ui.vals["speed"]} km/h", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { engine.armAccel() }) { Text("آماده‌باش (Arm)", fontSize = 18.sp) }
        Spacer(Modifier.height(16.dp))
        Text(
            "دقت تست به نرخ پاسخ ECU و رزولوشن ۱ km/h سرعت OBD محدود است و ممکن است با GPS چند دهم ثانیه " +
                "اختلاف داشته باشد. تست را فقط در مسیر بسته و ایمن انجام دهید.",
            fontSize = 12.sp, color = Color.Gray,
        )
    }
}

@Composable
fun TermTab(ui: DiagState, engine: Engine, modifier: Modifier) {
    var cmd by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(ui.term) { scroll.scrollTo(scroll.maxValue) }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) { Text(ui.term, style = mono) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmd, onValueChange = { cmd = it }, singleLine = true,
                modifier = Modifier.weight(1f), textStyle = mono,
                placeholder = { Text("ATRV  |  010C  |  0105", style = mono) },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { engine.sendRaw(cmd.trim().uppercase()); cmd = "" }) { Text("Send") }
        }
        Text("توجه: دستوراتی مثل ATZ/ATE/ATH ممکن است خواندن زنده را مختل کنند؛ در این صورت دوباره متصل شوید.",
            fontSize = 11.sp, color = Color.Gray)
    }
}
