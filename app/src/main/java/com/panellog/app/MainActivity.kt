package com.panellog.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Прототип: принимает поток с принтерного порта (TB5) панели NFS2-3030
 * через USB-RS232 переходник и показывает его на экране.
 * Сырые байты пишутся в файл без изменений — по ним потом подберём
 * кодировку иврита и формат разбора.
 */
class MainActivity : Activity(), SerialInputOutputManager.Listener {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.panellog.app.USB_PERMISSION"
        private const val MAX_SESSION_BYTES = 4 * 1024 * 1024
        private const val MAX_SCREEN_CHARS = 300_000
        private val BAUDS = intArrayOf(300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)
        private val ENCODINGS = arrayOf(
            "Текст (ASCII)",
            "Иврит SI-960 (7 бит)",
            "Иврит CP862 (DOS)"
        )
    }

    private lateinit var usbManager: UsbManager
    private lateinit var statusView: TextView
    private lateinit var devicesView: TextView
    private lateinit var counterView: TextView
    private lateinit var outputView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var baudSpinner: Spinner
    private lateinit var encSpinner: Spinner
    private lateinit var hexBox: CheckBox
    private lateinit var connectButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var rawFile: File? = null
    private var rawOut: FileOutputStream? = null

    private val sessionBytes = ByteArrayOutputStream()
    private var totalBytes = 0L
    private var lastWasCR = false
    private var pendingConnect = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (pendingConnect) {
                        pendingConnect = false
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) connect() else setStatus("Разрешение на USB не выдано", true)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (port != null) {
                        disconnect()
                        setStatus("Переходник отключён", true)
                    }
                    refreshDevices()
                }
            }
        }
    }

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        refreshDevices()
        setStatus("Не подключено. Подключите переходник через OTG и нажмите «Подключить».", false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            refreshDevices()
            setStatus("Обнаружен USB-переходник. Нажмите «Подключить».", false)
        }
    }

    override fun onDestroy() {
        disconnect()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        statusView = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(statusView)

        devicesView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(devicesView)

        // Строка настроек: скорость + кодировка
        val settingsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        baudSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                BAUDS.map { "$it бод" }
            )
            setSelection(BAUDS.indexOf(9600))
        }
        encSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ENCODINGS)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = rerender()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        settingsRow.addView(baudSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        settingsRow.addView(encSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        root.addView(settingsRow)

        // Кнопки
        val buttonsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectButton = smallButton("Подключить") {
            if (port == null) connect() else { disconnect(); setStatus("Отключено", false) }
        }
        buttonsRow.addView(connectButton, weighted())
        buttonsRow.addView(smallButton("Тест TX") { sendTest() }, weighted())
        buttonsRow.addView(smallButton("Очистить") { clearScreen() }, weighted())
        buttonsRow.addView(smallButton("Отправить") { shareLogs() }, weighted())
        root.addView(buttonsRow)

        val infoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hexBox = CheckBox(this).apply {
            text = "HEX"
            setOnCheckedChangeListener { _, _ -> rerender() }
        }
        infoRow.addView(hexBox)
        infoRow.addView(smallButton("Копировать") { copyLogs() })
        counterView = TextView(this).apply {
            textSize = 12f
            setPadding(dp(12), 0, 0, 0)
            text = "Принято: 0 байт"
        }
        infoRow.addView(counterView)
        root.addView(infoRow)

        outputView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.rgb(20, 230, 90))
            setBackgroundColor(Color.BLACK)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setTextIsSelectable(true)
        }
        scrollView = ScrollView(this).apply {
            addView(outputView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun weighted() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun setStatus(text: String, error: Boolean) {
        statusView.text = text
        statusView.setTextColor(if (error) Color.rgb(200, 30, 30) else Color.rgb(20, 20, 20))
    }

    private fun refreshDevices() {
        val all = usbManager.deviceList.values
        if (all.isEmpty()) {
            devicesView.text = "USB-устройств не видно (проверьте OTG-переходник и что OTG включён в настройках телефона)."
            return
        }
        val supported = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .associateBy { it.device.deviceName }
        val sb = StringBuilder("USB-устройства:\n")
        for (d in all) {
            val driver = supported[d.deviceName]
            sb.append("• ").append(describe(d))
            if (driver != null) sb.append(" — драйвер: ").append(driver.javaClass.simpleName.replace("SerialDriver", ""))
            else sb.append(" — не последовательный порт")
            sb.append('\n')
        }
        devicesView.text = sb.toString().trimEnd()
    }

    private fun describe(d: UsbDevice): String {
        val vid = String.format("%04X", d.vendorId)
        val pid = String.format("%04X", d.productId)
        val name = try {
            listOfNotNull(d.manufacturerName, d.productName).joinToString(" ")
        } catch (_: Exception) { "" }
        return "VID $vid PID $pid" + if (name.isNotBlank()) " ($name)" else ""
    }

    // ------------------------------------------------------- Подключение

    private fun selectedBaud(): Int = BAUDS[baudSpinner.selectedItemPosition.coerceIn(0, BAUDS.size - 1)]

    private fun connect() {
        refreshDevices()
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            setStatus("Переходник не найден. Подключите его через OTG.", true)
            return
        }
        val driver = drivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            pendingConnect = true
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
            usbManager.requestPermission(device, pi)
            setStatus("Запрашиваю разрешение на USB…", false)
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            setStatus("Не удалось открыть устройство", true)
            return
        }
        val p = driver.ports[0]
        try {
            p.open(connection)
            p.setParameters(selectedBaud(), 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.setDTR(true); p.setRTS(true) } catch (_: Exception) {}
        } catch (e: Exception) {
            setStatus("Ошибка открытия порта: ${e.message}", true)
            try { p.close() } catch (_: Exception) {}
            return
        }
        port = p
        openRawFile()
        ioManager = SerialInputOutputManager(p, this).also { it.start() }

        connectButton.text = "Отключить"
        baudSpinner.isEnabled = false
        setStatus("Подключено: ${describe(device)}, ${selectedBaud()} 8N1. Жду данные…", false)
    }

    private fun disconnect() {
        ioManager?.let { try { it.stop() } catch (_: Exception) {} }
        ioManager = null
        port?.let { try { it.close() } catch (_: Exception) {} }
        port = null
        closeRawFile()
        if (::connectButton.isInitialized) {
            connectButton.text = "Подключить"
            baudSpinner.isEnabled = true
        }
    }

    private fun sendTest() {
        val p = port
        if (p == null) {
            Toast.makeText(this, "Сначала подключитесь", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            p.write("TEST NFS2-3030 123\r\n".toByteArray(Charsets.US_ASCII), 1000)
            Toast.makeText(this, "Отправлено. При перемычке TX_A–RX_B текст вернётся на экран.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            setStatus("Ошибка отправки: ${e.message}", true)
        }
    }

    // ------------------------------------------------------- Приём данных

    override fun onNewData(data: ByteArray) {
        try { rawOut?.write(data); rawOut?.flush() } catch (_: Exception) {}
        mainHandler.post { appendData(data) }
    }

    override fun onRunError(e: Exception) {
        mainHandler.post {
            if (port != null) {
                disconnect()
                setStatus("Связь прервана: ${e.message}", true)
            }
        }
    }

    private fun appendData(data: ByteArray) {
        if (sessionBytes.size() < MAX_SESSION_BYTES) sessionBytes.write(data)
        totalBytes += data.size
        counterView.text = "Принято: $totalBytes байт" + (rawFile?.let { " → ${it.name}" } ?: "")
        outputView.append(decode(data))
        trimScreen()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun rerender() {
        if (!::outputView.isInitialized) return
        lastWasCR = false
        outputView.text = decode(sessionBytes.toByteArray())
        trimScreen()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun trimScreen() {
        val len = outputView.text.length
        if (len > MAX_SCREEN_CHARS) {
            outputView.text = outputView.text.subSequence(len - MAX_SCREEN_CHARS / 2, len)
        }
    }

    private fun clearScreen() {
        sessionBytes.reset()
        lastWasCR = false
        outputView.text = ""
    }

    /** Байты → текст для экрана. Сырые байты в файле не меняются. */
    private fun decode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        if (hexBox.isChecked) {
            for (b in bytes) sb.append(String.format("%02X ", b.toInt() and 0xFF))
            return sb.toString()
        }
        val enc = encSpinner.selectedItemPosition
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                c == 0x0D -> { sb.append('\n'); lastWasCR = true; continue }
                c == 0x0A -> { if (!lastWasCR) sb.append('\n') }
                c == 0x0C -> sb.append("\n──────── новая страница ────────\n")
                c == 0x09 -> sb.append('\t')
                enc == 1 && c in 0x60..0x7A -> sb.append((0x05D0 + (c - 0x60)).toChar())
                enc == 2 && c in 0x80..0x9A -> sb.append((0x05D0 + (c - 0x80)).toChar())
                c in 0x20..0x7E -> sb.append(c.toChar())
                else -> sb.append(String.format("[%02X]", c))
            }
            lastWasCR = false
        }
        return sb.toString()
    }

    // ------------------------------------------------------- Файлы

    private fun logDir(): File = (getExternalFilesDir(null) ?: filesDir).also { it.mkdirs() }

    private fun openRawFile() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(logDir(), "panel_raw_$stamp.bin")
        try {
            rawOut = FileOutputStream(f)
            rawFile = f
        } catch (e: Exception) {
            rawOut = null
            rawFile = null
            setStatus("Не удалось создать файл лога: ${e.message}", true)
        }
    }

    private fun closeRawFile() {
        try { rawOut?.close() } catch (_: Exception) {}
        rawOut = null
    }

    /** Чистое декодирование (без состояния экрана) — для отчёта и буфера обмена. */
    private fun decodeText(bytes: ByteArray, enc: Int): String {
        val sb = StringBuilder(bytes.size + 64)
        var cr = false
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                c == 0x0D -> { sb.append('\n'); cr = true; continue }
                c == 0x0A -> { if (!cr) sb.append('\n') }
                c == 0x0C -> sb.append("\n---- новая страница ----\n")
                c == 0x09 -> sb.append('\t')
                enc == 1 && c in 0x60..0x7A -> sb.append((0x05D0 + (c - 0x60)).toChar())
                enc == 2 && c in 0x80..0x9A -> sb.append((0x05D0 + (c - 0x80)).toChar())
                c in 0x20..0x7E -> sb.append(c.toChar())
                else -> sb.append(String.format("[%02X]", c))
            }
            cr = false
        }
        return sb.toString()
    }

    private fun hexDump(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 4 + 64)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + 16, bytes.size)
            sb.append(String.format("%08X  ", i))
            for (j in i until i + 16) {
                if (j < end) sb.append(String.format("%02X ", bytes[j].toInt() and 0xFF)) else sb.append("   ")
            }
            sb.append(" |")
            for (j in i until end) {
                val c = bytes[j].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append("|\n")
            i = end
        }
        return sb.toString()
    }

    private fun latestRawFile(): File? {
        try { rawOut?.flush() } catch (_: Exception) {}
        val f = rawFile ?: logDir().listFiles()?.filter { it.name.endsWith(".bin") }
            ?.maxByOrNull { it.lastModified() }
        return if (f != null && f.exists() && f.length() > 0L) f else null
    }

    /**
     * Один текстовый файл «для Claude»: заголовок, расшифровка и полный HEX-дамп
     * (HEX без потерь — по нему восстанавливаются исходные байты).
     * Текстовый файл принимает приложение Claude через «Поделиться».
     */
    private fun buildReport(raw: File): File {
        val bytes = raw.readBytes()
        val enc = encSpinner.selectedItemPosition
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("=== Panel Log 3030: лог принтерного порта NFS2-3030 ===\n")
        sb.append("Файл: ").append(raw.name).append('\n')
        sb.append("Создан отчёт: ").append(now).append('\n')
        sb.append("Скорость: ").append(selectedBaud()).append(" 8N1\n")
        sb.append("Байт: ").append(bytes.size).append('\n')
        sb.append("Кодировка расшифровки: ").append(ENCODINGS[enc]).append('\n')
        sb.append("Версия приложения: ").append(appVersion()).append("\n\n")
        sb.append("=== РАСШИФРОВКА ===\n")
        sb.append(decodeText(bytes, enc)).append("\n\n")
        sb.append("=== HEX (сырые байты) ===\n")
        sb.append(hexDump(bytes))
        val out = File(logDir(), raw.nameWithoutExtension.replace("panel_raw_", "panel_log_") + ".txt")
        out.writeText(sb.toString())
        return out
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    /** «Отправить»: один .txt файл — удобно поделиться прямо в чат с Claude, WhatsApp или почту. */
    private fun shareLogs() {
        val raw = latestRawFile()
        if (raw == null) {
            Toast.makeText(this, "Лог пока пустой", Toast.LENGTH_SHORT).show()
            return
        }
        val report = try { buildReport(raw) } catch (e: Exception) {
            Toast.makeText(this, "Ошибка подготовки файла: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", report)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Лог NFS2-3030 ${report.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Отправить лог"))
    }

    /** «Копировать»: текст в буфер обмена — вставить в чат одним нажатием. */
    private fun copyLogs() {
        val raw = latestRawFile()
        if (raw == null) {
            Toast.makeText(this, "Лог пока пустой", Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = raw.readBytes()
        val enc = encSpinner.selectedItemPosition
        val sb = StringBuilder()
        sb.append("Лог NFS2-3030, ").append(raw.name).append(", ").append(bytes.size).append(" байт, ")
            .append(selectedBaud()).append(" 8N1, ").append(ENCODINGS[enc]).append("\n\n")
        sb.append(decodeText(bytes, enc))
        if (bytes.size <= 8_000) sb.append("\n\nHEX:\n").append(hexDump(bytes))
        var text = sb.toString()
        var cut = false
        if (text.length > 100_000) { text = text.substring(0, 100_000); cut = true }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Лог NFS2-3030", text))
        Toast.makeText(
            this,
            if (cut) "Скопировано начало лога (он большой — лучше «Отправить» файлом)" else "Лог скопирован — вставьте его в чат",
            Toast.LENGTH_LONG
        ).show()
    }
}
