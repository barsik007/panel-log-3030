package com.panellog.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Готовые форматы для отправки: текст для WhatsApp и PDF-отчёт. */
object ReportExport {

    /** Название панели/объекта: самый частый «узел» у системных событий. */
    fun siteName(events: List<PanelDecoder.PanelEvent>): String =
        events.filter { it.node.isNotEmpty() }.groupingBy { it.node }.eachCount()
            .maxByOrNull { it.value }?.key ?: ""

    /** Дата отчёта = самое позднее событие (по настоящей дате, а не по строке «ДД.ММ»). */
    private fun reportDate(events: List<PanelDecoder.PanelEvent>): String {
        val fmt = SimpleDateFormat("d.M.yyyy H:mm:ss", Locale.US)
        val latest = events.maxOfOrNull { e -> try { fmt.parse(e.date + " " + e.time)?.time ?: 0L } catch (_: Exception) { 0L } } ?: 0L
        return SimpleDateFormat("dd.MM.yyyy", Locale.US).format(if (latest > 0) Date(latest) else Date())
    }

    internal fun problemLine(e: PanelDecoder.PanelEvent, he: Boolean): String {
        if (he) return e.problem
        val ru = PanelDecoder.ru(e.problem)
        return if (ru != null) "${e.problem} ($ru)" else e.problem
    }

    internal fun typeText(e: PanelDecoder.PanelEvent, he: Boolean): String {
        if (e.devType.isEmpty()) return ""
        val h = PanelDecoder.typeHe(e.devType)
        val r = PanelDecoder.typeRu(e.devType)
        return if (he || r == null) h else "$h ($r)"
    }

    internal fun place(e: PanelDecoder.PanelEvent): String =
        listOf(e.label, e.label2).filter { it.isNotEmpty() }.joinToString(" · ")

