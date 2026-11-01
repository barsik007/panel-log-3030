package com.panellog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
 * G1 Hotelo: принимает вывод принтерного порта (TB5) панели NFS2-3030 через
 * USB-RS232 переходник, разбирает события и отправляет/сохраняет отчёты.
 * Скорость, расшифровка и язык интерфейса — на экране «Настройки».
 */
class MainActivity : Activity(), SerialInputOutputManager.Listener {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.panellog.app.USB_PERMISSION"
        private const val MAX_SESSION_BYTES = 4 * 1024 * 1024
        private const val MAX_SCREEN_CHARS = 300_000
        private const val MENU_SETTINGS = 1
        private val BAUDS = intArrayOf(300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)
    }

    // ------------------------------------------------------- Тексты (ru / he)

    private val TEXTS: Map<String, Pair<String, String>> = mapOf(
        "notConnected" to ("Не подключено. Подключите переходник через OTG и нажмите «Подключить»." to "לא מחובר. חבר את המתאם דרך OTG ולחץ «התחבר»."),
        "adapterFound" to ("Обнаружен USB-переходник. Нажмите «Подключить»." to "זוהה מתאם USB. לחץ «התחבר»."),
        "noUsb" to ("USB-устройств не видно (проверьте OTG-переходник и что OTG включён в настройках телефона)." to "לא נמצאו התקני USB (בדוק את מתאם ה-OTG ושה-OTG מופעל בהגדרות הטלפון)."),
        "usbDevices" to ("USB-устройства:" to "התקני USB:"),
        "driver" to ("драйвер" to "דרייבר"),
        "notSerial" to ("не последовательный порт" to "לא יציאה טורית"),
        "site" to ("Объект:" to "אתר:"),
        "siteHint" to ("название объекта" to "שם האתר"),
        "connect" to ("Подключить" to "התחבר"),
        "disconnect" to ("Отключить" to "נתק"),
        "clear" to ("Очистить" to "נקה"),
        "send" to ("Отправить" to "שלח"),
        "save" to ("Скачать" to "שמור"),
        "paper" to ("Как на бумаге" to "כמו בהדפסה"),
        "received" to ("Принято: %d байт" to "התקבלו: %d בתים"),
        "permDenied" to ("Разрешение на USB не выдано" to "לא ניתנה הרשאת USB"),
        "detached" to ("Переходник отключён" to "המתאם נותק"),
        "disconnected" to ("Отключено" to "מנותק"),
        "notFound" to ("Переходник не найден. Подключите его через OTG." to "המתאם לא נמצא. חבר אותו דרך OTG."),
        "askPerm" to ("Запрашиваю разрешение на USB…" to "מבקש הרשאת USB…"),
        "openFail" to ("Не удалось открыть устройство" to "לא ניתן לפתוח את ההתקן"),
        "portErr" to ("Ошибка открытия порта: " to "שגיאה בפתיחת היציאה: "),
        "connected" to ("Подключено: %s, %d 8N1. Жду данные…" to "מחובר: %s, %d 8N1. ממתין לנתונים…"),
        "lost" to ("Связь прервана: " to "החיבור נותק: "),
        "logFail" to ("Не удалось создать файл лога: " to "לא ניתן ליצור קובץ יומן: "),
        "emptyLog" to ("Лог пока пустой" to "היומן ריק"),
        "noEvents" to ("Нет разобранных событий — сначала снимите лог с панели" to "אין אירועים — קודם קרא נתונים מהרכזת"),
        "sendTitle" to ("Отправить" to "שליחה"),
        "saveTitle" to ("Сохранить на телефон" to "שמירה בטלפון"),
        "waHe" to ("WhatsApp — иврит" to "וואטסאפ — עברית"),
        "waRu" to ("WhatsApp — иврит + русский" to "וואטסאפ — עברית + רוסית"),
        "pdfHe" to ("PDF — иврит" to "PDF — עברית"),
        "pdfRu" to ("PDF — иврит + русский" to "PDF — עברית + רוסית"),
        "logFile" to ("Файл лога (для Claude)" to "קובץ יומן (ל-Claude)"),
        "sendList" to ("Отправить список" to "שלח רשימה"),
        "sendPdf" to ("Отправить PDF" to "שלח PDF"),
        "sendLog" to ("Отправить лог" to "שלח יומן"),
        "pdfErr" to ("Ошибка PDF: " to "שגיאת PDF: "),
        "fileErr" to ("Ошибка подготовки файла: " to "שגיאה בהכנת הקובץ: "),
        "saved" to ("Сохранено в «Загрузки/G1 Hotelo»:\n" to "נשמר ב«הורדות/G1 Hotelo»:\n"),
        "savedApp" to ("Сохранено:\n" to "נשמר:\n"),
        "settings" to ("Настройки" to "הגדרות"),
        "baud" to ("Скорость порта" to "מהירות יציאה"),
        "enc" to ("Расшифровка" to "פענוח"),
        "lang" to ("Язык интерфейса" to "שפת ממשק"),
        "ok" to ("Сохранить" to "שמור"),
        "cancel" to ("Отмена" to "ביטול"),
        "baudNext" to ("Новая скорость будет применена при следующем подключении" to "המהירות החדשה תחול בחיבור הבא"),
        "events" to ("Событий" to "אירועים"),
        "devicesW" to ("устройства" to "התקנים"),
        "systemW" to ("системные" to "מערכת"),
        "SYSTEM" to ("СИСТЕМА" to "מערכת"),
        "unknown" to ("Внимание: %d незнакомых символов (□) — пришлите лог" to "שים לב: %d תווים לא מוכרים (□) — שלח את היומן")
    )

    private val ENCODINGS_RU = arrayOf("Авто: разбор панели", "Текст (ASCII)", "Иврит SI-960 (7 бит)", "Иврит CP862 (DOS)")
    private val ENCODINGS_HE = arrayOf("אוטומטי: פענוח רכזת", "טקסט (ASCII)", "עברית SI-960 (7 ביט)", "עברית CP862 (DOS)")

    private fun he(): Boolean = prefs().getString("lang", "ru") == "he"
    private fun t(key: String): String = TEXTS[key]?.let { if (he()) it.second else it.first } ?: key
    private fun encodings() = if (he()) ENCODINGS_HE else ENCODINGS_RU

    // ------------------------------------------------------- Состояние

    private lateinit var usbManager: UsbManager
    private lateinit var statusView: TextView
    private lateinit var devicesView: TextView
    private lateinit var counterView: TextView
    private lateinit var outputView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var paperBox: CheckBox
    private lateinit var siteInput: AutoCompleteTextView
    private lateinit var connectButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderRunnable = Runnable { rerender() }

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var rawFile: File? = null
    private var rawOut: FileOutputStream? = null

    private val sessionBytes = ByteArrayOutputStream()
    private var totalBytes = 0L
    private var pendingConnect = false
    private var statusKey = "notConnected"
    private var statusExtra = ""
    private var statusError = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (pendingConnect) {
                        pendingConnect = false
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) connect() else setStatus("permDenied", true)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (port != null) {
                        disconnect()
                        setStatus("detached", true)
                    }
                    refreshDevices()
                }
            }
        }
    }

    // ------------------------------------------------------- Жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showUi()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            refreshDevices()
            setStatus("adapterFound", false)
        }
    }

    override fun onDestroy() {
        disconnect()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, "⚙ " + t("settings")).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SETTINGS) { settingsDialog(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------- Экран

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Строит (или перестраивает после смены языка) главный экран. */
    private fun showUi() {
        val site = if (::siteInput.isInitialized) siteInput.text.toString() else prefs().getString("lastSite", "") ?: ""
        val paper = ::paperBox.isInitialized && paperBox.isChecked
        title = "G1 Hotelo"
        setContentView(buildUi(site, paper))
        window.decorView.layoutDirection = if (he()) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        invalidateOptionsMenu()
        refreshDevices()
        if (port != null) connectButton.text = t("disconnect")
        applyStatus()
        updateCounter()
        rerender()
    }

    private fun buildUi(site: String, paper: Boolean): View {
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

        // Объект (запоминается, подсказки из прошлых)
        val siteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        siteRow.addView(TextView(this).apply { text = t("site"); textSize = 14f; setPaddingRelative(0, 0, dp(6), 0) })
        siteInput = AutoCompleteTextView(this).apply {
            hint = t("siteHint")
            textSize = 15f
            setSingleLine()
            threshold = 1
            setText(site)
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, knownSites()))
            setOnFocusChangeListener { _, has -> if (has) showDropDown() }
        }
        siteRow.addView(siteInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(siteRow)

        // Кнопки
        val buttonsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectButton = smallButton(t("connect")) {
            if (port == null) connect() else { disconnect(); setStatus("disconnected", false) }
        }
        buttonsRow.addView(connectButton, weighted())
        buttonsRow.addView(smallButton(t("clear")) { clearScreen() }, weighted())
        buttonsRow.addView(smallButton(t("send")) { shareMenu() }, weighted())
        buttonsRow.addView(smallButton(t("save")) { saveMenu() }, weighted())
        root.addView(buttonsRow)

        val infoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        paperBox = CheckBox(this).apply {
            text = t("paper")
            isChecked = paper
            setOnCheckedChangeListener { _, _ -> rerender() }
        }
        infoRow.addView(paperBox)
        counterView = TextView(this).apply {
            textSize = 12f
            setPaddingRelative(dp(12), 0, 0, 0)
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

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun setStatus(key: String, error: Boolean, extra: String = "") {
        statusKey = key; statusError = error; statusExtra = extra
        applyStatus()
    }

    private fun applyStatus() {
        if (!::statusView.isInitialized) return
        statusView.text = if (statusKey == "connected") String.format(t("connected"), statusExtra, baud())
            else t(statusKey) + statusExtra
        statusView.setTextColor(if (statusError) Color.rgb(200, 30, 30) else Color.rgb(20, 20, 20))
    }

    private fun updateCounter() {
        if (!::counterView.isInitialized) return
        counterView.text = String.format(t("received"), totalBytes)
    }

    private fun refreshDevices() {
        if (!::devicesView.isInitialized) return
        val all = usbManager.deviceList.values
        if (all.isEmpty()) {
            devicesView.text = t("noUsb")
            return
        }
        val supported = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .associateBy { it.device.deviceName }
        val sb = StringBuilder(t("usbDevices")).append('\n')
        for (d in all) {
            val driver = supported[d.deviceName]
            sb.append("• ").append(describe(d))
            if (driver != null) sb.append(" — ").append(t("driver")).append(": ")
                .append(driver.javaClass.simpleName.replace("SerialDriver", ""))
            else sb.append(" — ").append(t("notSerial"))
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

    // ------------------------------------------------------- Настройки

    private fun prefs() = getSharedPreferences("panellog", Context.MODE_PRIVATE)

    private fun baud(): Int = prefs().getInt("baud", 9600)
    private fun enc(): Int = prefs().getInt("enc", 0)

    private fun settingsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        fun label(s: String) = TextView(this).apply { text = s; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, dp(2)) }

        box.addView(label(t("baud")))
        val baudSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, BAUDS.map { it.toString() })
            setSelection(BAUDS.indexOf(baud()).coerceAtLeast(0))
        }
        box.addView(baudSp)

        box.addView(label(t("enc")))
        val encSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, encodings())
            setSelection(enc().coerceIn(0, 3))
        }
        box.addView(encSp)

        box.addView(label(t("lang")))
        val langGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val ruBtn = RadioButton(this).apply { id = View.generateViewId(); text = "Русский" }
        val heBtn = RadioButton(this).apply { id = View.generateViewId(); text = "עברית" }
        langGroup.addView(ruBtn); langGroup.addView(heBtn)
        langGroup.check(if (he()) heBtn.id else ruBtn.id)
        box.addView(langGroup)

        AlertDialog.Builder(this)
            .setTitle(t("settings"))
            .setView(box)
            .setPositiveButton(t("ok")) { _, _ ->
                val newBaud = BAUDS[baudSp.selectedItemPosition.coerceIn(0, BAUDS.size - 1)]
                val baudChanged = newBaud != baud()
                val newLang = if (langGroup.checkedRadioButtonId == heBtn.id) "he" else "ru"
                val langChanged = newLang != prefs().getString("lang", "ru")
                prefs().edit()
                    .putInt("baud", newBaud)
                    .putInt("enc", encSp.selectedItemPosition)
                    .putString("lang", newLang)
                    .apply()
                if (langChanged) showUi() else rerender()
                if (baudChanged && port != null) Toast.makeText(this, t("baudNext"), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    // ------------------------------------------------------- Подключение

    private fun connect() {
        refreshDevices()
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            setStatus("notFound", true)
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
            setStatus("askPerm", false)
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            setStatus("openFail", true)
            return
        }
        val p = driver.ports[0]
        try {
            p.open(connection)
            p.setParameters(baud(), 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.setDTR(true); p.setRTS(true) } catch (_: Exception) {}
        } catch (e: Exception) {
            setStatus("portErr", true, e.message ?: "")
            try { p.close() } catch (_: Exception) {}
            return
        }
        port = p
        openRawFile()
        rememberSite(rawFile)
        ioManager = SerialInputOutputManager(p, this).also { it.start() }

        connectButton.text = t("disconnect")
        setStatus("connected", false, describe(device))
    }

    private fun disconnect() {
        ioManager?.let { try { it.stop() } catch (_: Exception) {} }
        ioManager = null
        port?.let { try { it.close() } catch (_: Exception) {} }
        port = null
        closeRawFile()
        if (::connectButton.isInitialized) connectButton.text = t("connect")
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
                setStatus("lost", true, e.message ?: "")
            }
        }
    }

    private fun appendData(data: ByteArray) {
        if (sessionBytes.size() < MAX_SESSION_BYTES) sessionBytes.write(data)
        totalBytes += data.size
        updateCounter()
        // Разбор целиком, но не чаще раза в 0,4 с — данные идут пачками.
        mainHandler.removeCallbacks(renderRunnable)
        mainHandler.postDelayed(renderRunnable, 400)
    }

    private fun rerender() {
        if (!::outputView.isInitialized) return
        val bytes = sessionBytes.toByteArray()
        var text = if (enc() == 0) panelText(bytes, paperBox.isChecked) else decodeText(bytes, enc())
        if (text.length > MAX_SCREEN_CHARS) text = text.substring(text.length - MAX_SCREEN_CHARS / 2)
        outputView.text = text
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearScreen() {
        sessionBytes.reset()
        outputView.text = ""
    }

    /** Разбор вывода панели: список событий или строки «как на бумаге». */
    private fun panelText(bytes: ByteArray, paper: Boolean): String {
        val d = PanelDecoder.decode(bytes)
        if (!d.graphic) return d.visualLines.joinToString("\n")
        if (paper) return PanelDecoder.logicalLines(d.visualLines).joinToString("\n")
        return eventsText(d, he())
    }

    private fun eventsText(d: PanelDecoder.Decoded, heb: Boolean): String {
        fun tt(k: String) = TEXTS[k]?.let { if (heb) it.second else it.first } ?: k
        val events = PanelDecoder.parse(d.visualLines)
        val sb = StringBuilder()
        val title = PanelDecoder.title(d.visualLines)
        if (title.isNotEmpty()) sb.append(title).append('\n')
        val sys = events.count { it.isSystem }
        sb.append(tt("events")).append(": ").append(events.size)
            .append(" (").append(tt("devicesW")).append(' ').append(events.size - sys)
            .append(", ").append(tt("systemW")).append(' ').append(sys).append(")\n")
        if (d.unknownGlyphs.isNotEmpty()) sb.append(String.format(tt("unknown"), d.unknownGlyphs.size)).append('\n')
        sb.append('\n')
        events.forEachIndexed { i, e ->
            val n = (i + 1).toString() + ". "
            if (e.isSystem) {
                sb.append(n).append(tt("SYSTEM")).append(if (e.node.isNotEmpty()) " · " + e.node else "").append('\n')
            } else {
                sb.append(n).append(e.address)
                if (e.zone.isNotEmpty()) sb.append(" · ").append(e.zone)
                if (e.devType.isNotEmpty()) sb.append(" · ").append(
                    if (heb) PanelDecoder.typeHe(e.devType) else PanelDecoder.typeRu(e.devType) ?: e.devType)
                sb.append('\n')
            }
            sb.append("   ").append(e.problem)
            if (!heb) PanelDecoder.ru(e.problem)?.let { sb.append(" — ").append(it) }
            sb.append('\n')
            if (e.label.isNotEmpty() || e.label2.isNotEmpty()) {
                sb.append("   ").append(listOf(e.label, e.label2).filter { it.isNotEmpty() }.joinToString(" · ")).append('\n')
            }
            sb.append("   ").append(e.date).append(' ').append(e.time).append("\n\n")
        }
        return sb.toString()
    }

    private fun eventsTsv(d: PanelDecoder.Decoded): String {
        val sb = StringBuilder("Адрес\tЗона\tТип\tПроблема\tМесто\tМесто 2\tДата\tВремя\tУзел/панель\n")
        for (e in PanelDecoder.parse(d.visualLines)) {
            sb.append(listOf(e.address, e.zone, e.devType, e.problem, e.label, e.label2, e.date, e.time, e.node)
                .joinToString("\t")).append('\n')
        }
        return sb.toString()
    }

    /** Текстовый (не графический) вывод с выбранной кодировкой — запасной режим. */
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
                enc == 2 && c in 0x60..0x7A -> sb.append((0x05D0 + (c - 0x60)).toChar())
                enc == 3 && c in 0x80..0x9A -> sb.append((0x05D0 + (c - 0x80)).toChar())
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

    // ------------------------------------------------------- Файлы и объекты

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
            setStatus("logFail", true, e.message ?: "")
        }
    }

    private fun closeRawFile() {
        try { rawOut?.close() } catch (_: Exception) {}
        rawOut = null
    }

    private fun latestRawFile(): File? {
        try { rawOut?.flush() } catch (_: Exception) {}
        val f = rawFile ?: logDir().listFiles()?.filter { it.name.endsWith(".bin") }
            ?.maxByOrNull { it.lastModified() }
        return if (f != null && f.exists() && f.length() > 0L) f else null
    }

    private fun knownSites(): List<String> =
        (prefs().getStringSet("sites", emptySet()) ?: emptySet()).sorted()

    private fun currentSite(): String = if (::siteInput.isInitialized) siteInput.text.toString().trim() else ""

    /** Запоминает объект: в подсказках и рядом с файлом лога (panel_raw_….site). */
    private fun rememberSite(raw: File?) {
        val site = currentSite()
        if (site.isEmpty()) return
        val set = HashSet(prefs().getStringSet("sites", emptySet()) ?: emptySet())
        set.add(site)
        prefs().edit().putStringSet("sites", set).putString("lastSite", site).apply()
        siteInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, set.sorted()))
        raw?.let { try { File(it.parentFile, it.nameWithoutExtension + ".site").writeText(site) } catch (_: Exception) {} }
    }

    /** Объект для отчёта: введённый сейчас, иначе сохранённый вместе с логом. */
    private fun siteForReport(): String {
        val s = currentSite()
        if (s.isNotEmpty()) return s
        val raw = latestRawFile() ?: return ""
        return try { File(raw.parentFile, raw.nameWithoutExtension + ".site").readText().trim() } catch (_: Exception) { "" }
    }

    /** События из текущего сеанса, а если он пуст — из последнего сохранённого лога. */
    private fun currentEvents(): List<PanelDecoder.PanelEvent> {
        val bytes = if (sessionBytes.size() > 0) sessionBytes.toByteArray()
            else latestRawFile()?.readBytes() ?: ByteArray(0)
        val d = PanelDecoder.decode(bytes)
        return if (d.graphic) PanelDecoder.parse(d.visualLines) else emptyList()
    }

    /** Безопасное имя файла: объект + дата. */
    private fun baseName(): String {
        val site = siteForReport().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(40)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return if (site.isNotEmpty()) "G1_${site}_$stamp" else "G1_$stamp"
    }

    // ------------------------------------------------------- Отправить

    private fun shareMenu() {
        val items = arrayOf(t("waHe"), t("waRu"), t("pdfHe"), t("pdfRu"), t("logFile"))
        AlertDialog.Builder(this)
            .setTitle(t("sendTitle") + siteForReport().let { if (it.isNotEmpty()) " · $it" else "" })
            .setItems(items) { _, which ->
                rememberSite(rawFile)
                when (which) {
                    0 -> shareWhatsapp(true)
                    1 -> shareWhatsapp(false)
                    2 -> sharePdf(true)
                    3 -> sharePdf(false)
                    else -> shareLogs()
                }
            }
            .show()
    }

    private fun shareWhatsapp(heOnly: Boolean) {
        val events = currentEvents()
        if (events.isEmpty()) { toastLong(t("noEvents")); return }
        val text = ReportExport.whatsappText(events, siteForReport(), heOnly)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val wa = Intent(send).setPackage("com.whatsapp")
        try {
            if (wa.resolveActivity(packageManager) != null) startActivity(wa)
            else startActivity(Intent.createChooser(send, t("sendList")))
        } catch (_: Exception) {
            startActivity(Intent.createChooser(send, t("sendList")))
        }
    }

    private fun makePdf(heOnly: Boolean): File? {
        val events = currentEvents()
        if (events.isEmpty()) { toastLong(t("noEvents")); return null }
        val out = File(logDir(), baseName() + (if (heOnly) "_he" else "_he_ru") + ".pdf")
        return try {
            ReportExport.writePdf(events, out, siteForReport(), heOnly)
            out
        } catch (e: Exception) {
            toastLong(t("pdfErr") + e.message); null
        }
    }

    private fun sharePdf(heOnly: Boolean) {
        val out = makePdf(heOnly) ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, out.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, t("sendPdf")))
    }

    /**
     * Один текстовый файл «для Claude»: заголовок, события, таблица и полный HEX-дамп
     * (HEX без потерь — по нему восстанавливаются исходные байты).
     */
    private fun buildReport(raw: File): File {
        val bytes = raw.readBytes()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("=== G1 Hotelo: лог принтерного порта NFS2-3030 ===\n")
        sb.append("Файл: ").append(raw.name).append('\n')
        sb.append("Объект: ").append(siteForReport().ifEmpty { "не указан" }).append('\n')
        sb.append("Создан отчёт: ").append(now).append('\n')
        sb.append("Скорость: ").append(baud()).append(" 8N1\n")
        sb.append("Байт: ").append(bytes.size).append('\n')
        sb.append("Расшифровка: ").append(ENCODINGS_RU[enc().coerceIn(0, 3)]).append('\n')
        sb.append("Версия приложения: ").append(appVersion()).append("\n\n")
        val d = PanelDecoder.decode(bytes)
        if (d.graphic) {
            sb.append("=== СОБЫТИЯ ===\n")
            sb.append(eventsText(d, false)).append('\n')
            sb.append("=== ТАБЛИЦА (TSV) ===\n")
            sb.append(eventsTsv(d)).append('\n')
            sb.append("=== КАК НА БУМАГЕ ===\n")
            sb.append(PanelDecoder.logicalLines(d.visualLines).joinToString("\n")).append("\n\n")
            if (d.unknownGlyphs.isNotEmpty()) {
                sb.append("=== НЕЗНАКОМЫЕ СИМВОЛЫ ===\n")
                sb.append(d.unknownGlyphs.joinToString("\n")).append("\n\n")
            }
        } else {
            sb.append("=== РАСШИФРОВКА ===\n")
            sb.append(decodeText(bytes, if (enc() == 0) 1 else enc())).append("\n\n")
        }
        sb.append("=== HEX (сырые байты) ===\n")
        sb.append(hexDump(bytes))
        val out = File(logDir(), raw.nameWithoutExtension.replace("panel_raw_", "panel_log_") + ".txt")
        out.writeText(sb.toString())
        return out
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun shareLogs() {
        val raw = latestRawFile()
        if (raw == null) { toastShort(t("emptyLog")); return }
        val report = try { buildReport(raw) } catch (e: Exception) {
            toastLong(t("fileErr") + e.message); return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", report)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, report.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, t("sendLog")))
    }

    // ------------------------------------------------------- Скачать на телефон

    private fun saveMenu() {
        val items = arrayOf(t("pdfHe"), t("pdfRu"), t("logFile"))
        AlertDialog.Builder(this)
            .setTitle(t("saveTitle") + siteForReport().let { if (it.isNotEmpty()) " · $it" else "" })
            .setItems(items) { _, which ->
                rememberSite(rawFile)
                when (which) {
                    0 -> makePdf(true)?.let { saveToDownloads(it, baseName() + "_he.pdf", "application/pdf") }
                    1 -> makePdf(false)?.let { saveToDownloads(it, baseName() + "_he_ru.pdf", "application/pdf") }
                    else -> {
                        val raw = latestRawFile()
                        if (raw == null) toastShort(t("emptyLog"))
                        else try {
                            saveToDownloads(buildReport(raw), baseName() + "_log.txt", "text/plain")
                        } catch (e: Exception) { toastLong(t("fileErr") + e.message) }
                    }
                }
            }
            .show()
    }

    /** Копирует файл в «Загрузки/G1 Hotelo» (Android 10+), на старых — оставляет в папке приложения. */
    private fun saveToDownloads(src: File, name: String, mime: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/G1 Hotelo")
                }
                val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore")
                contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                toastLong(t("saved") + name)
            } catch (e: Exception) {
                toastLong(t("fileErr") + e.message)
            }
        } else {
            val dst = File(logDir(), name)
            try { src.copyTo(dst, overwrite = true); toastLong(t("savedApp") + dst.absolutePath) }
            catch (e: Exception) { toastLong(t("fileErr") + e.message) }
        }
    }

    private fun toastShort(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun toastLong(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
