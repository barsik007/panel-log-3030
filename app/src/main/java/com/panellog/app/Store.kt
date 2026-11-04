package com.panellog.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Site(
    val id: String, val name: String, val address: String, val note: String, val panel: String,
    val created: Long, val lastLogTs: Long = 0, val activeCount: Int = 0
)

data class Panel(
    val id: String, val siteId: String, val name: String, val detected: String, val created: Long,
    val lastLogTs: Long = 0, val activeCount: Int = 0
)

data class LogRec(val id: String, val siteId: String, val ts: Long, val raw: String, val events: Int, val panelId: String)

data class Fault(
    val id: String, val siteId: String, val key: String,
    val address: String, val zone: String, val devType: String, val problem: String,
    val label: String, val label2: String, val node: String,
    val firstSeen: Long, val lastSeen: Long, val active: Boolean, val resolvedTs: Long,
    val recur: Int, val billable: Boolean, val action: String, val comment: String,
    val panelId: String,
    /** «Не актуально»: на утверждении / в согласовании — не трогать. */
    val parked: Boolean = false,
    /** «Починил»: отмечено вручную (время); подтвердится, когда панель перестанет её показывать. */
    val fixedTs: Long = 0,
    /** Убрана из отчёта вручную (долгое нажатие). */
    val hidden: Boolean = false
) {
    val repaired: Boolean get() = fixedTs > 0
    /** Ручная запись техника (не из панели): кнопка сломана, нет таблички и т.п. */
    val isManual: Boolean get() = key.startsWith("MAN|")
    val isSystem: Boolean get() = address.isEmpty() && !isManual
    val loop: String get() = Regex("L(\\d{2})").find(address)?.groupValues?.get(1) ?: ""
}

data class HistoryRec(val ts: Long, val kind: String, val text: String)
data class Photo(val id: String, val path: String, val ts: Long)

data class ActionDef(val id: String, val he: String, val ru: String, val billable: Boolean) {
    fun text(): String = if (I18n.he || ru.isEmpty()) he else "$he ($ru)"
}

data class CompareResult(
    val logId: String, val first: Boolean,
    val fixed: Int, val added: Int, val recurred: Int, val remaining: Int, val total: Int,
    val panelName: String,
    val notConfirmed: Int = 0
)

/** Раздел отчёта по одной панели (רכזת). */
data class PanelSection(
    val panel: Panel, val from: Long, val to: Long, val logsCount: Int,
    val billable: List<Fault>, val fixed: List<Fault>, val added: List<Fault>, val remaining: List<Fault>
)

data class PeriodReport(val site: Site, val from: Long, val to: Long, val panels: List<PanelSection>) {
    val logsCount: Int get() = panels.sumOf { it.logsCount }
    val billable: List<Fault> get() = panels.flatMap { it.billable }
    val fixed: List<Fault> get() = panels.flatMap { it.fixed }
    val added: List<Fault> get() = panels.flatMap { it.added }
    val remaining: List<Fault> get() = panels.flatMap { it.remaining }
}