    /** Текст для WhatsApp: *жирный* заголовок, устройства по адресам, системные отдельно. */
    fun whatsappText(events: List<PanelDecoder.PanelEvent>, siteIn: String, he: Boolean): String {
        val site = siteIn.ifBlank { siteName(events) }
        val dev = events.filter { !it.isSystem }
        val sys = events.filter { it.isSystem }
        val sb = StringBuilder()
        if (he) {
            sb.append("*תקלות ברכזת").append(if (site.isNotEmpty()) " — $site" else "").append("*\n")
            sb.append(reportDate(events)).append(" · אירועים: ").append(events.size).append("\n\n")
        } else {
            sb.append("*Неисправности панели").append(if (site.isNotEmpty()) " — $site" else "").append("*\n")
            sb.append(reportDate(events)).append(" · событий: ").append(events.size).append("\n\n")
        }
        if (dev.isNotEmpty()) {
            sb.append(if (he) "*התקנים (" else "*Устройства (").append(dev.size).append("):*\n")
            dev.forEachIndexed { i, e ->
                sb.append(i + 1).append(". ").append(e.address)
                val t = typeText(e, he)
                if (t.isNotEmpty()) sb.append(" · ").append(t)
                sb.append(" — ").append(problemLine(e, he)).append('\n')
                val p = place(e)
                if (p.isNotEmpty()) sb.append("    ").append(p).append('\n')
            }
            sb.append('\n')
        }
        if (sys.isNotEmpty()) {
            sb.append(if (he) "*מערכת (" else "*Системные (").append(sys.size).append("):*\n")
            sys.forEach { e -> sb.append("• ").append(problemLine(e, he)).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    /** PDF A4: шапка и таблица; текст переносится, иврит выравнивается справа налево. */
    /** heads: индекс события → заголовок раздела (רכזת), нумерация в разделе с 1. */
    fun writePdf(events: List<PanelDecoder.PanelEvent>, out: File, siteIn: String, he: Boolean, heads: Map<Int, String> = emptyMap()) {
        val pageW = 595; val pageH = 842; val margin = 36
        val doc = PdfDocument()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.rgb(60, 60, 60) }
        val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.BLACK }
        val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; typeface = Typeface.MONOSPACE; color = Color.BLACK }
        val line = Paint().apply { color = Color.rgb(150, 150, 150); strokeWidth = 0.7f; style = Paint.Style.STROKE }
        val headFill = Paint().apply { color = Color.rgb(232, 234, 237); style = Paint.Style.FILL }

        // Колонки слева направо; для отчёта на иврите порядок зеркальный (# справа).
        val baseWidths = intArrayOf(20, 58, 88, 112, 170, 75) // сумма 523 = 595 - 2*36
        val baseHeaders = if (he) arrayOf("#", "כתובת", "סוג", "תקלה", "מיקום", "זמן")
            else arrayOf("#", "כתובת\nАдрес", "סוג\nТип", "תקלה\nНеисправность", "מיקום\nМесто", "זמן\nВремя")
        val widths = if (he) baseWidths.reversedArray() else baseWidths
        val headers = if (he) baseHeaders.reversedArray() else baseHeaders
        val pad = 4f

        fun layout(text: String, paint: TextPaint, w: Int): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, w - (2 * pad).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun drawRow(cells: List<StaticLayout>, header: Boolean) {
            val h = cells.maxOf { it.height } + 2 * pad
            val c = page!!.canvas
            var x = margin.toFloat()
            if (header) c.drawRect(x, y, (pageW - margin).toFloat(), y + h, headFill)
            for ((k, l) in cells.withIndex()) {
                c.save(); c.translate(x + pad, y + pad); l.draw(c); c.restore()
                c.drawRect(x, y, x + widths[k], y + h, line)
                x += widths[k]
            }
            y += h
        }

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin.toFloat()
            if (pageNo == 1) {
                val site = siteIn.ifBlank { siteName(events) }
                val head = if (he) "דוח תקלות רכזת" else "דוח תקלות רכזת · Отчёт о неисправностях"
                val t = layout(head + if (site.isNotEmpty()) "\n$site" else "", titlePaint, pageW - 2 * margin)
                page!!.canvas.save(); page!!.canvas.translate(margin.toFloat(), y); t.draw(page!!.canvas); page!!.canvas.restore()
                y += t.height + 6
                val sys = events.count { it.isSystem }
                val meta = if (he)
                    "תאריך: ${reportDate(events)}   אירועים: ${events.size} (התקנים ${events.size - sys}, מערכת $sys)   רכזת: NFS2-3030"
                    else "Дата: ${reportDate(events)}   Событий: ${events.size} (устройства ${events.size - sys}, системные $sys)   Панель: NFS2-3030"
                val m = layout(meta, metaPaint, pageW - 2 * margin)
                page!!.canvas.save(); page!!.canvas.translate(margin.toFloat(), y); m.draw(page!!.canvas); page!!.canvas.restore()
                y += m.height + 10
            }
            drawRow(headers.mapIndexed { k, s -> layout(s, headPaint, widths[k]) }, true)
        }

        val panelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(179, 38, 30) }
        newPage()
        var num = 0
        events.forEachIndexed { i, e ->
            heads[i]?.let { h ->
                val t = layout(h, panelPaint, pageW - 2 * margin)
                if (y + t.height + 60 > pageH - margin - 16) newPage()
                y += 10
                page!!.canvas.save(); page!!.canvas.translate(margin.toFloat(), y); t.draw(page!!.canvas); page!!.canvas.restore()
                y += t.height + 4
                drawRow(headers.mapIndexed { k, s -> layout(s, headPaint, widths[k]) }, true)
                num = 0
            }
            num++
            val texts = listOf(
                num.toString(),
                if (e.isSystem) "—" else e.address + (if (e.zone.isNotEmpty()) "\n" + e.zone else ""),
                if (e.isSystem) (if (he) "מערכת" else "מערכת\nсистема") else {
                    val h = PanelDecoder.typeHe(e.devType); val r = PanelDecoder.typeRu(e.devType)
                    if (he || r == null) h else "$h\n$r"
                },
                e.problem + (if (he) "" else PanelDecoder.ru(e.problem)?.let { "\n$it" } ?: ""),
                if (e.isSystem) (e.node.ifEmpty { if (he) "מערכת" else "Система" }) else place(e).ifEmpty { "—" },
                e.date + "\n" + e.time
            )
            val paints = listOf(cellPaint, monoPaint, cellPaint, cellPaint, cellPaint, cellPaint)
            val order = if (he) listOf(5, 4, 3, 2, 1, 0) else listOf(0, 1, 2, 3, 4, 5)
            val cells = order.mapIndexed { col, k -> layout(texts[k], paints[k], widths[col]) }
            val h = cells.maxOf { it.height } + 2 * pad
            if (y + h > pageH - margin - 16) newPage()
            drawRow(cells, false)
        }
        page?.let { doc.finishPage(it) }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }
}

