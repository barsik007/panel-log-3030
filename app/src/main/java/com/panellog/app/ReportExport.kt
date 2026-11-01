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

    private fun reportDate(events: List<PanelDecoder.PanelEvent>): String =
        events.maxByOrNull { it.date + it.time }?.date
            ?: SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())

    private fun problemLine(e: PanelDecoder.PanelEvent, he: Boolean): String {
        if (he) return e.problem
        val ru = PanelDecoder.ru(e.problem)
        return if (ru != null) "${e.problem} ($ru)" else e.problem
    }

    private fun typeText(e: PanelDecoder.PanelEvent, he: Boolean): String {
        if (e.devType.isEmpty()) return ""
        val h = PanelDecoder.typeHe(e.devType)
        val r = PanelDecoder.typeRu(e.devType)
        return if (he || r == null) h else "$h ($r)"
    }

    private fun place(e: PanelDecoder.PanelEvent): String =
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
    fun writePdf(events: List<PanelDecoder.PanelEvent>, out: File, siteIn: String, he: Boolean) {
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

        newPage()
        events.forEachIndexed { i, e ->
            val texts = listOf(
                (i + 1).toString(),
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

    private fun d(ts: Long) = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(ts))
    private fun dt(ts: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(ts))

    private fun periodText(r: PeriodReport) =
        if (d(r.from) == d(r.to)) d(r.to) else d(r.from) + " – " + d(r.to)

    private data class Sec(val he: String, val ru: String, val items: List<Fault>)

    private fun sections(r: PeriodReport) = listOf(
        Sec("טעון אישור לתשלום", "Требует согласования оплаты", r.billable),
        Sec("תוקן", "Устранено", r.fixed),
        Sec("חדש", "Новые", r.added),
        Sec("נשאר פתוח", "Осталось", r.remaining)
    )

    private fun problem(f: Fault, heOnly: Boolean): String {
        if (heOnly) return f.problem
        val ru = PanelDecoder.ru(f.problem) ?: return f.problem
        return "${f.problem} ($ru)"
    }

    private fun type(f: Fault, heOnly: Boolean): String {
        if (f.isSystem) return if (heOnly) "מערכת" else "מערכת (система)"
        val h = PanelDecoder.typeHe(f.devType)
        val r = PanelDecoder.typeRu(f.devType)
        return if (heOnly || r == null) h else "$h ($r)"
    }

    private fun place(f: Fault): String =
        if (f.isSystem) f.node else listOf(f.label, f.label2).filter { it.isNotEmpty() }.joinToString(" · ")

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

    fun whatsapp(r: PeriodReport, store: Store, heOnly: Boolean): String {
        val sb = StringBuilder()
        sb.append("*").append(r.site.name).append("*\n")
        sb.append(if (heOnly) "דוח תקלות רכזת · " else "דוח תקלות רכזת · Отчёт · ").append(periodText(r)).append("\n")
        val counts = if (heOnly)
            "לתשלום ${r.billable.size} · תוקנו ${r.fixed.size} · חדשות ${r.added.size} · נשארו ${r.remaining.size}"
        else "К оплате ${r.billable.size} · устранено ${r.fixed.size} · новых ${r.added.size} · осталось ${r.remaining.size}"
        sb.append(counts).append("\n")
        for (s in sections(r)) {
            if (s.items.isEmpty()) continue
            sb.append("\n*").append(s.he).append(if (heOnly) "" else " / " + s.ru).append(" (").append(s.items.size).append("):*\n")
            s.items.forEachIndexed { i, f ->
                sb.append(i + 1).append(". ")
                if (!f.isSystem) sb.append(f.address).append(" · ")
                sb.append(type(f, heOnly)).append(" — ").append(problem(f, heOnly)).append('\n')
                val p = place(f)
                if (p.isNotEmpty()) sb.append("    ").append(p).append('\n')
                val dn = done(f, store, heOnly)
                if (dn.isNotEmpty()) sb.append("    ✔ ").append(dn).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    fun pdf(r: PeriodReport, store: Store, heOnly: Boolean, out: File) {
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
        val headersHe = arrayOf("#", "כתובת", "סוג", "תקלה", "מיקום", "בוצע / הערה")
        val headersRu = arrayOf("#", "כתובת\nАдрес", "סוג\nТип", "תקלה\nНеисправность", "מיקום\nМесто", "בוצע / הערה\nСделано / комментарий")
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
        val title = layout((if (heOnly) "דוח תקלות רכזת" else "דוח תקלות רכזת · Отчёт о неисправностях") + "\n" + r.site.name, titlePaint, content, Layout.Alignment.ALIGN_NORMAL)
        drawLayout(title, margin.toFloat()); y += title.height + 4
        val metaText = buildString {
            if (r.site.address.isNotEmpty()) append(r.site.address).append('\n')
            if (heOnly) {
                append("תקופה: ").append(periodText(r)).append("   קריאות: ").append(r.logsCount)
                append("   רכזת: NFS2-3030").append(if (r.site.panel.isNotEmpty()) " · " + r.site.panel else "").append('\n')
                append("לתשלום ${r.billable.size} · תוקנו ${r.fixed.size} · חדשות ${r.added.size} · נשארו ${r.remaining.size}")
            } else {
                append("Период: ").append(periodText(r)).append("   Логов: ").append(r.logsCount)
                append("   Панель: NFS2-3030").append(if (r.site.panel.isNotEmpty()) " · " + r.site.panel else "").append('\n')
                append("К оплате ${r.billable.size} · устранено ${r.fixed.size} · новых ${r.added.size} · осталось ${r.remaining.size}")
            }
        }
        val meta = layout(metaText, metaPaint, content, Layout.Alignment.ALIGN_NORMAL)
        drawLayout(meta, margin.toFloat()); y += meta.height + 8

        val bottom = pageH - margin - 10
        for (s in sections(r)) {
            if (s.items.isEmpty()) continue
            val secTitle = layout(s.he + (if (heOnly) "" else " / " + s.ru) + " (" + s.items.size + ")", secPaint, content, align)
            if (y + secTitle.height + 40 > bottom) newPage(false)
            y += 6
            drawLayout(secTitle, margin.toFloat()); y += secTitle.height + 3
            headerRow()
            s.items.forEachIndexed { i, f ->
                val texts = listOf(
                    (i + 1).toString(),
                    if (f.isSystem) "—" else f.address + (if (f.zone.isNotEmpty()) "\n" + f.zone else ""),
                    type(f, heOnly),
                    problem(f, heOnly) + (if (f.recur > 0) "\n🔁 ×" + f.recur else ""),
                    place(f).ifEmpty { "—" },
                    done(f, store, heOnly).ifEmpty { "" }
                )
                val paints = listOf(cellPaint, monoPaint, cellPaint, cellPaint, cellPaint, cellPaint)
                val cells = order.mapIndexed { col, k -> layout(texts[k], paints[k], widths[col]) }
                val h = cells.maxOf { it.height } + 2 * pad
                if (y + h > bottom) newPage(true)
                drawRow(cells, false)
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
