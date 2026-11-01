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
        private val BAUDS = intArrayOf(300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)
    }

    private enum class Scr { HOME, SITE, CAPTURE, FAULT, LOGS, PAPER }

    private lateinit var store: Store
    private lateinit var usbManager: UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scr = Scr.HOME
    private var siteId: String? = null
    private var faultId: String? = null
    private var tab = 0
    private var searchText = ""
    private var paperText = ""

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
    private val captureRender = Runnable { updateCaptureViews() }

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && scr == Scr.CAPTURE && port == null) connect()
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
            Scr.SITE -> go(Scr.HOME)
            Scr.CAPTURE -> leaveCapture()
            Scr.FAULT -> { saveCommentIfAny(); go(Scr.SITE) }
            Scr.LOGS -> go(Scr.SITE)
            Scr.PAPER -> go(Scr.LOGS)
        }
    }

    private fun prefs() = getSharedPreferences("panellog", Context.MODE_PRIVATE)
    private fun baud(): Int = prefs().getInt("baud", 9600)

    private fun go(s: Scr) { scr = s; render() }

    private fun render() {
        title = "G1 Hotelo"
        faultListBox = null
        captureStatusView = null; captureCounterView = null; capturePreview = null; captureConnectBtn = null
        val v = when (scr) {
            Scr.HOME -> homeView()
            Scr.SITE -> siteView()
            Scr.CAPTURE -> captureView()
            Scr.FAULT -> faultView()
            Scr.LOGS -> logsView()
            Scr.PAPER -> paperView()
        }
        setContentView(v)
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
            c.setOnClickListener { siteId = s.id; tab = 0; searchText = ""; go(Scr.SITE) }
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
                    tab = 0; searchText = ""; go(Scr.SITE)
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

        box.addView(row(
            btn(I18n.t("takeLog"), true) { startCapture() },
            btn(I18n.t("report")) { periodMenu() },
            btn(I18n.t("logs")) { go(Scr.LOGS) }
        ).apply { setPadding(0, dp(8), 0, 0) })

        val logs = store.logs(s.id)
        val faults = store.faults(s.id)
        if (logs.isEmpty()) {
            box.addView(tv(I18n.t("noLogsYet"), 14f, color = Color.DKGRAY).apply { setPadding(0, dp(14), 0, 0) })
            return sv
        }
        // сводка
        val active = faults.count { it.active }
        var fixedN = 0; var newN = 0
        if (logs.size >= 2) {
            val b = store.logFaultIds(logs[0].id); val a = store.logFaultIds(logs[1].id)
            fixedN = a.count { it !in b }; newN = b.count { it !in a }
        }
        box.addView(tv(I18n.f("summary", fmt(logs[0].ts), active, fixedN, newN), 13f, color = Color.rgb(60, 66, 75))
            .apply { setPadding(0, dp(10), 0, dp(2)) })
        // подсказки по шлейфам
        faults.filter { it.active && !it.isSystem && it.loop.isNotEmpty() }
            .groupBy { it.loop to it.problem }
            .filter { it.value.size >= 5 }
            .forEach { (k, v) ->
                box.addView(tv("💡 " + I18n.f("loopHint", k.first.trimStart('0'), v.size, I18n.panel(k.second)), 13f,
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
        val logs = store.logs(sid)
        val lastTs = logs.firstOrNull()?.ts ?: 0
        val q = searchText.trim().lowercase()
        val faults = store.faults(sid).filter {
            when (tab) { 0 -> it.active; 1 -> !it.active; else -> true }
        }.filter {
            q.isEmpty() || listOf(it.address, it.zone, it.label, it.label2, it.problem, it.node, it.devType,
                PanelDecoder.ru(it.problem) ?: "", store.actionText(it.action), it.comment)
                .any { s -> s.lowercase().contains(q) }
        }.let { fs -> if (tab == 1) fs.sortedByDescending { it.resolvedTs } else fs }
        if (faults.isEmpty()) {
            list.addView(tv(I18n.t("noFaults"), 14f, color = Color.DKGRAY).apply { setPadding(0, dp(12), 0, 0) })
            return
        }
        for (f in faults) list.addView(faultRow(f, lastTs, logs.size))
    }

    private fun faultRow(f: Fault, lastTs: Long, logCount: Int): View {
        val c = card()
        val head = (if (f.isSystem) I18n.t("system") + (if (f.node.isNotEmpty()) " · " + f.node else "")
            else f.address + (if (f.devType.isNotEmpty()) " · " + I18n.devType(f.devType) else ""))
        c.addView(tv(head, 15f, true))
        c.addView(tv(I18n.panel(f.problem), 14f))
        val place = listOf(f.label, f.label2).filter { it.isNotEmpty() }.joinToString(" · ")
        if (place.isNotEmpty()) c.addView(tv(place, 14f, color = Color.rgb(60, 66, 75)))
        val badges = statusBadges(f, lastTs, logCount)
        if (badges.isNotEmpty()) c.addView(tv(badges, 13f, color = Color.rgb(30, 63, 138)).apply { setPadding(0, dp(4), 0, 0) })
        warning(f)?.let { c.addView(tv(it, 13f, color = Color.rgb(160, 30, 30))) }
        c.setOnClickListener { faultId = f.id; go(Scr.FAULT) }
        return c
    }

    private fun statusBadges(f: Fault, lastTs: Long, logCount: Int): String {
        val b = ArrayList<String>()
        if (!f.active) b.add(I18n.f("stFixed", fmtD(f.resolvedTs)))
        else if (logCount > 1 && f.firstSeen == lastTs) b.add(I18n.t("stNew"))
        if (f.recur > 0) b.add(I18n.f("stRecur", f.recur))
        if (f.billable) b.add(I18n.t("stBill"))
        if (f.action.isNotEmpty()) b.add("✔ " + store.actionText(f.action))
        return b.joinToString("  ")
    }

    private fun warning(f: Fault): String? {
        val doneActions = setOf("fixed", "replaced", "cleaned", "base", "wiring")
        if (f.active && (f.action in doneActions || f.action.startsWith("custom:")))
            return I18n.f("warnStill", store.actionText(f.action))
        if (!f.active && f.action.isEmpty()) return I18n.t("warnNoAction")
        return null
    }

    // ================================================================ Карточка неисправности

    private var commentEdit: EditText? = null

    private fun faultView(): View {
        val f = faultId?.let { store.fault(it) } ?: return siteView().also { scr = Scr.SITE }
        val (sv, box) = page()
        val logs = store.logs(f.siteId)
        val head = if (f.isSystem) I18n.t("system") else f.address
        box.addView(tv(head, 22f, true))
        box.addView(tv(I18n.panel(f.problem), 17f, true).apply { setPadding(0, dp(4), 0, 0) })
        val info = card()
        if (!f.isSystem) {
            info.addView(tv(I18n.t("type") + ": " + I18n.devType(f.devType), 14f))
            if (f.zone.isNotEmpty()) info.addView(tv(I18n.t("zone") + ": " + f.zone, 14f))
        }
        val place = if (f.isSystem) f.node else listOf(f.label, f.label2).filter { it.isNotEmpty() }.joinToString(" · ")
        if (place.isNotEmpty()) info.addView(tv(I18n.t("place") + ": " + place, 14f))
        info.addView(tv(I18n.t("firstSeen") + ": " + fmt(f.firstSeen) + "   " + I18n.t("lastSeen") + ": " + fmt(f.lastSeen), 13f, color = Color.DKGRAY))
        val badges = statusBadges(f, logs.firstOrNull()?.ts ?: 0, logs.size)
        info.addView(tv(if (f.active) I18n.t("stActive") + (if (badges.isNotEmpty()) "  $badges" else "") else badges, 13f, color = Color.rgb(30, 63, 138)))
        warning(f)?.let { info.addView(tv(it, 13f, color = Color.rgb(160, 30, 30))) }
        box.addView(info)

        // Что сделано
        box.addView(tv(I18n.t("actions"), 15f, true).apply { setPadding(0, dp(12), 0, dp(2)) })
        val acts = store.actions()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var line: LinearLayout? = null
        acts.forEachIndexed { i, a ->
            if (i % 2 == 0) { line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; grid.addView(line) }
            val sel = f.action == a.id
            val b = Button(this).apply {
                text = (if (a.billable) "₪ " else "") + a.text(); isAllCaps = false; textSize = 13f
                background = rounded(if (sel) Color.rgb(21, 24, 28) else Color.WHITE, 10, Color.rgb(195, 201, 209))
                setTextColor(if (sel) Color.WHITE else Color.rgb(21, 24, 28))
                setOnClickListener {
                    saveCommentIfAny()
                    store.setAction(f.id, a.id, if (a.billable) true else null); render()
                }
            }
            line!!.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        if (acts.size % 2 == 1) line?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        box.addView(grid)
        val customSel = f.action.startsWith("custom:")
        box.addView(row(
            btn((if (customSel) "✔ " + f.action.removePrefix("custom:") else I18n.t("other"))) {
                val e = input(I18n.t("enterText"), if (customSel) f.action.removePrefix("custom:") else "")
                AlertDialog.Builder(this).setTitle(I18n.t("other")).setView(dialogBox().apply { addView(e) })
                    .setPositiveButton(I18n.t("ok")) { _, _ ->
                        val t = e.text.toString().trim()
                        if (t.isNotEmpty()) { saveCommentIfAny(); store.setAction(f.id, "custom:$t", null); render() }
                    }.setNegativeButton(I18n.t("cancel"), null).show()
            },
            btn(I18n.t("clearAction")) { saveCommentIfAny(); store.setAction(f.id, "", null); render() }
        ))

        // К оплате
        box.addView(CheckBox(this).apply {
            text = "₪ " + I18n.t("billable"); textSize = 15f; isChecked = f.billable
            setOnCheckedChangeListener { _, ch -> store.setBillable(f.id, ch) }
        })

        // Комментарий
        box.addView(tv(I18n.t("comment"), 15f, true).apply { setPadding(0, dp(8), 0, 0) })
        val ce = input(I18n.t("comment"), f.comment, true).apply { tag = f.comment }
        commentEdit = ce
        box.addView(ce)

        // Фото
        box.addView(tv(I18n.t("photos"), 15f, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(btn(I18n.t("addPhoto")) { takePhoto(f.id) }, LinearLayout.LayoutParams(dp(96), dp(96)))
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

        // История
        box.addView(tv(I18n.t("history"), 15f, true).apply { setPadding(0, dp(12), 0, dp(2)) })
        for (h in store.history(f.id)) {
            val what = when (h.kind) {
                "appeared" -> I18n.t("hAppeared")
                "resolved" -> I18n.t("hResolved")
                "recurred" -> I18n.t("hRecurred")
                "action" -> I18n.t("hAction") + ": " + (if (h.text.isEmpty()) "—" else store.actionText(h.text))
                "comment" -> I18n.t("hComment") + ": " + h.text
                "photo" -> I18n.t("hPhoto")
                else -> h.kind
            }
            box.addView(tv(fmt(h.ts) + " — " + what, 13f, color = Color.rgb(60, 66, 75)))
        }
        return sv
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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
        captureStatus = ""
        go(Scr.CAPTURE)
        connect()
    }

    private fun captureView(): View {
        val s = siteId?.let { store.site(it) }
        val (sv, box) = page()
        box.addView(tv(I18n.t("capture") + (if (s != null) " · " + s.name else ""), 20f, true))
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
        captureCounterView?.text = I18n.f("received", bytes.size, events.size)
        capturePreview?.text = events.takeLast(60).joinToString("\n") { e ->
            (if (e.isSystem) I18n.t("system") else e.address) + "  " + e.problem +
                (listOf(e.label, e.label2).filter { it.isNotEmpty() }.joinToString(" · ").let { if (it.isEmpty()) "" else "  $it" })
        }
    }

    private fun finishCapture() {
        disconnect()
        val sid = siteId ?: return
        val bytes = captureBytes.toByteArray()
        if (bytes.isEmpty()) { toast(I18n.t("noData")); return }
        val site = store.site(sid)
        val r = store.processLog(sid, bytes)
        captureBytes.reset()
        val msg = StringBuilder(I18n.t("saved")).append("\n\n")
        if (r.first) msg.append(I18n.f("firstLogBody", r.total))
        else msg.append(I18n.f("compareBody", r.fixed, r.added, r.recurred, r.remaining))
        if (site != null && site.panel.isNotEmpty() && r.panelName.isNotEmpty() && site.panel != r.panelName)
            msg.append("\n\n").append(I18n.f("otherPanel", r.panelName, site.panel))
        tab = 0; searchText = ""
        go(Scr.SITE)
        AlertDialog.Builder(this).setTitle(if (r.first) I18n.t("capture") else I18n.t("compareTitle"))
            .setMessage(msg.toString()).setPositiveButton(I18n.t("close"), null).show()
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
            mainHandler.removeCallbacks(captureRender)
            mainHandler.postDelayed(captureRender, 400)
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
        val logs = store.logs(s.id)
        if (logs.isEmpty()) box.addView(tv(I18n.t("noLogsYet"), 14f, color = Color.DKGRAY))
        for (l in logs) {
            val c = card()
            c.addView(tv(I18n.f("logLine", fmt(l.ts), l.events), 15f, true))
            c.setOnClickListener { logMenu(l) }
            box.addView(c)
        }
        return sv
    }

    private fun logMenu(l: LogRec) {
        val items = arrayOf(I18n.t("paperView"), I18n.t("reportFrom"), I18n.t("logFile"), I18n.t("deleteLog"))
        AlertDialog.Builder(this).setTitle(fmt(l.ts)).setItems(items) { _, w ->
            when (w) {
                0 -> {
                    val raw = try { File(l.raw).readBytes() } catch (_: Exception) { ByteArray(0) }
                    val d = PanelDecoder.decode(raw)
                    paperText = (if (d.graphic) PanelDecoder.logicalLines(d.visualLines) else d.visualLines).joinToString("\n")
                    go(Scr.PAPER)
                }
                1 -> reportMenu(l.ts)
                2 -> shareLogFile(l)
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

    private fun periodMenu() {
        val sid = siteId ?: return
        val logs = store.logs(sid)
        if (logs.isEmpty()) { toast(I18n.t("noLogsForReport")); return }
        AlertDialog.Builder(this).setTitle(I18n.t("periodTitle"))
            .setItems(arrayOf(I18n.t("sinceLast"), I18n.t("sinceDate"), I18n.t("allTime"))) { _, w ->
                when (w) {
                    0 -> reportMenu(if (logs.size >= 2) logs[1].ts else logs[0].ts)
                    1 -> {
                        val cal = Calendar.getInstance()
                        DatePickerDialog(this, { _, y, m, dd ->
                            val c = Calendar.getInstance().apply { set(y, m, dd, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
                            reportMenu(c.timeInMillis)
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    }
                    else -> reportMenu(0)
                }
            }.show()
    }

    private fun reportMenu(fromTs: Long) {
        val sid = siteId ?: return
        val r = store.period(sid, fromTs) ?: run { toast(I18n.t("noLogsForReport")); return }
        // Иврит в настройках — только иврит; русский — выбор: иврит или иврит + перевод
        val opts = if (I18n.he) listOf("waHe" to true, "pdfHe" to true, "saveHe" to true)
            else listOf("waHe" to true, "waRu" to false, "pdfHe" to true, "pdfRu" to false, "saveHe" to true, "saveRu" to false)
        AlertDialog.Builder(this).setTitle(r.site.name)
            .setItems(opts.map { I18n.t(it.first) }.toTypedArray()) { _, w ->
                val (key, heOnly) = opts[w]
                when {
                    key.startsWith("wa") -> shareWhatsapp(PeriodExport.whatsapp(r, store, heOnly))
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

    private fun makeReportPdf(r: PeriodReport, heOnly: Boolean): File? = try {
        val out = File(store.dataDir(), reportName(r, heOnly))
        PeriodExport.pdf(r, store, heOnly, out); out
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
        box.addView(btn(I18n.t("editActions")) { actionsEditor() })
        AlertDialog.Builder(this).setTitle(I18n.t("settings")).setView(box)
            .setPositiveButton(I18n.t("ok")) { _, _ ->
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
        val labels = acts.map { (if (it.billable) "₪ " else "") + it.he + if (it.ru.isNotEmpty()) " / " + it.ru else "" } + I18n.t("addAction")
        AlertDialog.Builder(this).setTitle(I18n.t("editActions"))
            .setItems(labels.toTypedArray()) { _, w -> editAction(if (w < acts.size) acts[w] else null) }
            .setNegativeButton(I18n.t("close"), null).show()
    }

    private fun editAction(a: ActionDef?) {
        val box = dialogBox()
        val heIn = input(I18n.t("actionHe"), a?.he ?: "")
        val ruIn = input(I18n.t("actionRu"), a?.ru ?: "")
        val bill = CheckBox(this).apply { text = I18n.t("actionBill"); isChecked = a?.billable ?: false }
        box.addView(heIn); box.addView(ruIn); box.addView(bill)
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
