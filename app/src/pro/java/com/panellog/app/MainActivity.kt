package com.panellog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * G1 Hotelo — локальная версия: объекты, снятие лога с панели NFS2-3030,
 * автосравнение с прошлым логом, карточки неисправностей, отчёт за период.
 */
class MainActivity : Activity(), SerialInputOutputManager.Listener {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.panellog.app.USB_PERMISSION"
        private const val MAX_CAPTURE_BYTES = 8 * 1024 * 1024
        private const val MENU_SETTINGS = 1
        private const val REQ_PHOTO = 7
        private const val REQ_GALLERY = 8
        private val BAUDS = intArrayOf(300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)
    }

    private enum class Scr { HOME, SITE, CAPTURE, FAULT, LOGS, PAPER, RECORD, RECLIST }

    private lateinit var store: Store
    private lateinit var usbManager: UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scr = Scr.HOME
    private var siteId: String? = null
    private var faultId: String? = null
    private var tab = 0
    // множественный выбор ошибок (долгое нажатие → «Выбрать несколько»)
    private var selMode = false
    private val selected = LinkedHashSet<String>()
    private var visibleIds: List<String> = emptyList()
    private var selCountView: TextView? = null
    private var panelSel: String? = null      // выбранная панель на экране объекта (null = все)
    private var capturePanelId: String? = null
    /** Что снимаем: "faults" (неисправности, со сравнением) | "alarms" | "history" (отдельно, без сравнения). */
    private var captureKind = "faults"
    private var searchText = ""
    private var paperText = ""
    // экран тревог / истории с поиском
    private var recRec: RecordRec? = null
    private var recEvents: List<PanelDecoder.PanelEvent> = emptyList()
    private var recHay: List<String> = emptyList()
    private var recQuery = ""
    private var recList: LinearLayout? = null
    private var recCount: TextView? = null

    // USB / снятие лога
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var pendingConnect = false
    private val captureBytes = ByteArrayOutputStream()
    private var captureStatus = ""
    private var captureError = false
    private var captureStatusView: TextView? = null
    private var captureCounterView: TextView? = null
    private var capturePreview: TextView? = null
    private var captureConnectBtn: Button? = null
    private var renderScheduled = false
    private var lastEventCount = 0
    private val captureRender = Runnable { renderScheduled = false; updateCaptureViews() }

    // список неисправностей объекта (перерисовка при поиске)
    private var faultListBox: LinearLayout? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> if (pendingConnect) {
                    pendingConnect = false
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else setCapture(I18n.t("permDenied"), true)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (port != null) {
                    disconnect(); setCapture(I18n.t("detached"), true)
                }
            }
        }
    }

    // ================================================================ Жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        I18n.he = prefs().getString("lang", "ru") == "he"
        AutoTr.init(this)
        AutoTr.onUpdate = { if (scr == Scr.SITE) fillFaultList() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        render()
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) mainHandler.post { autoCapture() }
        else intent?.let { i -> mainHandler.post { handleFileIntent(i) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) autoCapture()
        else handleFileIntent(intent)
    }

    /** Переходник вставлен: сразу к снятию лога (на открытом или последнем объекте). */
    private fun autoCapture() {
        if (port != null) return
        when (scr) {
            Scr.CAPTURE -> connect()
            Scr.HOME -> {
                val last = prefs().getString("lastSiteId", null)?.let { store.site(it) }
                if (last == null) { toast(I18n.t("pickSite")); return }
                AlertDialog.Builder(this).setMessage(I18n.f("autoCapQ", last.name))
                    .setPositiveButton(I18n.t("yes")) { _, _ ->
                        siteId = last.id; panelSel = null; tab = 0; searchText = ""
                        choosePanelThen { chooseKindThen { startCapture() } }
                    }
                    .setNegativeButton(I18n.t("otherSite")) { _, _ -> toast(I18n.t("pickSite")) }
                    .show()
            }
            else -> {
                if (siteId == null) return
                saveCommentIfAny()
                choosePanelThen { startCapture() }
            }
        }
    }

    override fun onDestroy() {
        disconnect()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, "⚙ " + I18n.t("settings")).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SETTINGS) { settingsDialog(); return true }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (scr) {
            Scr.HOME -> super.onBackPressed()
            Scr.SITE -> if (selMode) { selMode = false; selected.clear(); render() } else go(Scr.HOME)
            Scr.CAPTURE -> leaveCapture()
            Scr.FAULT -> { saveCommentIfAny(); go(Scr.SITE) }
            Scr.LOGS -> go(Scr.SITE)
            Scr.PAPER -> go(Scr.LOGS)
            Scr.RECORD -> go(Scr.RECLIST)
            Scr.RECLIST -> go(Scr.SITE)
        }
    }

    private fun prefs() = getSharedPreferences("panellog", Context.MODE_PRIVATE)
    private fun baud(): Int = prefs().getInt("baud", 9600)

    private fun go(s: Scr) { scr = s; render() }

    private fun render() {
        title = getString(R.string.app_name)
        faultListBox = null
        captureStatusView = null; captureCounterView = null; capturePreview = null; captureConnectBtn = null
        val v = when (scr) {
            Scr.HOME -> homeView()
            Scr.SITE -> siteView()
            Scr.CAPTURE -> captureView()
            Scr.FAULT -> faultView()
            Scr.LOGS -> logsView()
            Scr.PAPER -> paperView()
            Scr.RECORD -> recordView()
            Scr.RECLIST -> recListView()
        }
        if (scr != Scr.SITE) { selMode = false; selected.clear() }
        setContentView(if (scr == Scr.SITE && selMode) withSelBar(v) else v)
        window.decorView.layoutDirection = if (I18n.he) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        invalidateOptionsMenu()
    }

    // ================================================================ Помощники UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun page(): Pair<ScrollView, LinearLayout> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(24))
        }
        val sv = ScrollView(this).apply { addView(box); setBackgroundColor(Color.rgb(243, 244, 246)) }
        return sv to box
    }

    private fun tv(text: String, size: Float = 15f, bold: Boolean = false, color: Int = Color.rgb(21, 24, 28)) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }

    private fun btn(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f
        if (primary) {
            background = rounded(Color.rgb(179, 38, 30), 10)
            setTextColor(Color.WHITE)
        }
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 12, Color.rgb(221, 225, 230))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(4)
        })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun fmt(ts: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(ts))
    private fun fmtD(ts: Long) = SimpleDateFormat("dd.MM", Locale.US).format(Date(ts))

    private fun input(hint: String, value: String = "", multi: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); textSize = 15f
        inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        if (multi) { minLines = 2; gravity = Gravity.TOP or Gravity.START }
    }

    private fun dialogBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }

    // ================================================================ Объекты (главный экран)

    private fun homeView(): View {
        val (sv, box) = page()
        box.addView(tv(I18n.t("sites"), 24f, true))
        box.addView(btn(I18n.t("newSite"), true) { siteDialog(null) }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) }
        })
        val sites = store.sites()
        if (sites.isEmpty()) box.addView(tv(I18n.t("noSites"), 14f, color = Color.DKGRAY).apply { setPadding(0, dp(16), 0, 0) })
        for (s in sites) {
            val c = card()
            c.addView(tv(s.name, 18f, true))
            if (s.address.isNotEmpty()) c.addView(tv(s.address, 13f, color = Color.DKGRAY))
            val info = (if (s.lastLogTs > 0) I18n.t("lastLog") + " " + fmt(s.lastLogTs) else I18n.t("noLogs")) +
                " · " + I18n.f("activeN", s.activeCount)
            c.addView(tv(info, 13f, color = Color.rgb(85, 93, 104)))
            c.setOnClickListener {
                siteId = s.id; tab = 0; searchText = ""; panelSel = null
                prefs().edit().putString("lastSiteId", s.id).apply()
                go(Scr.SITE)
            }
            c.setOnLongClickListener { siteMenu(s); true }
            box.addView(c)
        }
        return sv
    }

    private fun siteDialog(edit: Site?) {
        val b = dialogBox()
        val name = input(I18n.t("siteName"), edit?.name ?: "")
        val addr = input(I18n.t("siteAddress"), edit?.address ?: "")
        val note = input(I18n.t("siteNote"), edit?.note ?: "", true)
        b.addView(name); b.addView(addr); b.addView(note)
        AlertDialog.Builder(this)
            .setTitle(if (edit == null) I18n.t("newSite") else I18n.t("renameSite"))
            .setView(b)
            .setPositiveButton(I18n.t("ok")) { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                if (edit == null) {
                    siteId = store.addSite(n, addr.text.toString().trim(), note.text.toString().trim())
                    store.addPanel(siteId!!, "רכזת 1")
                    tab = 0; searchText = ""; panelSel = null; go(Scr.SITE)
                } else {
                    store.updateSite(edit.id, n, addr.text.toString().trim(), note.text.toString().trim()); render()
                }
            }
            .setNegativeButton(I18n.t("cancel"), null)
            .show()
    }

    private fun siteMenu(s: Site) {
        AlertDialog.Builder(this).setTitle(s.name)
            .setItems(arrayOf(I18n.t("renameSite"), I18n.t("deleteSite"))) { _, w ->
                if (w == 0) siteDialog(s)
                else AlertDialog.Builder(this).setMessage(I18n.f("deleteSiteQ", s.name))
                    .setPositiveButton(I18n.t("delete")) { _, _ ->
                        store.deleteSite(s.id)
                        if (siteId == s.id) siteId = null
                        go(Scr.HOME)
                    }
                    .setNegativeButton(I18n.t("cancel"), null).show()
            }.show()
    }

    // ================================================================ Объект

    private fun siteView(): View {
        val s = siteId?.let { store.site(it) } ?: return homeView().also { scr = Scr.HOME }
        val (sv, box) = page()
        box.addView(tv(s.name, 22f, true))
        val sub = listOf(s.address, s.note).filter { it.isNotEmpty() }.joinToString(" · ")
        if (sub.isNotEmpty()) box.addView(tv(sub, 13f, color = Color.DKGRAY))

        // панели (רכזות)
        val panels = store.panels(s.id)
        if (panelSel != null && panels.none { it.id == panelSel }) panelSel = null
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun chip(label: String, sel: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f
            background = rounded(if (sel) Color.rgb(21, 24, 28) else Color.WHITE, 18, Color.rgb(195, 201, 209))
            setTextColor(if (sel) Color.WHITE else Color.rgb(21, 24, 28))
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        val chipLp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(6) } }
        if (panels.size > 1) chips.addView(chip(I18n.t("all") + " · " + panels.sumOf { it.activeCount }, panelSel == null, { panelSel = null; render() }), chipLp())
        for (p in panels) chips.addView(chip(p.name + " · " + p.activeCount, panelSel == p.id || panels.size == 1,
            { panelSel = p.id; render() }, { panelMenu(p) }), chipLp())
        chips.addView(chip(I18n.t("newPanel"), false, { panelDialog(null) }), chipLp())
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        // 2×2: снять лог · как есть / отчёт · логи для Claude
        box.addView(row(
            btn(I18n.t("takeLog"), true) { choosePanelThen { chooseKindThen { startCapture() } } },
            btn(I18n.t("sendAsIs")) { asIsMenu() }
        ).apply { setPadding(0, dp(8), 0, 0) })
        box.addView(row(
            btn(I18n.t("report")) { sendMenu() },
            btn(I18n.t("logsClaude")) { go(Scr.LOGS) }
        ))
        // тревоги и история — отдельная кнопка на экране объекта
        box.addView(btn(I18n.t("recBtn") + store.records(s.id).size.let { if (it > 0) " · $it" else "" }) { go(Scr.RECLIST) }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        })

        val logs = store.logs(s.id).filter { panelSel == null || it.panelId == panelSel }
        val faults = store.faults(s.id).filter { panelSel == null || it.panelId == panelSel || it.panelId.isEmpty() }
        if (logs.isEmpty() && faults.isEmpty()) {
            box.addView(tv(I18n.t("noLogsYet"), 14f, color = Color.DKGRAY).apply { setPadding(0, dp(14), 0, 0) })
            box.addView(entryButton())
            return sv
        }
        // сводка
        val active = faults.count { it.active }
        var fixedN = 0; var newN = 0
        for (p in panels) {
            if (panelSel != null && p.id != panelSel) continue
            val pl = logs.filter { it.panelId == p.id }
            if (pl.size >= 2) {
                val b = store.logFaultIds(pl[0].id); val a = store.logFaultIds(pl[1].id)
                fixedN += a.count { it !in b }; newN += b.count { it !in a }
            }
        }
        if (logs.isNotEmpty()) box.addView(tv(I18n.f("summary", fmt(logs[0].ts), active, fixedN, newN), 13f, color = Color.rgb(60, 66, 75))
            .apply { setPadding(0, dp(10), 0, dp(2)) })
        // подсказки по шлейфам
        val pName = panels.associate { it.id to it.name }
        faults.filter { it.active && !it.isSystem && it.loop.isNotEmpty() }
            .groupBy { Triple(it.panelId, it.loop, it.problem) }
            .filter { it.value.size >= 5 }
            .forEach { (k, v) ->
                val prefix = if (panels.size > 1) (pName[k.first] ?: "") + " · " else ""
                box.addView(tv("💡 " + prefix + I18n.f("loopHint", "L" + k.second, v.size, I18n.panel(k.third)), 13f,
                    color = Color.rgb(122, 74, 0)).apply {
                    background = rounded(Color.rgb(255, 241, 214), 8); setPadding(dp(8), dp(6), dp(8), dp(6))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                })
            }
        // поиск
        val search = input(I18n.t("search"), searchText).apply {
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { searchText = e?.toString() ?: ""; fillFaultList() }
            })
        }
        box.addView(search)
        // вкладки
        val tabs = listOf(I18n.t("tabActive"), I18n.t("tabFixed"), I18n.t("tabAll"))
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.forEachIndexed { i, label ->
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 13f
                background = rounded(if (i == tab) Color.rgb(21, 24, 28) else Color.WHITE, 18, Color.rgb(195, 201, 209))
                setTextColor(if (i == tab) Color.WHITE else Color.rgb(21, 24, 28))
                setOnClickListener { tab = i; render() }
            }
            tabRow.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        }
        box.addView(tabRow)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        faultListBox = list
        fillFaultList()
        return sv
    }

    private fun fillFaultList() {
        val list = faultListBox ?: return
        val sid = siteId ?: return
        list.removeAllViews()
        val panels = store.panels(sid)
        val pName = panels.associate { it.id to it.name }
        val logs = store.logs(sid)
        val lastTsBy = panels.associate { p -> p.id to (logs.firstOrNull { it.panelId == p.id }?.ts ?: 0L) }
        val countBy = panels.associate { p -> p.id to logs.count { it.panelId == p.id } }
        val q = searchText.trim().lowercase()
        val faults = store.faults(sid).filter { panelSel == null || it.panelId == panelSel || it.panelId.isEmpty() }.filter {
            when (tab) { 0 -> it.active; 1 -> !it.active; else -> true }
        }.filter {
            q.isEmpty() || listOf(it.address, it.zone, it.label, it.label2, it.problem, it.node, it.devType,
                PanelDecoder.ru(it.problem) ?: "", store.actionText(it.action), it.comment)
                .any { s -> s.lowercase().contains(q) }
        }.let { fs -> if (tab == 1) fs.sortedByDescending { it.resolvedTs } else fs.sortedBy {
            // сверху — что ещё нужно устранить; ниже «не актуально»; в самом конце «починил»
            // ошибки панели → «не актуально» → записи техника → «починил» → убранные из отчёта
            when { it.hidden -> 4; it.active && it.repaired -> 3; it.isManual -> 2; it.parked -> 1; else -> 0 }
        } }
        if (faults.isEmpty()) {
            list.addView(tv(I18n.t("noFaults"), 14f, color = Color.DKGRAY).apply { setPadding(0, dp(12), 0, 0) })
            list.addView(entryButton())
            return
        }
        if (!I18n.he) AutoTr.request(faults.flatMap { listOf(it.label, it.label2, it.node, it.problem) })
        val showPanel = panelSel == null && panels.size > 1
        visibleIds = faults.map { it.id }
        for (f in faults) list.addView(faultRow(f, lastTsBy[f.panelId] ?: 0L, countBy[f.panelId] ?: 0,
            if (showPanel) pName[f.panelId] ?: "" else ""))
        list.addView(entryButton())
    }

    /** В конце списка: ручная запись по объекту (без привязки к רכזת). */
    private fun entryButton(): View = btn(I18n.t("newEntryLong")) { entryDialog(null, "") }.apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12); bottomMargin = dp(24) }
    }

    private fun faultRow(f: Fault, lastTs: Long, logCount: Int, panelTag: String): View {
        val c = card()
        if (panelTag.isNotEmpty()) c.addView(tv(panelTag, 12f, true, Color.rgb(179, 38, 30)))
        val head = (if (f.isManual) I18n.t("manualEntry") + (if (f.address.isNotEmpty()) " · " + f.address else "")
            else if (f.isSystem) I18n.t("system") + (if (f.node.isNotEmpty()) " · " + f.node else "")
            else f.address + (if (f.devType.isNotEmpty()) " · " + I18n.devType(f.devType) else ""))
        c.addView(tv(head, 15f, true))
        c.addView(tv(if (f.isManual) f.problem else I18n.panel(f.problem), 14f, true))   // суть ошибки — жирным
        addPlace(c, if (f.isSystem) listOf() else listOf(f.label, f.label2), 15f)
        // комментарий техника — светло-красным прямо в списке
        if (f.comment.isNotBlank()) c.addView(tv("💬 " + f.comment.trim(), 14f, color = Color.rgb(205, 70, 70)).apply {
            maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        })
        val badges = statusBadges(f, lastTs, logCount)
        if (badges.isNotEmpty()) c.addView(tv(badges, 13f, color = Color.rgb(30, 63, 138)).apply { setPadding(0, dp(4), 0, 0) })
        warning(f)?.let { c.addView(tv(it, 13f, color = Color.rgb(160, 30, 30))) }
        if (selMode) {
            val on = f.id in selected
            c.addView(tv(if (on) "☑" else "☐", 22f, true, Color.rgb(30, 63, 138)), 0)
            c.setOnClickListener { toggleSel(f.id) }
            c.setOnLongClickListener { toggleSel(f.id); true }
        } else {
            c.setOnClickListener { faultId = f.id; go(Scr.FAULT) }
            c.setOnLongClickListener { faultLongMenu(f); true }
        }
        if (f.parked) c.alpha = 0.4f  // «не актуально» — бледная
        if (f.isManual && !f.hidden && !(f.active && f.repaired))  // запись техника — светло-жёлтая, неброская
            c.background = rounded(Color.rgb(255, 250, 225), 12, Color.rgb(238, 226, 170))
        if (f.hidden) c.alpha = 0.3f  // убрана из отчёта
        else if (f.active && f.repaired) {  // «Починил» — бледно-розовая, не бросается в глаза
            c.background = rounded(Color.rgb(253, 232, 239), 12, Color.rgb(244, 200, 215))
            c.alpha = 0.6f
        }
        return c
    }

    /** Место установки жирным; при русском интерфейсе — строкой ниже автоперевод. */
    private fun addPlace(box: LinearLayout, parts: List<String>, size: Float) {
        val p = parts.filter { it.isNotEmpty() }
        if (p.isEmpty()) return
        box.addView(tv(p.joinToString(" · "), size, true))
        if (!I18n.he) {
            val ru = p.mapNotNull { AutoTr.get(it) }
            if (ru.isNotEmpty()) box.addView(tv(ru.joinToString(" · "), size - 1f, color = Color.rgb(85, 93, 104)).apply {
                setTypeface(typeface, Typeface.ITALIC)
            })
        }
    }

    private fun statusBadges(f: Fault, lastTs: Long, logCount: Int): String {
        val b = ArrayList<String>()
        if (!f.active) b.add(I18n.f("stFixed", fmtD(f.resolvedTs)))
        else if (logCount > 1 && f.firstSeen == lastTs) b.add(I18n.t("stNew"))
        if (f.recur > 0) b.add(I18n.f("stRecur", f.recur))
        if (f.active && f.repaired) b.add(I18n.f("stRepaired", fmtD(f.fixedTs)))
        if (f.hidden) b.add(I18n.t("stHidden"))
        if (f.parked) b.add(I18n.t("stParked"))
        if (f.action.isNotEmpty()) b.add("✔ " + store.actionText(f.action))
        return b.joinToString("  ")
    }

    /** Ручная запись по объекту: место, что не так, адрес (необязательно), ₪. Фото — в карточке. */
    private fun entryDialog(edit: Fault?, panelId: String) {
        val sid = siteId ?: return
        val what = input(I18n.t("entryWhat"), edit?.problem ?: "", true)
        val place = input(I18n.t("entryPlace"), edit?.label ?: "")
        val addr = input(I18n.t("entryAddr"), edit?.address ?: "").apply { setSingleLine() }
        val box = dialogBox().apply { addView(what); addView(place); addView(addr) }
        fun save(photo: Boolean) {
            val w = what.text.toString().trim()
            if (w.isEmpty()) { toast(I18n.t("entryEmpty")); return }
            val pl = place.text.toString().trim(); val ad = addr.text.toString().trim().uppercase()
            val id = if (edit == null) store.addManual(sid, panelId, ad, w, pl, false)
                else { store.updateManual(edit.id, ad, w, pl); edit.id }
            faultId = id; tab = 0; go(Scr.FAULT)   // карточка: фото, комментарий
            if (photo) takePhoto(id)
        }
        AlertDialog.Builder(this).setTitle(I18n.t("entryTitle")).setView(box)
            .setPositiveButton(I18n.t("ok")) { _, _ -> save(false) }
            .setNeutralButton(I18n.t("entryPhoto")) { _, _ -> save(true) }
            .setNegativeButton(I18n.t("cancel"), null).show()
    }

    private fun toggleSel(id: String) {
        if (!selected.remove(id)) selected.add(id)
        selCountView?.text = I18n.f("selectedN", selected.size)
        fillFaultList()
    }

    /** Нижняя панель режима выбора: «Выбрано: N» · Действие… · ✕ */
    private fun withSelBar(content: View): View {
        val root = android.widget.FrameLayout(this)
        root.addView(content, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(21, 24, 28)); setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        val cnt = tv(I18n.f("selectedN", selected.size), 15f, true, Color.WHITE)
        selCountView = cnt
        bar.addView(cnt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn(I18n.t("selAction"), true) { selActions() })
        bar.addView(btn("✕") { selMode = false; selected.clear(); render() }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        (content as? ScrollView)?.getChildAt(0)?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, dp(90)) }
        return root
    }

    private fun selActions() {
        val keys = listOf("selComment", "selPark", "selUnpark", "selRepair", "hideFault", "unhideFault", "selAll", "selNone")
        AlertDialog.Builder(this).setTitle(I18n.f("selectedN", selected.size))
            .setItems(keys.map { I18n.t(it) }.toTypedArray()) { _, w ->
                val key = keys[w]
                if (key == "selAll") { selected.addAll(visibleIds); selCountView?.text = I18n.f("selectedN", selected.size); fillFaultList(); return@setItems }
                if (key == "selNone") { selected.clear(); selCountView?.text = I18n.f("selectedN", 0); fillFaultList(); return@setItems }
                if (selected.isEmpty()) { toast(I18n.t("selEmpty")); return@setItems }
                if (key == "selComment") { selCommentDialog(); return@setItems }
                var n = 0
                for (id in selected) {
                    val f = store.fault(id) ?: continue
                    when (key) {
                        "selPark" -> if (!f.parked) { store.setParked(id, true); n++ }
                        "selUnpark" -> if (f.parked) { store.setParked(id, false); n++ }
                        "selRepair" -> if (f.active && !f.repaired) { store.setRepaired(id, true); n++ }
                        "hideFault" -> if (!f.hidden) { store.setHidden(id, true); n++ }
                        "unhideFault" -> if (f.hidden) { store.setHidden(id, false); n++ }
                    }
                }
                selMode = false; selected.clear(); render()
                toast(I18n.f("selDone", n))
            }.show()
    }

    /** Один комментарий сразу на все выбранные (например, обрыв линии — 10 датчиков подряд).
     *  Пустой комментарий заменяется, существующий — дополняется новой строкой (ничего не стирается). */
    private fun selCommentDialog() {
        val e = input(I18n.t("selCommentHint"), "", true)
        AlertDialog.Builder(this).setTitle(I18n.f("selCommentTitle", selected.size))
            .setView(dialogBox().apply { addView(e) })
            .setPositiveButton(I18n.t("save")) { _, _ ->
                val t = e.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                var n = 0
                for (id in selected) {
                    val f = store.fault(id) ?: continue
                    val old = f.comment.trim()
                    if (old.contains(t)) continue
                    store.setComment(id, if (old.isEmpty()) t else old + "\n" + t); n++
                }
                selMode = false; selected.clear(); render()
                toast(I18n.f("selDone", n))
            }
            .setNegativeButton(I18n.t("cancel"), null).show()
    }

    /** Долгое нажатие на ошибку: убрать из отчёта / вернуть / удалить совсем. */
    private fun faultLongMenu(f: Fault) {
        val title = (if (f.isManual) I18n.t("manualEntry") else if (f.isSystem) I18n.t("system") else f.address) + " · " +
            (if (f.isManual) f.problem else I18n.panel(f.problem))
        AlertDialog.Builder(this).setTitle(title)
            .setItems(arrayOf(I18n.t(if (f.hidden) "unhideFault" else "hideFault"), I18n.t("deleteFault"), I18n.t("selectMany"))) { _, w ->
                if (w == 2) { selMode = true; selected.clear(); selected.add(f.id); render(); return@setItems }
                if (w == 0) { store.setHidden(f.id, !f.hidden); fillFaultList() }
                else AlertDialog.Builder(this).setMessage(I18n.t("deleteFaultQ"))
                    .setPositiveButton(I18n.t("delete")) { _, _ -> store.deleteFault(f.id); render() }
                    .setNegativeButton(I18n.t("cancel"), null).show()
            }.show()
    }

    private fun warning(f: Fault): String? {
        if (!f.active && f.comment.isBlank() && f.action.isEmpty()) return I18n.t("warnNoAction")
        return null
    }

    // ================================================================ Карточка неисправности

    private var commentEdit: EditText? = null

    /** Карточка: сведения панели, «к оплате», строка «что сделано», фото. */
    private fun faultView(): View {
        val f = faultId?.let { store.fault(it) } ?: return siteView().also { scr = Scr.SITE }
        val (sv, box) = page()
        val logs = store.panelLogs(f.panelId)
        val pn = store.panel(f.panelId)
        if (pn != null) box.addView(tv(pn.name, 13f, true, Color.rgb(179, 38, 30)))
        val head = if (f.isManual) I18n.t("manualEntry") + (if (f.address.isNotEmpty()) " · " + f.address else "")
            else if (f.isSystem) I18n.t("system") else f.address
        box.addView(tv(head, 22f, true))
        box.addView(tv(if (f.isManual) f.problem else I18n.panel(f.problem), 17f, true).apply { setPadding(0, dp(4), 0, 0) })
        if (f.isManual) box.addView(btn(I18n.t("editEntry")) { saveCommentIfAny(); entryDialog(f, f.panelId) })
        val info = card()
        val placeParts = if (f.isSystem) listOf(f.node) else listOf(f.label, f.label2)
        if (!I18n.he) AutoTr.request(placeParts + f.problem)
        addPlace(info, placeParts, 16f)
        if (!f.isSystem && !f.isManual) {
            info.addView(tv(I18n.t("type") + ": " + I18n.devType(f.devType) +
                (if (f.zone.isNotEmpty()) "   " + I18n.t("zone") + ": " + f.zone else ""), 14f))
        }
        val badges = statusBadges(f, logs.firstOrNull()?.ts ?: 0, logs.size)
        info.addView(tv(if (f.active) I18n.t("stActive") + (if (badges.isNotEmpty()) "  $badges" else "") else badges, 13f, color = Color.rgb(30, 63, 138)))
        warning(f)?.let { info.addView(tv(it, 13f, color = Color.rgb(160, 30, 30))) }
        box.addView(info)

        // Починил (останется в отчёте как устранённая; панель подтвердит при следующем логе)
        if (f.active) box.addView(CheckBox(this).apply {
            text = "🔧 " + I18n.t("repaired"); textSize = 16f; isChecked = f.repaired
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, ch -> store.setRepaired(f.id, ch) }
        })

        // Не актуально (на утверждении / согласовании)
        box.addView(CheckBox(this).apply {
            text = "⏸ " + I18n.t("parked"); textSize = 16f; isChecked = f.parked
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, ch -> store.setParked(f.id, ch) }
        })

        // Что сделано / комментарий
        box.addView(tv(I18n.t("doneComment"), 15f, true).apply { setPadding(0, dp(6), 0, 0) })
        val ce = input(I18n.t("doneComment"), f.comment, true).apply { tag = f.comment; minLines = 3 }
        commentEdit = ce
        box.addView(ce)
        box.addView(btn(I18n.t("ok"), true) { saveCommentIfAny(); toast(I18n.t("savedOk")); render() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(4) }
        })

        // Фото
        box.addView(tv(I18n.t("photos"), 15f, true).apply { setPadding(0, dp(12), 0, dp(4)) })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(btn(I18n.t("addPhoto")) { takePhoto(f.id) }, LinearLayout.LayoutParams(dp(96), dp(96)))
        strip.addView(btn(I18n.t("fromGallery")) { pickFromGallery(f.id) }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginStart = dp(6) })
        for (p in store.photos(f.id)) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(thumb(p.path, dp(96)))
                setOnClickListener { openPhoto(p.path) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity).setMessage(I18n.t("delete") + "?")
                        .setPositiveButton(I18n.t("delete")) { _, _ -> store.deletePhoto(p); render() }
                        .setNegativeButton(I18n.t("cancel"), null).show(); true
                }
            }
            strip.addView(iv, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginStart = dp(6) })
        }
        box.addView(HorizontalScrollView(this).apply { addView(strip) })
        // Отправить эту ошибку: полное описание + все её фото (WhatsApp и т.п.)
        box.addView(btn(I18n.t("sendFault")) { saveCommentIfAny(); sendFault(f.id) }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12); bottomMargin = dp(24) }
        })
        return sv
    }

    private fun sendFault(fid: String) {
        val f = store.fault(fid) ?: return
        val site = store.site(f.siteId)?.name ?: ""
        val pn = store.panel(f.panelId)?.name ?: ""
        val text = PeriodExport.faultMessage(f, site, pn, store, true)
        val uris = ArrayList(store.photos(f.id).map { FileProvider.getUriForFile(this, "$packageName.files", File(it.path)) })
        val i = when (uris.size) {
            0 -> Intent(Intent.ACTION_SEND).setType("text/plain")
            1 -> Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uris[0])
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        i.putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, I18n.t("sendWith"))) } catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
    }

    private fun saveCommentIfAny() {
        val e = commentEdit ?: return
        val id = faultId ?: return
        val now = e.text.toString().trim()
        if (now != (e.tag as? String ?: "")) { store.setComment(id, now); e.tag = now }
    }

    private fun thumb(path: String, size: Int): android.graphics.Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Exception) { null }

    private fun takePhoto(fid: String) {
        saveCommentIfAny()
        val file = store.newPhotoFile()
        prefs().edit().putString("pendingPhoto", file.absolutePath).putString("pendingFault", fid).apply()
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(i, REQ_PHOTO) } catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
    }

    /** Фото из галереи (можно несколько сразу) — копируются в программу. */
    private fun pickFromGallery(fid: String) {
        saveCommentIfAny()
        prefs().edit().putString("pendingFault", fid).apply()
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(Intent.createChooser(i, I18n.t("fromGallery")), REQ_GALLERY) }
        catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
    }

    private fun importGallery(data: Intent?) {
        val fid = prefs().getString("pendingFault", null) ?: return
        prefs().edit().remove("pendingFault").apply()
        val uris = ArrayList<Uri>()
        data?.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        var n = 0
        for (u in uris) {
            try {
                val file = store.newPhotoFile()
                contentResolver.openInputStream(u)?.use { inp -> file.outputStream().use { inp.copyTo(it) } }
                if (file.length() > 0) { store.addPhoto(fid, file); n++ } else file.delete()
            } catch (_: Exception) { }
        }
        val f = store.fault(fid)
        if (f != null) { siteId = f.siteId; faultId = fid; scr = Scr.FAULT }
        render()
        if (n > 0) toast(I18n.f("photosAdded", n))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GALLERY) { if (resultCode == RESULT_OK) importGallery(data); return }
        if (requestCode != REQ_PHOTO) return
        val path = prefs().getString("pendingPhoto", null) ?: return
        val fid = prefs().getString("pendingFault", null) ?: return
        prefs().edit().remove("pendingPhoto").remove("pendingFault").apply()
        val file = File(path)
        if (resultCode == RESULT_OK && file.exists() && file.length() > 0) {
            store.addPhoto(fid, file)
            val f = store.fault(fid)
            if (f != null) { siteId = f.siteId; faultId = fid; scr = Scr.FAULT }
            render()
        } else file.delete()
    }

    private fun openPhoto(path: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", File(path))
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
    }

    // ================================================================ Снятие лога

    private fun startCapture() {
        captureBytes.reset()
        lastEventCount = 0
        captureStatus = ""
        go(Scr.CAPTURE)
        connect()
    }

    private fun captureView(): View {
        val s = siteId?.let { store.site(it) }
        val (sv, box) = page()
        val cp = capturePanelId?.let { store.panel(it) }
        box.addView(tv(I18n.t("capture") + (if (s != null) " · " + s.name else "") + (if (cp != null) " · " + cp.name else ""), 20f, true))
        val statusTv = tv(captureStatus.ifEmpty { I18n.t("notConnected") }, 14f, true,
            if (captureError) Color.rgb(200, 30, 30) else Color.rgb(21, 24, 28)).apply { setPadding(0, dp(8), 0, dp(4)) }
        captureStatusView = statusTv
        box.addView(statusTv)
        box.addView(tv(I18n.t("printHint"), 13f, color = Color.DKGRAY))
        val connectBtn = btn(if (port == null) I18n.t("connect") else I18n.t("disconnect")) {
            if (port == null) connect() else { disconnect(); setCapture(I18n.t("notConnected"), false) }
        }
        captureConnectBtn = connectBtn
        box.addView(row(connectBtn, btn(I18n.t("finish"), true) { finishCapture() }).apply { setPadding(0, dp(8), 0, 0) })
        val counterTv = tv("", 13f, color = Color.rgb(60, 66, 75)).apply { setPadding(0, dp(8), 0, dp(4)) }
        captureCounterView = counterTv
        box.addView(counterTv)
        val previewTv = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f
            setTextColor(Color.rgb(20, 230, 90)); setBackgroundColor(Color.BLACK)
            setPadding(dp(6), dp(6), dp(6), dp(6)); minHeight = dp(160)
        }
        capturePreview = previewTv
        box.addView(previewTv)
        updateCaptureViews()
        return sv
    }

    private fun setCapture(text: String, error: Boolean) {
        captureStatus = text; captureError = error
        captureStatusView?.let {
            it.text = text
            it.setTextColor(if (error) Color.rgb(200, 30, 30) else Color.rgb(21, 24, 28))
        }
        captureConnectBtn?.text = if (port == null) I18n.t("connect") else I18n.t("disconnect")
    }

    private fun updateCaptureViews() {
        val bytes = captureBytes.toByteArray()
        val d = PanelDecoder.decode(bytes)
        val events = if (d.graphic) PanelDecoder.parse(d.visualLines) else emptyList()
        lastEventCount = events.size
        captureCounterView?.text = I18n.f("received", bytes.size, events.size)
        capturePreview?.text = events.takeLast(60).joinToString("\n") { e ->
            (if (e.isSystem) I18n.t("system") else e.address) + "  " + e.problem +
                (listOf(e.label, e.label2).filter { it.isNotEmpty() }.joinToString(" · ").let { if (it.isEmpty()) "" else "  $it" })
        }
    }

    /** Что печатаем с панели: неисправности / тревоги / история. */
    private fun chooseKindThen(then: () -> Unit) {
        val kinds = listOf("faults", "alarms", "history")
        AlertDialog.Builder(this).setTitle(I18n.t("kindQ"))
            .setItems(arrayOf(I18n.t("kindFaults"), I18n.t("kindAlarms"), I18n.t("kindHistory"))) { _, w ->
                captureKind = kinds[w]; then()
            }.show()
    }

    private fun histLimit(): Int = prefs().getInt("histLimit", 50)

    private fun finishCapture() {
        disconnect()
        val sid = siteId ?: return
        val pid = capturePanelId ?: return
        val bytes = captureBytes.toByteArray()
        if (bytes.isEmpty()) { toast(I18n.t("noData")); return }
        if (captureKind != "faults") {
            // тревоги / история — сохраняем отдельно, неисправности не трогаем
            val r = store.addRecord(sid, pid, captureKind, bytes)
            captureBytes.reset(); captureKind = "faults"
            go(Scr.SITE)
            AlertDialog.Builder(this)
                .setMessage(I18n.f("recSaved", RecordExport.title(r.kind, I18n.he), r.events))
                .setPositiveButton(I18n.t("recOpen")) { _, _ -> openRecord(r) }
                .setNegativeButton(I18n.t("close"), null).show()
            return
        }
        val chosen = store.panel(pid) ?: return
        val detected = store.detectPanelName(bytes)
        val other = if (detected.isEmpty()) null
            else store.panels(sid).firstOrNull { it.id != pid && it.detected == detected }
        if (other != null && chosen.detected != detected) {
            AlertDialog.Builder(this)
                .setMessage(I18n.f("panelGuess", detected, other.name, chosen.name))
                .setPositiveButton(I18n.f("saveTo", other.name)) { _, _ -> saveCaptured(sid, other.id, bytes) }
                .setNegativeButton(I18n.f("saveTo", chosen.name)) { _, _ -> saveCaptured(sid, chosen.id, bytes) }
                .setCancelable(false).show()
        } else saveCaptured(sid, pid, bytes)
    }

    private fun captureMsg(before: Panel?, r: CompareResult): String {
        val msg = StringBuilder()
        if (r.first) msg.append(I18n.f("firstLogBody", r.total))
        else msg.append(I18n.f("compareBody", r.fixed, r.added, r.recurred, r.remaining))
        if (r.notConfirmed > 0) msg.append("\n\n").append(I18n.f("notConfirmed", r.notConfirmed))
        if (before != null && before.detected.isNotEmpty() && r.panelName.isNotEmpty() && before.detected != r.panelName)
            msg.append("\n\n").append(I18n.f("otherPanel", r.panelName, before.detected))
        return msg.toString()
    }

    private fun saveCaptured(sid: String, pid: String, bytes: ByteArray, ts: Long = 0L) {
        val before = store.panel(pid)
        val r = store.processLog(sid, pid, bytes, ts)
        siteId = sid; prefs().edit().putString("lastSiteId", sid).apply()
        captureBytes.reset()
        val msg = StringBuilder(I18n.t("saved")).append(" · ").append(before?.name ?: "").append("\n\n").append(captureMsg(before, r))
        tab = 0; searchText = ""; panelSel = pid
        go(Scr.SITE)
        AlertDialog.Builder(this).setTitle(if (r.first) I18n.t("capture") else I18n.t("compareTitle"))
            .setMessage(msg.toString()).setPositiveButton(I18n.t("close"), null).show()
    }

    /** Выбор панели перед снятием лога: выбранная на экране, единственная, или спросить. */
    private fun choosePanelThen(then: () -> Unit) {
        val sid = siteId ?: return
        var panels = store.panels(sid)
        if (panels.isEmpty()) { store.addPanel(sid, "רכזת 1"); panels = store.panels(sid) }
        val sel = panelSel
        if (sel != null && panels.any { it.id == sel }) { capturePanelId = sel; then(); return }
        if (panels.size == 1) { capturePanelId = panels[0].id; then(); return }
        val labels = panels.map { it.name + (if (it.lastLogTs > 0) "  · " + fmt(it.lastLogTs) else "") } + I18n.t("newPanel")
        AlertDialog.Builder(this).setTitle(I18n.t("choosePanel"))
            .setItems(labels.toTypedArray()) { _, w ->
                if (w < panels.size) { capturePanelId = panels[w].id; then() }
                else panelDialog(null) { newId -> capturePanelId = newId; then() }
            }.show()
    }

    private fun panelDialog(edit: Panel?, after: ((String) -> Unit)? = null) {
        val sid = siteId ?: return
        val n = store.panels(sid).size + 1
        val e = input(I18n.t("panelName"), edit?.name ?: "רכזת $n")
        AlertDialog.Builder(this).setTitle(if (edit == null) I18n.t("newPanel") else I18n.t("editPanel"))
            .setView(dialogBox().apply { addView(e) })
            .setPositiveButton(I18n.t("ok")) { _, _ ->
                val name = e.text.toString().trim().ifEmpty { "רכזת $n" }
                if (edit == null) {
                    val id = store.addPanel(sid, name)
                    if (after != null) after(id) else { panelSel = id; render() }
                } else { store.renamePanel(edit.id, name); render() }
            }
            .setNegativeButton(I18n.t("cancel"), null).show()
    }

    private fun panelMenu(p: Panel) {
        AlertDialog.Builder(this).setTitle(p.name + if (p.detected.isNotEmpty()) " · " + p.detected else "")
            .setItems(arrayOf(I18n.t("editPanel"), I18n.t("deletePanel"))) { _, w ->
                if (w == 0) panelDialog(p)
                else AlertDialog.Builder(this).setMessage(I18n.f("deletePanelQ", p.name))
                    .setPositiveButton(I18n.t("delete")) { _, _ ->
                        store.deletePanel(p.id); if (panelSel == p.id) panelSel = null; render()
                    }.setNegativeButton(I18n.t("cancel"), null).show()
            }.show()
    }

    private fun leaveCapture() {
        if (captureBytes.size() > 0) {
            AlertDialog.Builder(this).setMessage(I18n.t("discardQ"))
                .setPositiveButton(I18n.t("yes")) { _, _ -> disconnect(); captureBytes.reset(); go(Scr.SITE) }
                .setNegativeButton(I18n.t("no"), null).show()
        } else { disconnect(); go(Scr.SITE) }
    }

    private fun describe(d: UsbDevice): String {
        val name = try { listOfNotNull(d.manufacturerName, d.productName).joinToString(" ") } catch (_: Exception) { "" }
        return String.format("%04X:%04X", d.vendorId, d.productId) + if (name.isNotBlank()) " ($name)" else ""
    }

    private fun connect() {
        if (port != null) return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            setCapture(if (usbManager.deviceList.isEmpty()) I18n.t("noUsb") else I18n.t("notFound"), true); return
        }
        val driver = drivers[0]
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            pendingConnect = true
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
            usbManager.requestPermission(device, pi)
            setCapture(I18n.t("askPerm"), false); return
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) { setCapture(I18n.t("openFail"), true); return }
        val p = driver.ports[0]
        try {
            p.open(connection)
            p.setParameters(baud(), 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.setDTR(true); p.setRTS(true) } catch (_: Exception) {}
        } catch (e: Exception) {
            setCapture(I18n.t("portErr") + e.message, true)
            try { p.close() } catch (_: Exception) {}
            return
        }
        port = p
        ioManager = SerialInputOutputManager(p, this).also { it.start() }
        setCapture(I18n.f("connected", describe(device), baud()), false)
    }

    private fun disconnect() {
        ioManager?.let { try { it.stop() } catch (_: Exception) {} }
        ioManager = null
        port?.let { try { it.close() } catch (_: Exception) {} }
        port = null
        captureConnectBtn?.text = I18n.t("connect")
    }

    override fun onNewData(data: ByteArray) {
        mainHandler.post {
            if (captureBytes.size() < MAX_CAPTURE_BYTES) captureBytes.write(data)
            // байты — сразу, на каждый пакет; разбор событий — не чаще двух раз в секунду
            captureCounterView?.text = I18n.f("received", captureBytes.size(), lastEventCount)
            if (!renderScheduled) { renderScheduled = true; mainHandler.postDelayed(captureRender, 500) }
        }
    }

    override fun onRunError(e: Exception) {
        mainHandler.post { if (port != null) { disconnect(); setCapture(I18n.t("lost") + e.message, true) } }
    }

    // ================================================================ Логи объекта

    private fun logsView(): View {
        val s = siteId?.let { store.site(it) } ?: return homeView().also { scr = Scr.HOME }
        val (sv, box) = page()
        box.addView(tv(I18n.t("logsTitle") + " · " + s.name, 20f, true))
        box.addView(tv(I18n.t("logsClaudeHint"), 13f, color = Color.DKGRAY).apply { setPadding(0, dp(4), 0, dp(4)) })
        val pName = store.panels(s.id).associate { it.id to it.name }
        val logs = store.logs(s.id).filter { panelSel == null || it.panelId == panelSel }
        if (logs.isEmpty()) box.addView(tv(I18n.t("noLogsYet"), 14f, color = Color.DKGRAY))
        for (l in logs) {
            val c = card()
            c.addView(tv(pName[l.panelId] ?: "", 12f, true, Color.rgb(179, 38, 30)))
            c.addView(tv(I18n.f("logLine", fmt(l.ts), l.events), 15f, true))
            c.setOnClickListener { logMenu(l) }
            box.addView(c)
        }
        return sv
    }

    private fun logMenu(l: LogRec) {
        val items = arrayOf(I18n.t("logFile"), I18n.t("shareTech"), I18n.t("paperView"), I18n.t("reportFrom"), I18n.t("deleteLog"))
        AlertDialog.Builder(this).setTitle(fmt(l.ts)).setItems(items) { _, w0 ->
            if (w0 == 1) { shareTechMenu(listOf(l)); return@setItems }
            val w = if (w0 > 1) w0 - 1 else w0
            when (w) {
                0 -> shareLogFile(l)
                1 -> {
                    val raw = try { File(l.raw).readBytes() } catch (_: Exception) { ByteArray(0) }
                    val d = PanelDecoder.decode(raw)
                    paperText = (if (d.graphic) PanelDecoder.logicalLines(d.visualLines) else d.visualLines).joinToString("\n")
                    go(Scr.PAPER)
                }
                2 -> reportMenu(l.panelId) { _ -> l.ts }
                3 -> AlertDialog.Builder(this).setMessage(I18n.t("deleteLog") + "?")
                    .setPositiveButton(I18n.t("delete")) { _, _ -> store.deleteLog(l); render() }
                    .setNegativeButton(I18n.t("cancel"), null).show()
            }
        }.show()
    }

    private fun paperView(): View {
        val (sv, box) = page()
        box.addView(TextView(this).apply {
            text = paperText; typeface = Typeface.MONOSPACE; textSize = 10f
            setTextColor(Color.rgb(20, 230, 90)); setBackgroundColor(Color.BLACK)
            setPadding(dp(6), dp(6), dp(6), dp(6)); setTextIsSelectable(true)
        })
        return sv
    }

    /** Список снятых тревог и историй объекта: нажатие — поиск, «⋮» — отправить / PDF / удалить. */
    private fun recListView(): View {
        val (sv, box) = page()
        val s = store.site(siteId ?: return sv) ?: return sv
        box.addView(tv(I18n.t("recTitle") + " · " + s.name, 20f, true))
        val pName = store.panels(s.id).associate { it.id to it.name }
        val recs = store.records(s.id)
        if (recs.isEmpty()) box.addView(tv(I18n.t("recEmpty"), 14f, color = Color.DKGRAY).apply { setPadding(0, dp(12), 0, 0) })
        for (r in recs) {
            val c = card().apply { background = rounded(Color.rgb(255, 244, 240), 12, Color.rgb(240, 200, 190)) }
            c.addView(tv(pName[r.panelId] ?: "", 12f, true, Color.rgb(179, 38, 30)))
            c.addView(tv((if (r.kind == "alarms") "🔥 " else "📜 ") + RecordExport.title(r.kind, I18n.he) + " · " + I18n.f("logLine", fmt(r.ts), r.events), 15f, true))
            c.addView(tv(I18n.t("recCardHint"), 12f, color = Color.DKGRAY))
            c.setOnClickListener { openRecord(r) }
            c.setOnLongClickListener { recordMenu(r); true }
            box.addView(c)
        }
        return sv
    }

    /** Открыть тревоги / историю на экране с поиском (ищет по ВСЕМ событиям, не только по последним). */
    private fun openRecord(r: RecordRec) {
        val all = try { PanelDecoder.decode(File(r.raw).readBytes()).let { d -> if (d.graphic) PanelDecoder.parse(d.visualLines) else emptyList() } }
            catch (_: Exception) { emptyList() }
        recRec = r
        recEvents = RecordExport.select(all, 0)   // новые сверху
        recHay = recEvents.map { e ->
            listOf(e.date, e.time, e.event, e.problem, e.address, e.zone, e.zoneLabel, e.label, e.label2, e.devType, e.node,
                PanelDecoder.ru(e.problem) ?: "", PanelDecoder.ru(e.event.trim()) ?: "").joinToString(" ").lowercase()
        }
        recQuery = ""
        go(Scr.RECORD)
    }

    private fun recFiltered(): List<PanelDecoder.PanelEvent> {
        val words = recQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return recEvents
        return recEvents.filterIndexed { i, _ -> words.all { recHay[i].contains(it) } }
    }

    private fun recordView(): View {
        val (sv, box) = page()
        val r = recRec ?: return sv
        val s = store.site(r.siteId)
        val pn = store.panel(r.panelId)?.name ?: ""
        box.addView(tv((if (r.kind == "alarms") "🔥 " else "📜 ") + RecordExport.title(r.kind, I18n.he), 20f, true))
        box.addView(tv((s?.name ?: "") + (if (pn.isNotEmpty()) " · $pn" else "") + " · " + fmt(r.ts), 13f, color = Color.DKGRAY))
        box.addView(input(I18n.t("recSearch"), recQuery).apply {
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { recQuery = e?.toString() ?: ""; fillRecList() }
            })
        })
        recCount = tv("", 13f, color = Color.rgb(60, 66, 75)).apply { setPadding(0, dp(6), 0, dp(2)) }
        box.addView(recCount)
        box.addView(btn(I18n.t("recSendFound")) {
            val found = recFiltered()
            if (found.isEmpty()) toast(I18n.t("recNothing"))
            else shareWhatsapp(RecordExport.text(found.take(300), r.kind, s?.name ?: "", pn, true, recEvents.size))
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(6) } })
        recList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(recList)
        fillRecList()
        return sv
    }

    private fun fillRecList() {
        val list = recList ?: return
        list.removeAllViews()
        val r = recRec ?: return
        val found = recFiltered()
        val searching = recQuery.isNotBlank()
        // без поиска: история — последние N (настройка), тревоги — все; на экране не больше 300 строк
        val lim = if (!searching && r.kind == "history" && histLimit() > 0) histLimit() else 300
        val shown = found.take(minOf(lim, 300))
        recCount?.text = if (searching) I18n.f("recFound", found.size, recEvents.size) +
                (if (found.size > shown.size) " · " + I18n.f("recShown", shown.size) else "")
            else I18n.f("recTotal", recEvents.size) + (if (recEvents.size > shown.size) " · " + I18n.f("recShown", shown.size) else "")
        if (shown.isEmpty()) { list.addView(tv(I18n.t("recNothing"), 14f, color = Color.DKGRAY).apply { setPadding(0, dp(12), 0, 0) }); return }
        for (e in shown) {
            val c = card()
            val ev = e.event.trim()
            c.addView(tv(e.date + "  " + e.time + (if (ev.isNotEmpty()) " · " + I18n.panel(ev) else ""), 13f, true, Color.rgb(179, 38, 30)))
            val head = if (e.address.isNotEmpty()) e.address + (if (e.devType.isNotEmpty()) " · " + I18n.devType(e.devType) else "")
                else I18n.t("system") + (if (e.node.isNotEmpty()) " · " + e.node else "")
            c.addView(tv(head, 15f, true))
            if (e.problem.isNotBlank()) c.addView(tv(I18n.panel(e.problem), 14f, true))
            addPlace(c, if (e.address.isEmpty()) listOf() else listOf(e.label, e.label2), 15f)
            if (e.zone.isNotEmpty() || e.zoneLabel.isNotBlank())
                c.addView(tv(listOf(e.zone, e.zoneLabel.trim()).filter { it.isNotEmpty() }.joinToString(" · "), 12f, color = Color.DKGRAY))
            list.addView(c)
        }
    }

    /** Тревоги / история: просмотр, отправка, PDF, файл для Claude, удаление. */
    private fun recordMenu(r: RecordRec) {
        val s = store.site(r.siteId) ?: return
        val pn = store.panel(r.panelId)?.name ?: ""
        val all = try { PanelDecoder.decode(File(r.raw).readBytes()).let { d -> if (d.graphic) PanelDecoder.parse(d.visualLines) else emptyList() } }
            catch (_: Exception) { emptyList() }
        val ev = RecordExport.select(all, if (r.kind == "history") histLimit() else 0)
        // отчёты и отправки — только на иврите
        val items = listOf("recView", "waHe", "pdfHe", "saveHe", "logFile", "deleteLog")
        AlertDialog.Builder(this).setTitle(RecordExport.title(r.kind, I18n.he) + " · " + fmt(r.ts))
            .setItems(items.map { I18n.t(it) }.toTypedArray()) { _, w ->
                val key = items[w]
                val he = true
                when {
                    key == "recView" -> openRecord(r)
                    key == "logFile" -> shareLogFile(r.asLog())
                    key == "deleteLog" -> AlertDialog.Builder(this).setMessage(I18n.t("deleteLog") + "?")
                        .setPositiveButton(I18n.t("delete")) { _, _ -> store.deleteRecord(r); render() }
                        .setNegativeButton(I18n.t("cancel"), null).show()
                    key.startsWith("wa") -> shareWhatsapp(RecordExport.text(ev, r.kind, s.name, pn, he, all.size))
                    else -> try {
                        val safe = s.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(40)
                        val out = File(store.dataDir(), "G1_${safe}_${r.kind}_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(r.ts)) +
                            (if (he) "_he" else "_he_ru") + ".pdf")
                        RecordExport.pdf(ev, r.kind, s.name, pn, he, all.size, out)
                        if (key.startsWith("pdf")) shareFile(out, "application/pdf") else saveToDownloads(out, out.name, "application/pdf")
                    } catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
                }
            }.show()
    }

    private fun shareLogFile(l: LogRec) {
        try {
            val s = store.site(l.siteId)
            val raw = File(l.raw).readBytes()
            val d = PanelDecoder.decode(raw)
            val sb = StringBuilder()
            sb.append("=== G1 Hotelo: лог принтерного порта NFS2-3030 ===\n")
            sb.append("Объект: ").append(s?.name ?: "").append('\n')
            sb.append("Время лога: ").append(fmt(l.ts)).append('\n')
            sb.append("Байт: ").append(raw.size).append('\n')
            sb.append("Версия приложения: ").append(appVersion()).append("\n\n")
            if (d.graphic) {
                val ev = PanelDecoder.parse(d.visualLines)
                sb.append("=== СОБЫТИЯ (").append(ev.size).append(") ===\n")
                for (e in ev) sb.append(listOf(e.address, e.zone, e.devType, e.problem, e.label, e.label2, e.date, e.time, e.node).joinToString("\t")).append('\n')
                sb.append("\n=== КАК НА БУМАГЕ ===\n").append(PanelDecoder.logicalLines(d.visualLines).joinToString("\n")).append("\n\n")
                if (d.unknownGlyphs.isNotEmpty()) sb.append("=== НЕЗНАКОМЫЕ СИМВОЛЫ ===\n").append(d.unknownGlyphs.joinToString("\n")).append("\n\n")
            }
            sb.append("=== HEX (сырые байты) ===\n").append(hexDump(raw))
            val out = File(store.dataDir(), "log_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(l.ts)) + ".txt")
            out.writeText(sb.toString())
            shareFile(out, "text/plain")
        } catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
    }

    private fun hexDump(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 4 + 64)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + 16, bytes.size)
            sb.append(String.format("%08X  ", i))
            for (j in i until i + 16) sb.append(if (j < end) String.format("%02X ", bytes[j].toInt() and 0xFF) else "   ")
            sb.append(" |")
            for (j in i until end) { val c = bytes[j].toInt() and 0xFF; sb.append(if (c in 0x20..0x7E) c.toChar() else '.') }
            sb.append("|\n")
            i = end
        }
        return sb.toString()
    }

    private fun appVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    // ================================================================ Отчёты

    // ================================================================ Обмен логами между техниками (.g1h)

    private fun techName(): String = prefs().getString("techName", "") ?: ""

    /** Файл для техника: только лог или лог + мои заметки (для заметок нужно имя). */
    private fun shareTechMenu(list: List<LogRec>) {
        AlertDialog.Builder(this).setTitle(I18n.t("shareTech"))
            .setItems(arrayOf(I18n.t("shareLogOnly"), I18n.t("shareWithNotes"))) { _, w ->
                if (w == 0) { shareG1h(list, false); return@setItems }
                if (techName().isNotBlank()) { shareG1h(list, true); return@setItems }
                val e = input(I18n.t("techName"))
                AlertDialog.Builder(this).setTitle(I18n.t("techName")).setView(dialogBox().apply { addView(e) })
                    .setPositiveButton(I18n.t("ok")) { _, _ ->
                        prefs().edit().putString("techName", e.text.toString().trim()).apply()
                        shareG1h(list, true)
                    }.setNegativeButton(I18n.t("cancel"), null).show()
            }.show()
    }

    /** Лог (+ при желании заметки по ошибкам этой панели) → файл .g1h для другого техника. */
    private fun shareG1h(list: List<LogRec>, withNotes: Boolean) {
        try {
            // один файл на объект: все панели внутри (רכזת 1, רכזת 2…)
            val files = ArrayList<File>()
            for ((sid, logs) in list.groupBy { it.siteId }) {
                val s = store.site(sid) ?: continue
                val j = org.json.JSONObject()
                j.put("g1h", 2); j.put("app", appVersion())
                j.put("site", s.name); j.put("address", s.address)
                if (withNotes) j.put("who", techName().ifBlank { I18n.t("techDefault") })
                val arr = org.json.JSONArray()
                for (l in logs.sortedBy { store.panel(it.panelId)?.name ?: "" }) {
                    val p = store.panel(l.panelId)
                    val raw = File(l.raw).readBytes()
                    val o = org.json.JSONObject()
                    o.put("panel", p?.name ?: ""); o.put("detected", p?.detected ?: "")
                    o.put("ts", l.ts); o.put("events", l.events)
                    o.put("raw", android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP))
                    if (withNotes) o.put("notes", store.notesJson(l.panelId))
                    arr.put(o)
                }
                j.put("logs", arr)
                if (withNotes) j.put("siteNotes", store.notesJson("", sid))   // записи техника без רכזת
                val safe = { x: String -> x.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(30) }
                val dir = File(store.dataDir(), "share").also { it.mkdirs() }
                val one = if (logs.size == 1) "_" + safe(store.panel(logs[0].panelId)?.name ?: "") else ""
                val f = File(dir, "G1_" + safe(s.name) + one + "_" +
                    SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(logs.maxOf { it.ts })) + ".g1h")
                f.writeText(j.toString())
                files.add(f)
            }
            if (files.isEmpty()) { toast(I18n.t("noLogsForReport")); return }
            val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
            val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            send.type = "application/octet-stream"
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, I18n.t("sendWith")))
        } catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
    }

    private data class G1h(val site: String, val address: String, val panel: String, val detected: String,
                           val ts: Long, val events: Int, val raw: ByteArray,
                           val who: String, val notes: org.json.JSONArray?, val siteNotes: org.json.JSONArray? = null)

    /** Файл .g1h открыт из WhatsApp/почты/проводника или прислан через «Поделиться». */
    private fun handleFileIntent(i: Intent) {
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_VIEW -> i.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.addAll(it) }
            else -> return
        }
        if (uris.isEmpty()) return
        setIntent(Intent(this, MainActivity::class.java)) // не импортировать повторно при пересоздании
        val packs = ArrayList<List<G1h>>()
        for (u in uris) {
            try {
                val text = contentResolver.openInputStream(u)?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: continue
                val j = org.json.JSONObject(text)
                if (!j.has("g1h")) continue
                val site = j.optString("site"); val addr = j.optString("address")
                val who = j.optString("who").ifBlank { I18n.t("techDefault") }
                fun item(o: org.json.JSONObject) = G1h(site, addr, o.optString("panel"), o.optString("detected"),
                    o.optLong("ts"), o.optInt("events"), android.util.Base64.decode(o.getString("raw"), android.util.Base64.DEFAULT),
                    who, o.optJSONArray("notes"))
                val arr = j.optJSONArray("logs")
                val pack = if (arr != null) (0 until arr.length()).mapNotNull { k -> arr.optJSONObject(k)?.let { item(it) } }
                    else if (j.has("raw")) listOf(item(j)) else emptyList()   // формат v1: один лог
                val sn = j.optJSONArray("siteNotes")
                if (pack.isNotEmpty()) packs.add(if (sn != null && sn.length() > 0) listOf(pack[0].copy(siteNotes = sn)) + pack.drop(1) else pack)
            } catch (_: Exception) { }
        }
        if (packs.isEmpty()) { toast(I18n.t("notG1")); return }
        importPack(packs, 0)
    }

    /** Один файл = один объект: одно подтверждение на все панели внутри. */
    private fun importPack(packs: List<List<G1h>>, k: Int) {
        if (k >= packs.size) return
        val pack = packs[k]
        val next: () -> Unit = { mainHandler.post { importPack(packs, k + 1) } }
        val g0 = pack[0]
        val lines = pack.joinToString("\n") { I18n.f("importPanelLine", it.panel, fmt(it.ts), it.events) }
        val notesN = pack.sumOf { it.notes?.length() ?: 0 }
        AlertDialog.Builder(this)
            .setMessage(I18n.f("importQ2", g0.site, lines) +
                (if (notesN > 0) "\n\n" + I18n.f("importNotes", g0.who, notesN) else ""))
            .setPositiveButton(I18n.t("importAdd")) { _, _ -> chooseImportSite(pack, next) }
            .setNegativeButton(I18n.t("cancel")) { _, _ -> next() }
            .setCancelable(false).show()
    }

    private fun chooseImportSite(pack: List<G1h>, next: () -> Unit) {
        val g = pack[0]
        val sites = store.sites()
        val same = sites.firstOrNull { it.name.trim().equals(g.site.trim(), ignoreCase = true) }
        val go = { sid: String -> importItems(sid, pack, 0, StringBuilder(), ArrayList(), next) }
        if (same != null) { go(same.id); return }
        val labels = listOf(I18n.f("newSiteNamed", g.site)) + sites.map { it.name }
        AlertDialog.Builder(this).setTitle(I18n.t("importSite"))
            .setItems(labels.toTypedArray()) { _, w ->
                go(if (w == 0) store.addSite(g.site.ifBlank { "G1" }, g.address, "") else sites[w - 1].id)
            }
            .setOnCancelListener { next() }.show()
    }

    /** Панели файла по очереди; в конце — одно общее окно с итогом. */
    private fun importItems(sid: String, pack: List<G1h>, k: Int, sum: StringBuilder, pids: ArrayList<String>, next: () -> Unit) {
        if (k >= pack.size) {
            pack[0].siteNotes?.let { sn ->
                val n = store.mergeNotes("", sn, pack[0].who, sid)
                if (n > 0) sum.append(I18n.t("manualEntry")).append(": ").append(I18n.f("notesMerged", n)).append('\n')
            }
            siteId = sid; prefs().edit().putString("lastSiteId", sid).apply()
            panelSel = if (pids.distinct().size == 1) pids[0] else null
            tab = 0; searchText = ""
            go(Scr.SITE)
            AlertDialog.Builder(this).setTitle(I18n.t("importDone") + " · " + (store.site(sid)?.name ?: ""))
                .setMessage(sum.toString().trim())
                .setPositiveButton(I18n.t("close")) { _, _ -> next() }.setCancelable(false).show()
            return
        }
        val g = pack[k]
        val cont = { mainHandler.post { importItems(sid, pack, k + 1, sum, pids, next) }; Unit }
        val panels = store.panels(sid)
        val p = panels.firstOrNull { g.detected.isNotEmpty() && it.detected == g.detected }
            ?: panels.firstOrNull { it.name.trim() == g.panel.trim() }
            ?: (if (panels.size == 1 && panels[0].lastLogTs == 0L) panels[0] else null)
        val pid = p?.id ?: store.addPanel(sid, g.panel.ifBlank { "רכזת " + (panels.size + 1) })
        val pname = store.panel(pid)?.name ?: g.panel
        pids.add(pid)
        sum.append("■ ").append(pname).append('\n')
        fun notes() {
            if (g.notes == null) return
            val n = store.mergeNotes(pid, g.notes, g.who)
            sum.append(if (n > 0) I18n.f("notesMerged", n) else I18n.t("notesNone")).append('\n')
        }
        val existing = store.panelLogs(pid)
        if (existing.any { l -> try { File(l.raw).readBytes().contentEquals(g.raw) } catch (_: Exception) { false } }) {
            sum.append(I18n.t("dupLog")).append('\n'); notes(); sum.append('\n'); cont(); return
        }
        val doIt = {
            val before = store.panel(pid)
            val r = store.processLog(sid, pid, g.raw, g.ts)
            sum.append(captureMsg(before, r)).append('\n'); notes(); sum.append('\n'); cont()
        }
        val newest = existing.maxOfOrNull { it.ts } ?: 0L
        if (newest > g.ts) {
            AlertDialog.Builder(this).setTitle(pname).setMessage(I18n.f("olderLog", fmt(newest)))
                .setPositiveButton(I18n.t("importAdd")) { _, _ -> doIt() }
                .setNegativeButton(I18n.t("cancel")) { _, _ -> sum.append(I18n.t("skipped")).append("\n\n"); cont() }
                .setCancelable(false).show()
        } else doIt()
    }

    /** «Отправить как есть»: последний лог каждой выбранной панели — ровно то, что показала панель, без комментариев. */
    private fun asIsMenu() {
        val sid = siteId ?: return
        val s = store.site(sid) ?: return
        val panels = store.panels(sid)   // всегда весь объект: все רכזות в одном файле
        val logs = store.logs(sid)
        val lastLogs = ArrayList<LogRec>()
        val parts = panels.mapNotNull { p ->
            val l = logs.filter { it.panelId == p.id }.maxByOrNull { it.ts } ?: return@mapNotNull null
            val raw = try { File(l.raw).readBytes() } catch (_: Exception) { return@mapNotNull null }
            lastLogs.add(l)
            val d = PanelDecoder.decode(raw)
            if (!d.graphic) null else p to PanelDecoder.parse(d.visualLines)
        }
        if (parts.isEmpty()) { toast(I18n.t("noLogsForReport")); return }
        val title = { p: Panel -> s.name + (if (store.panels(sid).size > 1) " · " + p.name else "") }
        val opts = listOf("waHe" to true, "pdfHe" to true, "saveHe" to true)   // только иврит
        AlertDialog.Builder(this).setTitle(I18n.t("sendAsIs"))
            .setItems((opts.map { I18n.t(it.first) } + I18n.t("shareTech")).toTypedArray()) { _, w ->
                if (w >= opts.size) { shareTechMenu(lastLogs); return@setItems }
                val (key, he) = opts[w]
                if (key.startsWith("wa")) {
                    shareWhatsapp(parts.joinToString("\n\n— — —\n\n") { (p, ev) -> ReportExport.whatsappText(ev, title(p), he) })
                    return@setItems
                }
                try {
                    val events = parts.flatMap { it.second }
                    val name = if (parts.size == 1) title(parts[0].first) else s.name + " · " + parts.joinToString(", ") { it.first.name }
                    val safe = s.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(40)
                    val out = File(store.dataDir(), "G1_${safe}_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) +
                        (if (he) "_he" else "_he_ru") + "_panel.pdf")
                    val heads = HashMap<Int, String>()
                    if (parts.size > 1) { var idx = 0; for ((p, ev) in parts) { heads[idx] = p.name; idx += ev.size } }
                    ReportExport.writePdf(events, out, if (parts.size == 1) name else s.name, he, heads)
                    if (key.startsWith("pdf")) shareFile(out, "application/pdf") else saveToDownloads(out, out.name, "application/pdf")
                } catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
            }.show()
    }

    /** «Отчёт»: от самого раннего лога до самого последнего. */
    private fun sendMenu() {
        val sid = siteId ?: return
        if (store.logs(sid).isEmpty() && store.faults(sid).none { it.isManual }) { toast(I18n.t("noLogsForReport")); return }
        // по умолчанию: самый ранний лог → самый последний (вся работа на объекте)
        val go = { reportMenu(null, true) { _ -> 0L } }   // весь объект: все רכזות в одном отчёте
        // Главное в отчёте — причины по оставшимся неисправностям: напомнить, если не заполнено
        val noComment = store.faults(sid).count {
            it.active && !it.repaired && !it.parked && !it.hidden && it.comment.isBlank() && it.action.isEmpty()
        }
        if (noComment == 0) { go(); return }
        AlertDialog.Builder(this).setMessage(I18n.f("noCommentQ", noComment))
            .setPositiveButton(I18n.t("sendAnyway")) { _, _ -> go() }
            .setNegativeButton(I18n.t("fillComments")) { _, _ -> tab = 0; searchText = ""; render() }
            .show()
    }

    private fun periodMenu() {
        val sid = siteId ?: return
        if (store.logs(sid).isEmpty() && store.faults(sid).none { it.isManual }) { toast(I18n.t("noLogsForReport")); return }
        val panels = store.panels(sid)
        if (panels.size <= 1) { periodChoice(null); return }
        val labels = listOf(I18n.t("allPanels")) + panels.map { it.name }
        AlertDialog.Builder(this).setTitle(I18n.t("reportScope"))
            .setItems(labels.toTypedArray()) { _, w -> periodChoice(if (w == 0) null else panels[w - 1].id) }.show()
    }

    private fun periodChoice(panelId: String?) {
        AlertDialog.Builder(this).setTitle(I18n.t("periodTitle"))
            .setItems(arrayOf(I18n.t("sinceLast"), I18n.t("sinceDate"), I18n.t("allTime"))) { _, w ->
                when (w) {
                    // у каждой панели — свой прошлый лог
                    0 -> reportMenu(panelId) { logs -> if (logs.size >= 2) logs[logs.size - 2].ts else logs.last().ts }
                    1 -> {
                        val cal = Calendar.getInstance()
                        DatePickerDialog(this, { _, y, m, dd ->
                            val c = Calendar.getInstance().apply { set(y, m, dd, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
                            val from = c.timeInMillis
                            reportMenu(panelId) { _ -> from }
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    }
                    else -> reportMenu(panelId) { _ -> 0L }
                }
            }.show()
    }

    private fun reportMenu(panelId: String?, withOther: Boolean = false, fromFor: (List<LogRec>) -> Long) {
        val sid = siteId ?: return
        if (!I18n.he) AutoTr.request(store.faults(sid).flatMap { listOf(it.label, it.label2, it.node, it.problem) })
        val r = store.period(sid, panelId, fromFor) ?: run { toast(I18n.t("noLogsForReport")); return }
        // Иврит в настройках — только иврит; русский — выбор: иврит или иврит + перевод
        val opts = listOf("waHe" to true, "pdfHe" to true, "pdfPhHe" to true, "xlsHe" to true, "saveHe" to true, "xlsSave" to true)   // только иврит
        // + «Проделанная работа» — для себя (устранённое), начальнику не отправляется
        val workItems = listOf(I18n.t("workSave"), I18n.t("workShare"))
        val labels = opts.map { I18n.t(it.first) } + workItems + (if (withOther) listOf(I18n.t("otherPeriod")) else emptyList())
        AlertDialog.Builder(this).setTitle(r.site.name + " · " + SimpleDateFormat("dd.MM", Locale.US).format(Date(r.from)) +
                (if (fmtD(r.from) != fmtD(r.to)) "–" + fmtD(r.to) else ""))
            .setItems(labels.toTypedArray()) { _, w ->
                if (w == opts.size || w == opts.size + 1) {
                    val heW = true
                    try {
                        val out = File(store.dataDir(), reportName(r, heW).replace(".pdf", "_work.pdf"))
                        PeriodExport.pdf(r, store, heW, out, work = true, photos = true)
                        if (w == opts.size) saveToDownloads(out, out.name, "application/pdf") else shareFile(out, "application/pdf")
                    } catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
                    return@setItems
                }
                if (w > opts.size + 1) { periodMenu(); return@setItems }
                val (key, heOnly) = opts[w]
                when {
                    key.startsWith("wa") -> shareWhatsapp(PeriodExport.whatsapp(r, store, heOnly))
                    key.startsWith("xls") -> try {
                        val mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        val out = File(store.dataDir(), reportName(r, true).replace("_he.pdf", ".xlsx"))
                        PeriodExport.xlsx(r, store, out)
                        if (key == "xlsHe") shareFile(out, mime) else saveToDownloads(out, out.name, mime)
                    } catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
                    key.startsWith("pdfPh") -> makeReportPdf(r, heOnly, photos = true)?.let { shareFile(it, "application/pdf") }
                    key.startsWith("pdf") -> makeReportPdf(r, heOnly)?.let { shareFile(it, "application/pdf") }
                    else -> makeReportPdf(r, heOnly)?.let { saveToDownloads(it, it.name, "application/pdf") }
                }
            }.show()
    }

    private fun reportName(r: PeriodReport, heOnly: Boolean): String {
        val site = r.site.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(40)
        val d = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(r.to))
        return "G1_${site}_$d" + (if (heOnly) "_he" else "_he_ru") + ".pdf"
    }

    private fun makeReportPdf(r: PeriodReport, heOnly: Boolean, photos: Boolean = false): File? = try {
        val out = File(store.dataDir(), reportName(r, heOnly).let { if (photos) it.replace(".pdf", "_photos.pdf") else it })
        PeriodExport.pdf(r, store, heOnly, out, photos = photos); out
    } catch (e: Exception) { toast(I18n.t("fileErr") + e.message); null }

    private fun shareWhatsapp(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        val wa = Intent(send).setPackage("com.whatsapp")
        try {
            if (wa.resolveActivity(packageManager) != null) startActivity(wa)
            else startActivity(Intent.createChooser(send, I18n.t("sendWith")))
        } catch (_: Exception) { startActivity(Intent.createChooser(send, I18n.t("sendWith"))) }
    }

    private fun shareFile(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, I18n.t("sendWith")))
    }

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
                toast(I18n.t("savedTo") + name)
            } catch (e: Exception) { toast(I18n.t("fileErr") + e.message) }
        } else {
            toast(I18n.t("savedTo") + src.absolutePath)
        }
    }

    // ================================================================ Настройки

    private fun settingsDialog() {
        val box = dialogBox()
        fun label(s: String) = tv(s, 13f, true).apply { setPadding(0, dp(10), 0, dp(2)) }
        box.addView(label(I18n.t("baud")))
        val baudSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, BAUDS.map { it.toString() })
            setSelection(BAUDS.indexOf(baud()).coerceAtLeast(0))
        }
        box.addView(baudSp)
        box.addView(label(I18n.t("lang")))
        val lg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val ru = RadioButton(this).apply { id = View.generateViewId(); text = "Русский" }
        val he = RadioButton(this).apply { id = View.generateViewId(); text = "עברית" }
        lg.addView(ru); lg.addView(he)
        lg.check(if (I18n.he) he.id else ru.id)
        box.addView(lg)
        box.addView(label(I18n.t("techName")))
        val nameE = input(I18n.t("techName"), techName()).apply { setSingleLine() }
        box.addView(nameE)
        box.addView(label(I18n.t("histLimit")))
        val histE = input("50", histLimit().toString()).apply { setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        box.addView(histE)
        AlertDialog.Builder(this).setTitle(I18n.t("settings")).setView(box)
            .setPositiveButton(I18n.t("ok")) { _, _ ->
                prefs().edit().putString("techName", nameE.text.toString().trim())
                    .putInt("histLimit", histE.text.toString().toIntOrNull()?.coerceIn(0, 100000) ?: 50).apply()
                val heSel = lg.checkedRadioButtonId == he.id
                prefs().edit().putInt("baud", BAUDS[baudSp.selectedItemPosition.coerceIn(0, BAUDS.size - 1)])
                    .putString("lang", if (heSel) "he" else "ru").apply()
                I18n.he = heSel
                render()
            }
            .setNegativeButton(I18n.t("cancel"), null).show()
    }

    private fun actionsEditor() {
        val acts = store.actions().toMutableList()
        val labels = acts.map { it.he + if (it.ru.isNotEmpty()) " / " + it.ru else "" } + I18n.t("addAction")
        AlertDialog.Builder(this).setTitle(I18n.t("editActions"))
            .setItems(labels.toTypedArray()) { _, w -> editAction(if (w < acts.size) acts[w] else null) }
            .setNegativeButton(I18n.t("close"), null).show()
    }

    private fun editAction(a: ActionDef?) {
        val box = dialogBox()
        val heIn = input(I18n.t("actionHe"), a?.he ?: "")
        val ruIn = input(I18n.t("actionRu"), a?.ru ?: "")
        val bill = CheckBox(this).apply { text = I18n.t("actionBill"); isChecked = a?.billable ?: false }
        box.addView(heIn); box.addView(ruIn)   // «₪ к оплате» убрано — достаточно комментариев
        val b = AlertDialog.Builder(this).setTitle(if (a == null) I18n.t("addAction") else a.he).setView(box)
            .setPositiveButton(I18n.t("ok")) { _, _ ->
                val h = heIn.text.toString().trim()
                if (h.isEmpty()) return@setPositiveButton
                val list = store.actions().toMutableList()
                val n = ActionDef(a?.id ?: store.newActionId(), h, ruIn.text.toString().trim(), bill.isChecked)
                val idx = list.indexOfFirst { it.id == n.id }
                if (idx >= 0) list[idx] = n else list.add(n)
                store.saveActions(list)
                actionsEditor()
            }
            .setNegativeButton(I18n.t("cancel")) { _, _ -> actionsEditor() }
        if (a != null) b.setNeutralButton(I18n.t("delete")) { _, _ ->
            store.saveActions(store.actions().filter { it.id != a.id }); actionsEditor()
        }
        b.show()
    }
}