/** Локальная база: объекты, логи, неисправности, история, фото. ID — UUID (готово к синхронизации). */
class Store(private val ctx: Context) : SQLiteOpenHelper(ctx, "g1.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sites(id TEXT PRIMARY KEY, name TEXT, address TEXT, note TEXT, panel TEXT, created INTEGER)")
        db.execSQL("CREATE TABLE panels(id TEXT PRIMARY KEY, site_id TEXT, name TEXT, detected TEXT, created INTEGER)")
        db.execSQL("CREATE TABLE logs(id TEXT PRIMARY KEY, site_id TEXT, ts INTEGER, raw TEXT, events INTEGER, panel_id TEXT)")
        db.execSQL("CREATE TABLE faults(id TEXT PRIMARY KEY, site_id TEXT, fkey TEXT, address TEXT, zone TEXT, dev_type TEXT, problem TEXT, label TEXT, label2 TEXT, node TEXT, first_seen INTEGER, last_seen INTEGER, active INTEGER, resolved_ts INTEGER, recur INTEGER, billable INTEGER, action TEXT, comment TEXT, updated INTEGER, panel_id TEXT, parked INTEGER DEFAULT 0, fixed_ts INTEGER DEFAULT 0, hidden INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE log_faults(log_id TEXT, fault_id TEXT)")
        db.execSQL("CREATE TABLE history(id TEXT PRIMARY KEY, fault_id TEXT, ts INTEGER, kind TEXT, text TEXT)")
        db.execSQL("CREATE TABLE photos(id TEXT PRIMARY KEY, fault_id TEXT, path TEXT, ts INTEGER)")
        db.execSQL("CREATE INDEX i_logs_site ON logs(site_id, ts)")
        db.execSQL("CREATE INDEX i_faults_site ON faults(site_id, fkey)")
        db.execSQL("CREATE INDEX i_lf_log ON log_faults(log_id)")
        db.execSQL("CREATE INDEX i_hist_fault ON history(fault_id, ts)")
        db.execSQL("CREATE INDEX i_photos_fault ON photos(fault_id)")
    }

    /** v1 → v2: панели (רכזות). Старые логи и неисправности переносятся в «רכזת 1». */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE panels(id TEXT PRIMARY KEY, site_id TEXT, name TEXT, detected TEXT, created INTEGER)")
            db.execSQL("ALTER TABLE logs ADD COLUMN panel_id TEXT")
            db.execSQL("ALTER TABLE faults ADD COLUMN panel_id TEXT")
            val sites = ArrayList<Pair<String, String>>()
            db.rawQuery("SELECT id, panel FROM sites", null).use { c -> while (c.moveToNext()) sites.add(c.getString(0) to (c.getString(1) ?: "")) }
            for ((sid, det) in sites) {
                val pid = uuid()
                db.insert("panels", null, ContentValues().apply {
                    put("id", pid); put("site_id", sid); put("name", "רכזת 1"); put("detected", det); put("created", now())
                })
                db.execSQL("UPDATE logs SET panel_id=? WHERE site_id=?", arrayOf<Any>(pid, sid))
                db.execSQL("UPDATE faults SET panel_id=? WHERE site_id=?", arrayOf<Any>(pid, sid))
            }
        }
        // v2 → v3: отметка «не актуально»
        if (oldVersion < 3) db.execSQL("ALTER TABLE faults ADD COLUMN parked INTEGER DEFAULT 0")
        // v3 → v4: отметка «Починил»
        if (oldVersion < 4) db.execSQL("ALTER TABLE faults ADD COLUMN fixed_ts INTEGER DEFAULT 0")
        // v4 → v5: «убрать из отчёта»
        if (oldVersion < 5) db.execSQL("ALTER TABLE faults ADD COLUMN hidden INTEGER DEFAULT 0")
    }

    private fun uuid() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()

    fun dataDir(): File = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).also { it.mkdirs() }
    private fun rawDir(): File = File(dataDir(), "raw").also { it.mkdirs() }
    fun photoDir(): File = File(dataDir(), "photos").also { it.mkdirs() }

    private inline fun <T> query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(map(c)) }
        return out
    }

    // ---------------------------------------------------------------- Объекты

    fun sites(): List<Site> = query(
        "SELECT s.id,s.name,s.address,s.note,s.panel,s.created," +
            "(SELECT MAX(ts) FROM logs l WHERE l.site_id=s.id)," +
            "(SELECT COUNT(*) FROM faults f WHERE f.site_id=s.id AND f.active=1) " +
            "FROM sites s", emptyArray()
    ) { c ->
        Site(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "",
            c.getLong(5), if (c.isNull(6)) 0 else c.getLong(6), c.getInt(7))
    }.sortedWith(compareByDescending<Site> { maxOf(it.lastLogTs, it.created) })

    fun site(id: String): Site? = sites().firstOrNull { it.id == id }

    fun addSite(name: String, address: String, note: String): String {
        val id = uuid()
        writableDatabase.insert("sites", null, ContentValues().apply {
            put("id", id); put("name", name); put("address", address); put("note", note); put("panel", ""); put("created", now())
        })
        return id
    }

    fun updateSite(id: String, name: String, address: String, note: String) {
        writableDatabase.update("sites", ContentValues().apply {
            put("name", name); put("address", address); put("note", note)
        }, "id=?", arrayOf(id))
    }

    fun deleteSite(id: String) {
        val db = writableDatabase
        for (l in logs(id)) try { File(l.raw).delete() } catch (_: Exception) {}
        val faultIds = query("SELECT id FROM faults WHERE site_id=?", arrayOf(id)) { it.getString(0) }
        for (f in faultIds) {
            for (p in photos(f)) try { File(p.path).delete() } catch (_: Exception) {}
            db.delete("photos", "fault_id=?", arrayOf(f))
            db.delete("history", "fault_id=?", arrayOf(f))
        }
        db.execSQL("DELETE FROM log_faults WHERE log_id IN (SELECT id FROM logs WHERE site_id=?)", arrayOf<Any>(id))
        db.delete("logs", "site_id=?", arrayOf(id))
        db.delete("faults", "site_id=?", arrayOf(id))
        db.delete("panels", "site_id=?", arrayOf(id))
        db.delete("sites", "id=?", arrayOf(id))
    }

    // ---------------------------------------------------------------- Логи

    fun logs(siteId: String): List<LogRec> = query(
        "SELECT id,site_id,ts,raw,events,panel_id FROM logs WHERE site_id=? ORDER BY ts DESC", arrayOf(siteId)
    ) { c -> LogRec(c.getString(0), c.getString(1), c.getLong(2), c.getString(3) ?: "", c.getInt(4), c.getString(5) ?: "") }

    fun panelLogs(panelId: String): List<LogRec> = query(
        "SELECT id,site_id,ts,raw,events,panel_id FROM logs WHERE panel_id=? ORDER BY ts DESC", arrayOf(panelId)
    ) { c -> LogRec(c.getString(0), c.getString(1), c.getLong(2), c.getString(3) ?: "", c.getInt(4), c.getString(5) ?: "") }

    // ---------------------------------------------------------------- Панели (רכזות)

    fun panels(siteId: String): List<Panel> = query(
        "SELECT p.id,p.site_id,p.name,p.detected,p.created," +
            "(SELECT MAX(ts) FROM logs l WHERE l.panel_id=p.id)," +
            "(SELECT COUNT(*) FROM faults f WHERE f.panel_id=p.id AND f.active=1) " +
            "FROM panels p WHERE p.site_id=? ORDER BY p.created", arrayOf(siteId)
    ) { c ->
        Panel(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4),
            if (c.isNull(5)) 0 else c.getLong(5), c.getInt(6))
    }

    fun panel(id: String): Panel? = query(
        "SELECT id,site_id,name,detected,created FROM panels WHERE id=?", arrayOf(id)
    ) { c -> Panel(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4)) }.firstOrNull()

    fun addPanel(siteId: String, name: String): String {
        val id = uuid()
        writableDatabase.insert("panels", null, ContentValues().apply {
            put("id", id); put("site_id", siteId); put("name", name); put("detected", ""); put("created", now())
        })
        return id
    }

    fun renamePanel(id: String, name: String) {
        writableDatabase.update("panels", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id))
    }

    fun deletePanel(id: String) {
        val db = writableDatabase
        for (l in panelLogs(id)) { try { File(l.raw).delete() } catch (_: Exception) {}; db.delete("log_faults", "log_id=?", arrayOf(l.id)) }
        val faultIds = query("SELECT id FROM faults WHERE panel_id=?", arrayOf(id)) { it.getString(0) }
        for (f in faultIds) {
            for (p in photos(f)) try { File(p.path).delete() } catch (_: Exception) {}
            db.delete("photos", "fault_id=?", arrayOf(f))
            db.delete("history", "fault_id=?", arrayOf(f))
        }
        db.delete("logs", "panel_id=?", arrayOf(id))
        db.delete("faults", "panel_id=?", arrayOf(id))
        db.delete("panels", "id=?", arrayOf(id))
    }

    /** Имя панели из системных строк лога (например «נפטון אילת»), если есть. */
    fun detectPanelName(raw: ByteArray): String {
        val d = PanelDecoder.decode(raw)
        if (!d.graphic) return ""
        return ReportExport.siteName(PanelDecoder.parse(d.visualLines))
    }

    fun logFaultIds(logId: String): Set<String> =
        query("SELECT fault_id FROM log_faults WHERE log_id=?", arrayOf(logId)) { it.getString(0) }.toSet()

    fun deleteLog(log: LogRec) {
        try { File(log.raw).delete() } catch (_: Exception) {}
        writableDatabase.delete("log_faults", "log_id=?", arrayOf(log.id))
        writableDatabase.delete("logs", "id=?", arrayOf(log.id))
    }

    private fun keyOf(e: PanelDecoder.PanelEvent): String =
        if (e.address.isNotEmpty()) e.address + "|" + e.problem else "SYS|" + e.node + "|" + e.problem

    /**
     * Сохраняет лог объекта и сравнивает его с текущим состоянием:
     * пропавшие → устранены, новые → добавлены, вернувшиеся → повторные.
     */
    fun processLog(siteId: String, panelId: String, raw: ByteArray, tsIn: Long = 0L): CompareResult {
        val ts = if (tsIn > 0) tsIn else now()
        val logId = uuid()
        val rawFile = File(rawDir(), "$logId.bin")
        rawFile.writeBytes(raw)
        val d = PanelDecoder.decode(raw)
        val events = if (d.graphic) PanelDecoder.parse(d.visualLines) else emptyList()
        val byKey = LinkedHashMap<String, PanelDecoder.PanelEvent>()
        for (e in events) byKey.putIfAbsent(keyOf(e), e)

        val db = writableDatabase
        val first = panelLogs(panelId).isEmpty()
        var fixed = 0; var added = 0; var recurred = 0; var remaining = 0; var notConfirmed = 0
        db.beginTransaction()
        try {
            db.insert("logs", null, ContentValues().apply {
                put("id", logId); put("site_id", siteId); put("ts", ts); put("raw", rawFile.absolutePath); put("events", events.size); put("panel_id", panelId)
            })
            val existing = faults(siteId).filter { it.panelId == panelId }.associateBy { it.key }
            for ((key, e) in byKey) {
                val f = existing[key]
                val faultId: String
                if (f == null) {
                    faultId = uuid()
                    db.insert("faults", null, ContentValues().apply {
                        put("id", faultId); put("site_id", siteId); put("fkey", key)
                        put("address", e.address); put("zone", e.zone); put("dev_type", e.devType); put("problem", e.problem)
                        put("label", e.label); put("label2", e.label2); put("node", e.node)
                        put("first_seen", ts); put("last_seen", ts); put("active", 1); put("resolved_ts", 0)
                        put("recur", 0); put("billable", 0); put("action", ""); put("comment", ""); put("updated", ts); put("panel_id", panelId)
                    })
                    addHistory(faultId, ts, "appeared", "")
                    added++
                } else {
                    faultId = f.id
                    val cv = ContentValues().apply {
                        put("last_seen", ts); put("label", e.label); put("label2", e.label2); put("zone", e.zone); put("dev_type", e.devType)
                    }
                    if (f.fixedTs in 1 until ts) {
                        // отметили «Починил», а панель всё ещё показывает — отметку снимаем
                        cv.put("fixed_ts", 0)
                        if (f.active) { addHistory(faultId, ts, "repairNotConfirmed", ""); notConfirmed++ }
                    }
                    if (!f.active) {
                        cv.put("active", 1); cv.put("resolved_ts", 0); cv.put("recur", f.recur + 1); cv.put("updated", ts)
                        addHistory(faultId, ts, "recurred", "")
                        recurred++
                    } else remaining++
                    db.update("faults", cv, "id=?", arrayOf(faultId))
                }
                db.insert("log_faults", null, ContentValues().apply { put("log_id", logId); put("fault_id", faultId) })
            }
            for (f in existing.values) {
                if (f.active && !f.isManual && !byKey.containsKey(f.key)) {   // ручные записи панель не закрывает
                    db.update("faults", ContentValues().apply {
                        put("active", 0); put("resolved_ts", ts); put("updated", ts)
                    }, "id=?", arrayOf(f.id))
                    addHistory(f.id, ts, "resolved", "")
                    fixed++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val panel = ReportExport.siteName(events)
        val p = panel(panelId)
        if (p != null && p.detected.isEmpty() && panel.isNotEmpty()) {
            db.update("panels", ContentValues().apply { put("detected", panel) }, "id=?", arrayOf(panelId))
        }
        return CompareResult(logId, first, fixed, added, recurred, remaining, byKey.size, panel, notConfirmed)
    }

    // ---------------------------------------------------------------- Неисправности

    private fun faultFrom(c: Cursor) = Fault(
        c.getString(0), c.getString(1), c.getString(2), c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "",
        c.getString(6) ?: "", c.getString(7) ?: "", c.getString(8) ?: "", c.getString(9) ?: "",
        c.getLong(10), c.getLong(11), c.getInt(12) == 1, c.getLong(13), c.getInt(14), c.getInt(15) == 1,
        c.getString(16) ?: "", c.getString(17) ?: "", c.getString(18) ?: "",
        c.getInt(19) == 1, c.getLong(20), c.getInt(21) == 1
    )

    private val FCOLS = "id,site_id,fkey,address,zone,dev_type,problem,label,label2,node,first_seen,last_seen,active,resolved_ts,recur,billable,action,comment,panel_id,parked,fixed_ts,hidden"

    fun faults(siteId: String): List<Fault> =
        query("SELECT $FCOLS FROM faults WHERE site_id=?", arrayOf(siteId)) { faultFrom(it) }
            .sortedWith(compareBy<Fault>({ it.isSystem }, { it.address }, { it.problem }))

    fun fault(id: String): Fault? =
        query("SELECT $FCOLS FROM faults WHERE id=?", arrayOf(id)) { faultFrom(it) }.firstOrNull()

    private fun addHistory(faultId: String, ts: Long, kind: String, text: String) {
        writableDatabase.insert("history", null, ContentValues().apply {
            put("id", uuid()); put("fault_id", faultId); put("ts", ts); put("kind", kind); put("text", text)
        })
    }

    fun history(faultId: String): List<HistoryRec> =
        query("SELECT ts,kind,text FROM history WHERE fault_id=? ORDER BY ts DESC", arrayOf(faultId)) {
            HistoryRec(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "")
        }

    fun setAction(faultId: String, action: String, billable: Boolean?) {
        val cv = ContentValues().apply { put("action", action); put("updated", now()) }
        if (billable != null) cv.put("billable", if (billable) 1 else 0)
        writableDatabase.update("faults", cv, "id=?", arrayOf(faultId))
        addHistory(faultId, now(), "action", action)
    }

    fun setBillable(faultId: String, b: Boolean) {
        writableDatabase.update("faults", ContentValues().apply { put("billable", if (b) 1 else 0); put("updated", now()) }, "id=?", arrayOf(faultId))
    }

    fun setParked(faultId: String, b: Boolean) {
        writableDatabase.update("faults", ContentValues().apply { put("parked", if (b) 1 else 0); put("updated", now()) }, "id=?", arrayOf(faultId))
        addHistory(faultId, now(), if (b) "parked" else "unparked", "")
    }

    fun setRepaired(faultId: String, b: Boolean) {
        val t = now()
        writableDatabase.update("faults", ContentValues().apply { put("fixed_ts", if (b) t else 0L); put("updated", t) }, "id=?", arrayOf(faultId))
        addHistory(faultId, t, if (b) "repaired" else "unrepaired", "")
    }

    /** Новая ручная запись по объекту. */
    fun addManual(siteId: String, panelId: String, address: String, problem: String, place: String, billable: Boolean,
                  key: String = "MAN|" + uuid(), ts: Long = now()): String {
        val id = uuid()
        writableDatabase.insert("faults", null, ContentValues().apply {
            put("id", id); put("site_id", siteId); put("fkey", key)
            put("address", address); put("zone", ""); put("dev_type", ""); put("problem", problem)
            put("label", place); put("label2", ""); put("node", "")
            put("first_seen", ts); put("last_seen", ts); put("active", 1); put("resolved_ts", 0)
            put("recur", 0); put("billable", if (billable) 1 else 0); put("action", ""); put("comment", ""); put("updated", ts); put("panel_id", panelId)
        })
        addHistory(id, ts, "manual", "")
        return id
    }

    fun updateManual(id: String, address: String, problem: String, place: String) {
        writableDatabase.update("faults", ContentValues().apply {
            put("address", address); put("problem", problem); put("label", place); put("updated", now())
        }, "id=?", arrayOf(id))
    }

    fun setHidden(faultId: String, b: Boolean) {
        writableDatabase.update("faults", ContentValues().apply { put("hidden", if (b) 1 else 0); put("updated", now()) }, "id=?", arrayOf(faultId))
        addHistory(faultId, now(), if (b) "hidden" else "unhidden", "")
    }

    /** Удалить ошибку полностью (если панель покажет её снова — появится как новая). */
    fun deleteFault(faultId: String) {
        val db = writableDatabase
        for (p in photos(faultId)) try { File(p.path).delete() } catch (_: Exception) {}
        db.delete("photos", "fault_id=?", arrayOf(faultId))
        db.delete("history", "fault_id=?", arrayOf(faultId))
        db.delete("log_faults", "fault_id=?", arrayOf(faultId))
        db.delete("faults", "id=?", arrayOf(faultId))
    }

    fun setComment(faultId: String, text: String) {
        writableDatabase.update("faults", ContentValues().apply { put("comment", text); put("updated", now()) }, "id=?", arrayOf(faultId))
        if (text.isNotBlank()) addHistory(faultId, now(), "comment", text)
    }

    /** Заметки по ошибкам панели для передачи другому технику. */
    /** panelId = "" и siteId — записи объекта без привязки к панели. */
    fun notesJson(panelId: String, siteId: String? = null): JSONArray {
        val a = JSONArray()
        val rows = if (siteId != null) query("SELECT $FCOLS FROM faults WHERE site_id=? AND panel_id=?", arrayOf(siteId, panelId)) { faultFrom(it) }
            else query("SELECT $FCOLS FROM faults WHERE panel_id=?", arrayOf(panelId)) { faultFrom(it) }
        for (f in rows) {
            if (!f.isManual && f.comment.isBlank() && f.action.isEmpty() && !f.billable && !f.parked && !f.repaired) continue
            if (f.hidden) continue
            a.put(JSONObject().put("key", f.key).put("comment", f.comment).put("action", f.action)
                .put("billable", f.billable).put("parked", f.parked).put("fixed", f.repaired && f.active)
                .apply { if (f.isManual) put("manual", true).put("address", f.address).put("problem", f.problem).put("place", f.label).put("ts", f.firstSeen) })
        }
        return a
    }

    /** Добавить заметки другого техника к своим: ничего не стирается; разные комментарии склеиваются с именем. */
    fun mergeNotes(panelId: String, notes: JSONArray, who: String, siteIdIn: String? = null): Int {
        val mine = (if (siteIdIn != null) query("SELECT $FCOLS FROM faults WHERE site_id=? AND panel_id=?", arrayOf(siteIdIn, panelId)) { faultFrom(it) }
            else query("SELECT $FCOLS FROM faults WHERE panel_id=?", arrayOf(panelId)) { faultFrom(it) }).associateBy { it.key }
        var changed = 0
        for (i in 0 until notes.length()) {
            val n = notes.optJSONObject(i) ?: continue
            var f = mine[n.optString("key")]
            if (f == null && n.optBoolean("manual")) {   // ручная запись другого техника — создать у себя
                val sid = siteIdIn ?: panel(panelId)?.siteId ?: continue
                val id = addManual(sid, panelId, n.optString("address"), n.optString("problem"), n.optString("place"), false,
                    n.optString("key"), n.optLong("ts").takeIf { it > 0 } ?: now())
                f = fault(id); changed++
            }
            if (f == null) continue
            var ch = false
            val t = n.optString("comment").trim()
            val m = f.comment.trim()
            if (t.isNotEmpty() && !m.contains(t)) {
                val merged = when {
                    m.isEmpty() || t.contains(m) -> t
                    else -> m + "\n— " + who + ": " + t
                }
                setComment(f.id, merged); ch = true
            }
            val act = n.optString("action")
            if (act.isNotEmpty() && f.action.isEmpty()) { setAction(f.id, act, null); ch = true }
            if (n.optBoolean("billable") && !f.billable) { setBillable(f.id, true); ch = true }
            if (n.optBoolean("parked") && !f.parked) { setParked(f.id, true); ch = true }
            if (n.optBoolean("fixed") && !f.repaired && f.active) { setRepaired(f.id, true); ch = true }
            if (ch) changed++
        }
        return changed
    }

    fun newPhotoFile(): File = File(photoDir(), uuid() + ".jpg")

    fun addPhoto(faultId: String, file: File) {
        writableDatabase.insert("photos", null, ContentValues().apply {
            put("id", uuid()); put("fault_id", faultId); put("path", file.absolutePath); put("ts", now())
        })
        addHistory(faultId, now(), "photo", file.name)
    }

    fun photos(faultId: String): List<Photo> =
        query("SELECT id,path,ts FROM photos WHERE fault_id=? ORDER BY ts", arrayOf(faultId)) {
            Photo(it.getString(0), it.getString(1) ?: "", it.getLong(2))
        }

    fun deletePhoto(p: Photo) {
        try { File(p.path).delete() } catch (_: Exception) {}
        writableDatabase.delete("photos", "id=?", arrayOf(p.id))
    }

    // ---------------------------------------------------------------- Отчёт за период

    /** Период от fromTs до последнего лога — по каждой панели отдельно (или по одной). */
    fun period(siteId: String, onlyPanel: String?, fromFor: (List<LogRec>) -> Long): PeriodReport? {
        val site = site(siteId) ?: return null
        val byId = faults(siteId).filter { !it.hidden }.associateBy { it.id }   // убранные вручную — не в отчёте
        fun list(ids: Collection<String>) = ids.mapNotNull { byId[it] }
            .sortedWith(compareBy<Fault>({ it.isSystem }, { it.address }))
        val sections = ArrayList<PanelSection>()
        for (p in panels(siteId)) {
            if (onlyPanel != null && p.id != onlyPanel) continue
            val all = panelLogs(p.id).sortedBy { it.ts }
            val manual = byId.values.filter { it.panelId == p.id && it.isManual }
            if (all.isEmpty() && manual.isEmpty()) continue
            val fromTs = if (all.isEmpty()) 0L else fromFor(all)
            val inRange = all.filter { it.ts >= fromTs }
            if (inRange.isEmpty() && manual.isEmpty()) continue
            val a = if (inRange.isEmpty()) HashSet() else logFaultIds(inRange.first().id).toHashSet()
            val b = if (inRange.isEmpty()) HashSet() else logFaultIds(inRange.last().id).toHashSet()
            val union = HashSet<String>()
            for (l in inRange) union.addAll(logFaultIds(l.id))
            // ручные записи: открытые — как «оставшиеся», «починил» — как устранённые
            for (m in manual) {
                if (m.active) { union.add(m.id); a.add(m.id); b.add(m.id) }
            }
            val billable = list(union.filter { byId[it]?.billable == true })
            val billIds = billable.map { it.id }.toSet()
            // «Починил» (ещё видна в панели, но отмечена) — считается устранённой
            val rep = union.filter { byId[it]?.let { f -> f.active && f.repaired } == true }.toSet()
            val fixed = list(union.filter { (it !in b || it in rep) && it !in billIds })
            val added = if (inRange.size > 1) list(b.filter { it !in a && it !in billIds && it !in rep }) else emptyList()
            val remaining = list(b.filter { (inRange.size <= 1 || it in a) && it !in billIds && it !in rep })
            val t0 = if (inRange.isEmpty()) manual.minOf { it.firstSeen } else inRange.first().ts
            val t1 = if (inRange.isEmpty()) manual.maxOf { it.lastSeen } else inRange.last().ts
            sections.add(PanelSection(p, t0, t1, inRange.size, billable, fixed, added, remaining))
        }
        // записи техника без привязки к רכזת — отдельным разделом в конце
        if (onlyPanel == null) {
            val loose = byId.values.filter { it.isManual && it.panelId.isEmpty() }
            if (loose.isNotEmpty()) {
                val pseudo = Panel("", siteId, "✍ רישומי טכנאי", "", 0)
                val open = loose.filter { it.active && !it.repaired }
                val fixed = loose.filter { it.active && it.repaired }
                sections.add(PanelSection(pseudo, loose.minOf { it.firstSeen }, loose.maxOf { it.lastSeen }, 0,
                    list(open.filter { it.billable }.map { it.id }), list(fixed.map { it.id }), emptyList(),
                    list(open.filter { !it.billable }.map { it.id })))
            }
        }
        if (sections.isEmpty()) return null
        return PeriodReport(site, sections.minOf { it.from }, sections.maxOf { it.to }, sections)
    }

    // ---------------------------------------------------------------- Действия (настраиваемые)

    private fun prefs() = ctx.getSharedPreferences("panellog", Context.MODE_PRIVATE)

    fun defaultActions(): List<ActionDef> = listOf(
        ActionDef("fixed", "תוקן", "починил", false),
        ActionDef("replaced", "הוחלף גלאי", "заменил датчик", false),
        ActionDef("cleaned", "נוקה גלאי", "почистил датчик", false),
        ActionDef("base", "הוחלף בסיס", "заменил базу", false),
        ActionDef("wiring", "תוקן חיווט", "исправил проводку", false),
        ActionDef("missing", "חסר גלאי", "нет датчика", true),
        ActionDef("wait", "ממתין לאישור — לא לטפל", "ждёт одобрения — не трогать", false)
    )

    fun actions(): List<ActionDef> {
        val js = prefs().getString("actions", null) ?: return defaultActions()
        return try {
            val arr = JSONArray(js)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ActionDef(o.getString("id"), o.optString("he"), o.optString("ru"), o.optBoolean("bill"))
            }
        } catch (_: Exception) { defaultActions() }
    }

    fun saveActions(list: List<ActionDef>) {
        val arr = JSONArray()
        for (a in list) arr.put(JSONObject().put("id", a.id).put("he", a.he).put("ru", a.ru).put("bill", a.billable))
        prefs().edit().putString("actions", arr.toString()).apply()
    }

    fun newActionId(): String = "a_" + uuid().take(8)

    /** Текст отметки: id действия или «custom:текст». */
    fun actionText(action: String): String {
        if (action.isEmpty()) return ""
        if (action.startsWith("custom:")) return action.removePrefix("custom:")
        return actions().firstOrNull { it.id == action }?.text() ?: action
    }

    fun actionHe(action: String): String {
        if (action.isEmpty()) return ""
        if (action.startsWith("custom:")) return action.removePrefix("custom:")
        return actions().firstOrNull { it.id == action }?.he ?: action
    }

    fun actionRu(action: String): String? {
        if (action.isEmpty() || action.startsWith("custom:")) return null
        return actions().firstOrNull { it.id == action }?.ru?.ifEmpty { null }
    }
}