/** Отчёт за период по объекту: WhatsApp и PDF. heOnly — только иврит, иначе иврит + перевод. */
object PeriodExport {
    /** Фото для PDF: уменьшенное, с учётом поворота камеры (EXIF). */
    private fun loadPhoto(path: String, maxPx: Int): android.graphics.Bitmap? = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        var sz = 1
        while (maxOf(o.outWidth, o.outHeight) / (sz * 2) >= maxPx) sz *= 2
        var b = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sz })
        val rot = when (android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (b != null && rot != 0f) b = android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height,
            android.graphics.Matrix().apply { postRotate(rot) }, true)
        b
    } catch (_: Exception) { null }

    /** Полное описание одной ошибки — для отправки в WhatsApp вместе с фото. */
    fun faultMessage(f: Fault, siteName: String, panelName: String, store: Store, heOnly: Boolean): String {
        val sb = StringBuilder()
        sb.append("*").append(siteName).append(if (panelName.isNotEmpty()) " · $panelName" else "").append("*\n")
        if (f.address.isNotEmpty()) sb.append(f.address).append(" · ")
        sb.append(type(f, heOnly)).append('\n')
        sb.append("*").append(problem(f, heOnly)).append("*\n")
        val pl = place(f, heOnly)
        if (pl.isNotEmpty()) sb.append("📍 ").append(pl).append('\n')
        val dn = done(f, store, heOnly)
        sb.append("💬 ").append(dn.ifEmpty { if (heOnly) "אין הערה" else "אין הערה · нет комментария" })
        return sb.toString().trimEnd()
    }


    private fun d(ts: Long) = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(ts))
    private fun dt(ts: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(ts))

    private fun periodText(r: PeriodReport) = periodText2(r.from, r.to)
    private fun periodText2(from: Long, to: Long) =
        if (d(from) == d(to)) d(to) else d(from) + " – " + d(to)

    private data class Sec(val he: String, val ru: String, val items: List<Fault>)

    /** Главное — что осталось (с причинами); затем коротко — что устранено. */
    private fun openOf(r: PanelSection): List<Fault> =
        (r.billable.filter { it.active && !it.repaired } + r.added + r.remaining)
            .sortedWith(compareBy<Fault>({ it.isSystem }, { it.address }))
    private fun fixedOf(r: PanelSection): List<Fault> =
        (r.fixed + r.billable.filter { !it.active || it.repaired }).sortedWith(compareBy<Fault>({ it.isSystem }, { it.address }))

    /** Начальнику — только активные (оставшиеся) с комментариями; «для себя» — только проделанная работа. */
    private fun sections(r: PanelSection, work: Boolean) =
        if (!work) listOf(Sec("תקלות פעילות", "Активные неисправности", openOf(r)))
        else listOf(Sec("תוקן", "Устранено", fixedOf(r)))

    /** Пометки в строке: ₪ к оплате, 🆕 новая, 🔁 повторная. */
    private fun tags(f: Fault, r: PanelSection, heOnly: Boolean): String {
        val t = ArrayList<String>()
        if (f.repaired) t.add(if (heOnly) "🔧 תוקן ע״י הטכנאי" else "🔧 תוקן ע״י הטכנאי (починено техником)")
        if (f.parked) t.add(if (heOnly) "⏸ ממתין לאישור" else "⏸ ממתין לאישור (на утверждении)")
        if (r.added.any { it.id == f.id }) t.add(if (heOnly) "🆕 חדש" else "🆕 חדש (новая)")
        if (f.recur > 0) t.add("🔁 ×" + f.recur)
        return t.joinToString("  ")
    }

    private fun countsLine(r: PeriodReport, heOnly: Boolean, work: Boolean = false): String {
        val open = r.panels.sumOf { openOf(it).size }
        val fixed = r.panels.sumOf { fixedOf(it).size }
        val byTech = r.panels.sumOf { p -> fixedOf(p).count { it.repaired } }
        if (work) return if (heOnly) "תוקנו: $fixed (מהן סומנו ע״י הטכנאי: $byTech) · נשארו פתוחות: $open"
            else "Устранено: $fixed (из них отмечено техником: $byTech) · осталось: $open"
        return if (heOnly) "תקלות פעילות: $open"
            else "Активных неисправностей: $open"
    }

    private fun problem(f: Fault, heOnly: Boolean): String {
        if (heOnly || f.isManual) return f.problem
        val ru = PanelDecoder.ru(f.problem) ?: AutoTr.get(f.problem) ?: return f.problem
        return "${f.problem} ($ru)"
    }

    private fun type(f: Fault, heOnly: Boolean): String {
        if (f.isManual) return if (heOnly) "✍ רישום טכנאי" else "✍ רישום טכנאי (запись техника)"
        if (f.isSystem) return if (heOnly) "מערכת" else "מערכת (система)"
        val h = PanelDecoder.typeHe(f.devType)
        val r = PanelDecoder.typeRu(f.devType)
        return if (heOnly || r == null) h else "$h ($r)"
    }

    private fun place(f: Fault, heOnly: Boolean): String {
        val parts = if (f.isSystem) listOf(f.node) else listOf(f.label, f.label2)
        return parts.filter { it.isNotEmpty() }.joinToString(" · ") { if (heOnly) it else AutoTr.withRu(it) }
    }

    private fun done(f: Fault, store: Store, heOnly: Boolean): String {
        val parts = ArrayList<String>()
        if (f.action.isNotEmpty()) {
            val h = store.actionHe(f.action)
            val r = store.actionRu(f.action)
            parts.add(if (heOnly || r == null) h else "$h ($r)")
        }
        if (f.comment.isNotBlank()) parts.add(f.comment)
        return parts.joinToString(" — ")
    }

    private fun titleOf(heOnly: Boolean, work: Boolean) = when {
        work && heOnly -> "סיכום עבודה"
        work -> "סיכום עבודה · Проделанная работа"
        heOnly -> "דוח תקלות רכזת"
        else -> "דוח תקלות רכזת · Отчёт о неисправностях"
    }

    fun whatsapp(r: PeriodReport, store: Store, heOnly: Boolean, work: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("*").append(r.site.name).append("*\n")
        sb.append(titleOf(heOnly, work)).append(" · ").append(if (work) periodText(r) else d(r.to)).append("\n")
        sb.append(countsLine(r, heOnly, work)).append("\n")
        for (ps in r.panels) {
        sb.append("\n━━ *").append(ps.panel.name).append("*")
        if (ps.panel.detected.isNotEmpty() && ps.panel.detected != ps.panel.name) sb.append(" (").append(ps.panel.detected).append(")")
        sb.append(" ━━\n")
        for (s in sections(ps, work)) {
            if (s.items.isEmpty()) continue
            val openSec = !work
            sb.append("\n*").append(s.he).append(if (heOnly) "" else " / " + s.ru).append(" (").append(s.items.size).append("):*\n")
            s.items.forEachIndexed { i, f ->
                sb.append(i + 1).append(". ")
                if (f.address.isNotEmpty()) sb.append(f.address).append(" · ")
                sb.append(type(f, heOnly)).append(" — ").append(problem(f, heOnly)).append('\n')
                val p = place(f, heOnly)
                if (p.isNotEmpty()) sb.append("    📍 ").append(p).append('\n')
                val tg = tags(f, ps, heOnly)
                if (tg.isNotEmpty()) sb.append("    ").append(tg).append('\n')
                val dn = done(f, store, heOnly)
                if (openSec) sb.append("    💬 ").append(dn.ifEmpty { if (heOnly) "— אין הערה" else "— нет комментария" }).append('\n')
                else if (dn.isNotEmpty()) sb.append("    ✔ ").append(dn).append('\n')
            }
        }
        }
        return sb.toString().trimEnd()
    }

    fun pdf(r: PeriodReport, store: Store, heOnly: Boolean, out: File, work: Boolean = false, photos: Boolean = false) {
        val pageW = 595; val pageH = 842; val margin = 30
        val doc = PdfDocument()
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = Color.rgb(50, 50, 50) }
        val secPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.BLACK }
        val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; typeface = Typeface.MONOSPACE; color = Color.BLACK }
        val line = Paint().apply { color = Color.rgb(150, 150, 150); strokeWidth = 0.7f; style = Paint.Style.STROKE }
        val headFill = Paint().apply { color = Color.rgb(232, 234, 237); style = Paint.Style.FILL }
        val pad = 3.5f
        val content = pageW - 2 * margin // 535

        val base = intArrayOf(18, 54, 78, 100, 135, 150) // = 535
        val headersHe = arrayOf("#", "כתובת", "סוג", "תקלה", "מיקום", "סיבה / הערה")
        val headersRu = arrayOf("#", "כתובת\nАдрес", "סוג\nТип", "תקלה\nНеисправность", "מיקום\nМесто", "סיבה / הערה\nПричина / комментарий")
        val widths = if (heOnly) base.reversedArray() else base
        val headers = (if (heOnly) headersHe else headersRu).let { if (heOnly) it.reversedArray() else it }
        val order = if (heOnly) listOf(5, 4, 3, 2, 1, 0) else listOf(0, 1, 2, 3, 4, 5)

        fun layout(text: String, paint: TextPaint, w: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, maxOf(10, w - (2 * pad).toInt()))
                .setAlignment(align).setIncludePad(false).build()

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun drawLayout(l: StaticLayout, x: Float) {
            val c = page!!.canvas
            c.save(); c.translate(x, y); l.draw(c); c.restore()
        }

        fun drawRow(cells: List<StaticLayout>, header: Boolean) {
            val h = cells.maxOf { it.height } + 2 * pad
            val c = page!!.canvas
            var x = margin.toFloat()
            if (header) c.drawRect(x, y, (pageW - margin).toFloat(), y + h, headFill)
            for ((k, l) in cells.withIndex()) {
                c.save(); c.translate(x + pad, y + pad); l.draw(c); c.restore()
                c.drawRect(x, y, x + widths[k], y + h, line)
                x += widths[k]
            }
            y += h
        }

        fun headerRow() = drawRow(headers.mapIndexed { k, s -> layout(s, headPaint, widths[k]) }, true)

        fun newPage(withHeader: Boolean) {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin.toFloat()
            if (withHeader) headerRow()
        }

        // Шапка
        newPage(false)
        val align = if (heOnly) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        val title = layout(titleOf(heOnly, work) + "\n" + r.site.name, titlePaint, content, Layout.Alignment.ALIGN_NORMAL)
        drawLayout(title, margin.toFloat()); y += title.height + 4
        val metaText = buildString {
            if (r.site.address.isNotEmpty()) append(r.site.address).append('\n')
            if (heOnly) {
                if (work) append("תקופה: ").append(periodText(r)).append("   קריאות: ").append(r.logsCount)
                else append("תאריך: ").append(d(r.to))
                append("   רכזות: ").append(r.panels.size).append('\n')
                append(countsLine(r, true, work))
            } else {
                if (work) append("Период: ").append(periodText(r)).append("   Логов: ").append(r.logsCount)
                else append("Дата: ").append(d(r.to))
                append("   Панелей (רכזות): ").append(r.panels.size).append('\n')
                append(countsLine(r, false, work))
            }
        }
        val meta = layout(metaText, metaPaint, content, Layout.Alignment.ALIGN_NORMAL)
        drawLayout(meta, margin.toFloat()); y += meta.height + 8

        val bottom = pageH - margin - 10
        val panelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(179, 38, 30) }
        // ошибки с фото: номер ссылки → (ошибка, רכזת)
        val photoRefs = ArrayList<Triple<Int, Fault, String>>()
        val photoNo = HashMap<String, Int>()
        for (ps in r.panels) {
        val pTitle = layout(ps.panel.name + (if (ps.panel.detected.isNotEmpty() && ps.panel.detected != ps.panel.name) " · " + ps.panel.detected else "") +
            (if (work) "   (" + periodText2(ps.from, ps.to) + (if (heOnly) ", קריאות: " else ", логов: ") + ps.logsCount + ")" else ""), panelPaint, content, align)
        if (y + pTitle.height + 60 > bottom) newPage(false)
        y += 10
        drawLayout(pTitle, margin.toFloat()); y += pTitle.height + 2
        page!!.canvas.drawLine(margin.toFloat(), y, (pageW - margin).toFloat(), y, line)
        for (s in sections(ps, work)) {
            if (s.items.isEmpty()) continue
            val secTitle = layout(s.he + (if (heOnly) "" else " / " + s.ru) + " (" + s.items.size + ")", secPaint, content, align)
            if (y + secTitle.height + 40 > bottom) newPage(false)
            y += 6
            drawLayout(secTitle, margin.toFloat()); y += secTitle.height + 3
            headerRow()
            s.items.forEachIndexed { i, f ->
                val texts = listOf(
                    (i + 1).toString(),
                    if (f.address.isEmpty()) "—" else f.address + (if (f.zone.isNotEmpty()) "\n" + f.zone else ""),
                    type(f, heOnly),
                    problem(f, heOnly) + tags(f, ps, heOnly).let { if (it.isEmpty()) "" else "\n$it" } +
                        (if (!photos || store.photos(f.id).isEmpty()) "" else {
                            val k = photoNo.getOrPut(f.id) { (photoRefs.size + 1).also { photoRefs.add(Triple(it, f, ps.panel.name)) } }
                            if (heOnly) "\n[תמונה #$k]" else "\n[תמונה / фото #$k]"
                        }),
                    place(f, heOnly).ifEmpty { "—" },
                    done(f, store, heOnly).ifEmpty { "—" }
                )
                val paints = listOf(cellPaint, monoPaint, cellPaint, cellPaint, cellPaint, cellPaint)
                val cells = order.mapIndexed { col, k -> layout(texts[k], paints[k], widths[col]) }
                val h = cells.maxOf { it.height } + 2 * pad
                if (y + h > bottom) newPage(true)
                drawRow(cells, false)
            }
        }
        }
        // Фото — в конце отчёта, по номерам из таблицы
        if (photoRefs.isNotEmpty()) {
            newPage(false)
            val ph = layout(if (heOnly) "תמונות" else "תמונות / Фото", titlePaint, content, align)
            drawLayout(ph, margin.toFloat()); y += ph.height + 8
            val colW = (content - 10) / 2f
            val maxH = 300f
            for ((k, f, pn) in photoRefs) {
                val cap = layout("#$k · " + (if (f.address.isNotEmpty()) f.address + " · " else "") + problem(f, heOnly) +
                    "\n" + pn + (place(f, heOnly).let { if (it.isEmpty()) "" else " · $it" }) +
                    done(f, store, heOnly).let { if (it.isEmpty()) "" else "\n💬 $it" },
                    TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK }, content, align)
                if (y + cap.height + 120 > bottom) newPage(false)
                y += 6
                drawLayout(cap, margin.toFloat()); y += cap.height + 4
                val pics = store.photos(f.id).mapNotNull { loadPhoto(it.path, 700) }
                var col = 0; var rowH = 0f
                for (b in pics) {
                    var w = colW; var h = w * b.height / b.width
                    if (h > maxH) { h = maxH; w = h * b.width / b.height }
                    if (col == 0 && y + h > bottom) newPage(false)
                    val x = if (heOnly) margin + content - (col + 1) * colW - col * 10f + (colW - w) else margin + col * (colW + 10f)
                    page!!.canvas.drawBitmap(b, null, android.graphics.RectF(x, y, x + w, y + h), null)
                    rowH = maxOf(rowH, h)
                    b.recycle()
                    col++
                    if (col == 2) { y += rowH + 8; col = 0; rowH = 0f }
                }
                if (col != 0) y += rowH + 8
            }
        }
        // подписи
        val sign = layout(if (heOnly) "חתימת הטכנאי: ____________        נציג הלקוח: ____________" else "חתימת הטכנאי / Подпись техника: __________     נציג הלקוח / Представитель клиента: __________", metaPaint, content, align)
        if (y + sign.height + 30 > bottom) newPage(false)
        y += 24
        drawLayout(sign, margin.toFloat())
        page?.let { doc.finishPage(it) }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }
}


