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

data class LogRec(val id: String, val siteId: String, val ts: Long, val raw: String, val events: Int)

data class Fault(
    val id: String, val siteId: String, val key: String,
    val address: String, val zone: String, val devType: String, val problem: String,
    val label: String, val label2: String, val node: String,
    val firstSeen: Long, val lastSeen: Long, val active: Boolean, val resolvedTs: Long,
    val recur: Int, val billable: Boolean, val action: String, val comment: String
) {
    val isSystem: Boolean get() = address.isEmpty()
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
    val panelName: String
)

data class PeriodReport(
    val site: Site, val from: Long, val to: Long, val logsCount: Int,
    val billable: List<Fault>, val fixed: List<Fault>, val added: List<Fault>, val remaining: List<Fault>
)

/** Локальная база: объекты, логи, неисправности, история, фото. ID — UUID (готово к синхронизации). */
class Store(private val ctx: Context) : SQLiteOpenHelper(ctx, "g1.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sites(id TEXT PRIMARY KEY, name TEXT, address TEXT, note TEXT, panel TEXT, created INTEGER)")
        db.execSQL("CREATE TABLE logs(id TEXT PRIMARY KEY, site_id TEXT, ts INTEGER, raw TEXT, events INTEGER)")
        db.execSQL("CREATE TABLE faults(id TEXT PRIMARY KEY, site_id TEXT, fkey TEXT, address TEXT, zone TEXT, dev_type TEXT, problem TEXT, label TEXT, label2 TEXT, node TEXT, first_seen INTEGER, last_seen INTEGER, active INTEGER, resolved_ts INTEGER, recur INTEGER, billable INTEGER, action TEXT, comment TEXT, updated INTEGER)")
        db.execSQL("CREATE TABLE log_faults(log_id TEXT, fault_id TEXT)")
        db.execSQL("CREATE TABLE history(id TEXT PRIMARY KEY, fault_id TEXT, ts INTEGER, kind TEXT, text TEXT)")
        db.execSQL("CREATE TABLE photos(id TEXT PRIMARY KEY, fault_id TEXT, path TEXT, ts INTEGER)")
        db.execSQL("CREATE INDEX i_logs_site ON logs(site_id, ts)")
        db.execSQL("CREATE INDEX i_faults_site ON faults(site_id, fkey)")
        db.execSQL("CREATE INDEX i_lf_log ON log_faults(log_id)")
        db.execSQL("CREATE INDEX i_hist_fault ON history(fault_id, ts)")
        db.execSQL("CREATE INDEX i_photos_fault ON photos(fault_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
        db.delete("sites", "id=?", arrayOf(id))
    }

    // ---------------------------------------------------------------- Логи

    fun logs(siteId: String): List<LogRec> = query(
        "SELECT id,site_id,ts,raw,events FROM logs WHERE site_id=? ORDER BY ts DESC", arrayOf(siteId)
    ) { c -> LogRec(c.getString(0), c.getString(1), c.getLong(2), c.getString(3) ?: "", c.getInt(4)) }

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
    fun processLog(siteId: String, raw: ByteArray): CompareResult {
        val ts = now()
        val logId = uuid()
        val rawFile = File(rawDir(), "$logId.bin")
        rawFile.writeBytes(raw)
        val d = PanelDecoder.decode(raw)
        val events = if (d.graphic) PanelDecoder.parse(d.visualLines) else emptyList()
        val byKey = LinkedHashMap<String, PanelDecoder.PanelEvent>()
        for (e in events) byKey.putIfAbsent(keyOf(e), e)

        val db = writableDatabase
        val first = logs(siteId).isEmpty()
        var fixed = 0; var added = 0; var recurred = 0; var remaining = 0
        db.beginTransaction()
        try {
            db.insert("logs", null, ContentValues().apply {
                put("id", logId); put("site_id", siteId); put("ts", ts); put("raw", rawFile.absolutePath); put("events", events.size)
            })
            val existing = faults(siteId).associateBy { it.key }
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
                        put("recur", 0); put("billable", 0); put("action", ""); put("comment", ""); put("updated", ts)
                    })
                    addHistory(faultId, ts, "appeared", "")
                    added++
                } else {
                    faultId = f.id
                    val cv = ContentValues().apply {
                        put("last_seen", ts); put("label", e.label); put("label2", e.label2); put("zone", e.zone); put("dev_type", e.devType)
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
                if (f.active && !byKey.containsKey(f.key)) {
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
        val site = site(siteId)
        if (site != null && site.panel.isEmpty() && panel.isNotEmpty()) {
            db.update("sites", ContentValues().apply { put("panel", panel) }, "id=?", arrayOf(siteId))
        }
        return CompareResult(logId, first, fixed, added, recurred, remaining, byKey.size, panel)
    }

    // ---------------------------------------------------------------- Неисправности

    private fun faultFrom(c: Cursor) = Fault(
        c.getString(0), c.getString(1), c.getString(2), c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "",
        c.getString(6) ?: "", c.getString(7) ?: "", c.getString(8) ?: "", c.getString(9) ?: "",
        c.getLong(10), c.getLong(11), c.getInt(12) == 1, c.getLong(13), c.getInt(14), c.getInt(15) == 1,
        c.getString(16) ?: "", c.getString(17) ?: ""
    )

    private val FCOLS = "id,site_id,fkey,address,zone,dev_type,problem,label,label2,node,first_seen,last_seen,active,resolved_ts,recur,billable,action,comment"

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

    fun setComment(faultId: String, text: String) {
        writableDatabase.update("faults", ContentValues().apply { put("comment", text); put("updated", now()) }, "id=?", arrayOf(faultId))
        if (text.isNotBlank()) addHistory(faultId, now(), "comment", text)
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

    /** Период от лога fromTs до последнего: устранено, новые, осталось, к оплате. */
    fun period(siteId: String, fromTs: Long): PeriodReport? {
        val site = site(siteId) ?: return null
        val all = logs(siteId).sortedBy { it.ts }
        if (all.isEmpty()) return null
        val inRange = all.filter { it.ts >= fromTs }.ifEmpty { listOf(all.last()) }
        val a = logFaultIds(inRange.first().id)
        val b = logFaultIds(inRange.last().id)
        val union = HashSet<String>()
        for (l in inRange) union.addAll(logFaultIds(l.id))
        val byId = faults(siteId).associateBy { it.id }
        fun list(ids: Collection<String>) = ids.mapNotNull { byId[it] }
            .sortedWith(compareBy<Fault>({ it.isSystem }, { it.address }))
        val billable = list(union.filter { byId[it]?.billable == true })
        val billIds = billable.map { it.id }.toSet()
        val fixed = list(union.filter { it !in b && it !in billIds })
        val added = if (inRange.size > 1) list(b.filter { it !in a && it !in billIds }) else emptyList()
        val remaining = list(b.filter { (inRange.size == 1 || it in a) && it !in billIds })
        return PeriodReport(site, inRange.first().ts, inRange.last().ts, inRange.size, billable, fixed, added, remaining)
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
