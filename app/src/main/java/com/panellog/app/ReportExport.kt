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
                sb.append(i + 1).append(". ").append(e.address).append(" — ").append(problemLine(e, he)).append('\n')
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
        val baseWidths = intArrayOf(22, 70, 150, 190, 91) // сумма 523 = 595 - 2*36
        val baseHeaders = if (he) arrayOf("#", "כתובת", "תקלה", "מיקום", "זמן")
            else arrayOf("#", "כתובת\nАдрес", "תקלה\nНеисправность", "מיקום\nМесто", "זמן\nВремя")
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
                e.problem + (if (he) "" else PanelDecoder.ru(e.problem)?.let { "\n$it" } ?: ""),
                if (e.isSystem) (e.node.ifEmpty { if (he) "מערכת" else "Система" }) else place(e).ifEmpty { "—" },
                e.date + "\n" + e.time
            )
            val paints = listOf(cellPaint, monoPaint, cellPaint, cellPaint, cellPaint)
            val order = if (he) listOf(4, 3, 2, 1, 0) else listOf(0, 1, 2, 3, 4)
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