/** Тревоги и история: список событий (новые сверху), без сравнения и комментариев. */
object RecordExport {
    fun title(kind: String, he: Boolean) = when (kind) {
        "alarms" -> if (he) "אזעקות" else "Пожарные тревоги · אזעקות"
        else -> if (he) "היסטוריה" else "История · היסטוריה"
    }

    private fun stamp(e: PanelDecoder.PanelEvent): Long = try {
        java.text.SimpleDateFormat("d.M.yyyy H:mm:ss", java.util.Locale.US).parse(e.date + " " + e.time)?.time ?: 0L
    } catch (_: Exception) { 0L }

    /** Новые сверху; limit > 0 — только последние N. */
    fun select(events: List<PanelDecoder.PanelEvent>, limit: Int): List<PanelDecoder.PanelEvent> {
        val sorted = events.sortedByDescending { stamp(it) }
        return if (limit > 0) sorted.take(limit) else sorted
    }

    fun block(e: PanelDecoder.PanelEvent, he: Boolean): String {
        val sb = StringBuilder()
        sb.append(e.date).append(' ').append(e.time)
        val ev = e.event.trim()
        if (ev.isNotEmpty()) sb.append(" — ").append(if (he) ev else I18n.panel(ev))
        sb.append('\n')
        if (e.address.isNotEmpty()) {
            sb.append(e.address)
            val t = ReportExport.typeText(e, he)
            if (t.isNotEmpty()) sb.append(" · ").append(t)
            sb.append(" · ")
        }
        sb.append(ReportExport.problemLine(e, he))
        val p = ReportExport.place(e)
        if (p.isNotEmpty()) sb.append('\n').append(p)
        return sb.toString()
    }

    fun text(events: List<PanelDecoder.PanelEvent>, kind: String, site: String, panel: String, he: Boolean, total: Int): String {
        val sb = StringBuilder()
        sb.append("*").append(title(kind, he)).append(" — ").append(site).append(if (panel.isNotEmpty()) " · $panel" else "").append("*\n")
        sb.append(if (he) "אירועים: " else "Событий: ").append(events.size)
        if (total > events.size) sb.append(if (he) " (אחרונים מתוך $total)" else " (последние из $total)")
        sb.append("\n\n")
        events.forEachIndexed { i, e -> sb.append(i + 1).append(". ").append(block(e, he)).append("\n\n") }
        return sb.toString().trimEnd()
    }

    fun pdf(events: List<PanelDecoder.PanelEvent>, kind: String, site: String, panel: String, he: Boolean, total: Int, out: File) {
        val doc = PdfDocument()
        val pageW = 595; val pageH = 842; val margin = 36
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.DKGRAY }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = Color.BLACK }
        val align = if (he) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        val w = pageW - 2 * margin
        fun layout(t: String, p: TextPaint): StaticLayout =
            StaticLayout.Builder.obtain(t, 0, t.length, p, w).setAlignment(align).build()
        var page: PdfDocument.Page? = null; var y = 0f; var no = 0
        fun newPage() {
            page?.let { doc.finishPage(it) }
            no++; page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, no).create()); y = margin.toFloat()
        }
        fun draw(l: StaticLayout) {
            if (y + l.height > pageH - margin) newPage()
            page!!.canvas.save(); page!!.canvas.translate(margin.toFloat(), y); l.draw(page!!.canvas); page!!.canvas.restore()
            y += l.height
        }
        newPage()
        draw(layout(title(kind, he) + "\n" + site + (if (panel.isNotEmpty()) " · $panel" else ""), titlePaint)); y += 4
        val meta = (if (he) "אירועים: " else "Событий: ") + events.size +
            (if (total > events.size) (if (he) " (אחרונים מתוך $total)" else " (последние из $total)") else "") +
            (if (he) "   חדשים למעלה" else "   новые сверху")
        draw(layout(meta, metaPaint)); y += 10
        events.forEachIndexed { i, e ->
            draw(layout("${i + 1}. " + block(e, he), bodyPaint))
            if (y + 1 < pageH - margin) { page!!.canvas.drawLine(margin.toFloat(), y + 4, (pageW - margin).toFloat(), y + 4, metaPaint) }
            y += 9
        }
        page?.let { doc.finishPage(it) }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
