package com.panellog.app

/**
 * Разбор вывода принтерного порта NFS2-3030.
 *
 * Панель с ивритом печатает в графическом режиме Epson: каждая строка —
 * команда ESC * 0 nL nH и n байт столбцов (8 точек, старший бит сверху).
 * Шрифт фиксированный: 6 столбцов на символ, 80 символов в строке.
 * Распознаём символы точным сравнением 6-байтных рисунков с таблицей.
 * Строки приходят в «визуальном» порядке (как на бумаге), поэтому поля
 * на иврите разворачиваем в логический порядок.
 */
object PanelDecoder {

    /** Рисунок символа (6 байт, HEX) → символ. Собрано по реальной распечатке NFS2-3030. */
    val GLYPHS: Map<String, String> = mapOf(
        "28107C102800" to "*",
        "FC848484FC00" to "ם",
        "000080E00000" to "י",
        "00C448506000" to "ל",
        "84FC0404FC00" to "ע",
        "F4948484FC00" to "פ",
        "00000080FC00" to "ו",
        "00808080FC00" to "ר",
        "DC201028C400" to "א",
        "04FC8080FC00" to "ת",
        "FC848498E000" to "ס",
        "808080FC8000" to "ד",
        "9C808080FC00" to "ה",
        "BC808890E000" to "ק",
        "FC042444FC00" to "ט",
        "FC04FC04FC00" to "ש",
        "0080FC800000" to "ז",
        "FC808080FC00" to "ח",
        "844424548C00" to "צ",
        "00040484FC00" to "נ",
        "8482A2D28C00" to "3",
        "0042FE020000" to "1",
        "80809EA0C000" to "7",
        "868A92A2C200" to "Z",
        "1C2222221C00" to "o",
        "3E1020201E00" to "n",
        "1C2A2A2A1800" to "e",
        "7C8A92A27C00" to "0",
        "003844820000" to "(",
        "00000080FE00" to "ן",
        "008244380000" to ")",
        "609292947800" to "9",
        "006C6C000000" to ":",
        "182848FE0800" to "4",
        "6C9292926C00" to "8",
        "00A0C0000000" to "'",
        "42868A926200" to "2",
        "3C5292920C00" to "6",
        "FE0202020200" to "L",
        "82FE82827C00" to "D",
        "E4A2A2A29C00" to "5",
        "101010101000" to "-",
        "14A44484FC00" to "מ",
        "848484FC0400" to "ב",
        "00848890FC00" to "ג",
        "00848484FC00" to "כ",
        "F0908080FE00" to "ף",
        "00E000E00000" to "\"",
        "000606000000" to ".",
        "402010080400" to "\\",
        "FE403040FE00" to "M",
        "7C8282827C00" to "O",
        "FE60100CFE00" to "N",
        "0082FE820000" to "I",
        "8080FE808000" to "T",
        "FE9098946200" to "R",
        "FC020202FC00" to "U",
        "82FE92926C00" to "B",
        "FE9292929200" to "E",
        "F8040204F800" to "V",
        "7E8888887E00" to "A",
        "E0100E10E000" to "Y",
        "649292924C00" to "S",
        "FE9090908000" to "F",
        "FE041804FE00" to "W",
        "7C8282824400" to "C",
        "FE101010FE00" to "H",
    )

    private const val EMPTY = "000000000000"

    data class Decoded(
        val graphic: Boolean,
        val visualLines: List<String>,
        val unknownGlyphs: Set<String>
    )

    data class PanelEvent(
        val event: String,
        val problem: String,
        val label: String,
        val label2: String,
        val zoneLabel: String,
        val zone: String,
        val devType: String,
        val address: String,
        val node: String,
        val date: String,
        val time: String
    ) {
        val isSystem: Boolean get() = address.isEmpty()
    }

    fun hasGraphics(bytes: ByteArray): Boolean {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i].toInt() == 0x1B && bytes[i + 1].toInt() == '*'.code) return true
        }
        return false
    }

    /** Байты → строки, как они напечатаны на бумаге (визуальный порядок). */
    fun decode(bytes: ByteArray): Decoded {
        if (!hasGraphics(bytes)) {
            return Decoded(false, textLines(bytes), emptySet())
        }
        val lines = ArrayList<String>()
        val unknown = LinkedHashSet<String>()
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x1B && i + 4 < n && (bytes[i + 1].toInt() and 0xFF) == '*'.code) {
                val len = (bytes[i + 3].toInt() and 0xFF) or ((bytes[i + 4].toInt() and 0xFF) shl 8)
                val start = i + 5
                if (start + len > n) break // строка ещё не пришла целиком
                val sb = StringBuilder()
                var c = 0
                while (c + 6 <= len) {
                    val key = hex(bytes, start + c, 6)
                    if (key == EMPTY) sb.append(' ')
                    else {
                        val ch = GLYPHS[key]
                        if (ch != null) sb.append(ch) else { sb.append('□'); unknown.add(key) }
                    }
                    c += 6
                }
                lines.add(sb.toString().trimEnd())
                i = start + len
            } else {
                i++
            }
        }
        return Decoded(true, lines, unknown)
    }

    private fun textLines(bytes: ByteArray): List<String> {
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                c == 0x0A -> sb.append('\n')
                c == 0x0D || c == 0x0C -> {}
                c in 0x20..0x7E -> sb.append(c.toChar())
                else -> sb.append(String.format("[%02X]", c))
            }
        }
        return sb.toString().split('\n').map { it.trimEnd() }
    }

    private fun hex(b: ByteArray, off: Int, len: Int): String {
        val sb = StringBuilder(len * 2)
        for (k in off until off + len) sb.append(String.format("%02X", b[k].toInt() and 0xFF))
        return sb.toString()
    }

    private val HEB = Regex("[\u05D0-\u05EA]")
    private val LTR_RUN = Regex("[A-Za-z0-9][A-Za-z0-9.:/\\\\-]*[A-Za-z0-9]|[A-Za-z0-9]")

    /** Поле в визуальном порядке → логический (как читается). */
    fun logical(field: String): String {
        val f = field.trim()
        if (!HEB.containsMatchIn(f)) return f
        val r = StringBuilder()
        for (ch in f.reversed()) r.append(
            when (ch) { '(' -> ')'; ')' -> '('; else -> ch }
        )
        return LTR_RUN.replace(r.toString()) { it.value.reversed() }
    }

    /** Строки целиком в логическом порядке — для чтения и отчёта. */
    fun logicalLines(visual: List<String>): List<String> = visual.map { line ->
        Regex("\\S+(?: \\S+)*").replace(line) { logical(it.value) }
    }

    private val TIME = Regex("(\\d{1,2}:\\d\\d:\\d\\d)\\s+'(\\S)\\s+(\\d\\d)\\s+(\\d\\d),?\\s+(\\d{4})(?:\\s+((?:N\\d{3})?L\\d{2}[DM]\\d{3}))?")
    private val ZONE = Regex("Z\\d{3}")

    private fun seg(s: String, a: Int, b: Int): String {
        if (a >= s.length) return ""
        return logical(s.substring(a, minOf(b, s.length)))
    }

    /** Название отчёта из строки со звёздочками. */
    fun title(visual: List<String>): String =
        visual.firstOrNull { it.contains("***") }?.let { logical(it.replace('*', ' ')) } ?: ""

    /** Пары строк (описание + строка со временем) → события. */
    fun parse(visual: List<String>): List<PanelEvent> {
        val out = ArrayList<PanelEvent>()
        var a: String? = null
        for (v in visual) {
            if (v.contains("***")) continue
            val m = TIME.find(v)
            if (m == null) { if (v.isNotBlank()) a = v; continue }
            val line1 = a ?: ""
            val addr = m.groupValues[6]
            // На первом листе дата была «месяц день,», здесь «день месяц».
            // Пока считаем «день месяц»; формат уточним по дню недели позже.
            val date = m.groupValues[3] + "." + m.groupValues[4] + "." + m.groupValues[5]
            val time = m.groupValues[1]
            if (addr.isNotEmpty()) {
                out.add(PanelEvent(
                    event = seg(line1, 0, 20),
                    problem = seg(line1, 20, 40),
                    label = seg(line1, 40, 61),
                    label2 = seg(line1, 61, 80),
                    zoneLabel = seg(v, 0, 20),
                    zone = ZONE.find(v.take(30))?.value ?: "",
                    devType = seg(v, 28, 42),
                    address = addr,
                    node = "",
                    date = date, time = time
                ))
            } else {
                out.add(PanelEvent(
                    event = seg(line1, 0, 20),
                    problem = seg(line1, 20, 80),
                    label = "", label2 = "", zoneLabel = "", zone = "", devType = "",
                    address = "",
                    node = seg(v, 0, 40),
                    date = date, time = time
                ))
            }
            a = null
        }
        return out
    }

    /** Короткий перевод частых сообщений панели. */
    val RU: Map<String, String> = mapOf(
        "לא מגיב" to "не отвечает",
        "מעגל פתוח" to "обрыв цепи",
        "מעגל מקוצר" to "короткое замыкание",
        "נחוצה תחזוקה" to "требуется обслуживание (запылён)",
        "אישור תקלה" to "неисправность (квитирована)",
        "MISMAT HDWE TYPE" to "тип устройства не совпадает с программой",
        "NVRAM BATTERY TROUBLE" to "батарея памяти CPU",
        "SOFTWARE MISMATCH" to "несовпадение версий ПО",
        "TROUBLE MON" to "монитор неисправности",
        "MONITOR" to "монитор-модуль",
        "חום" to "тепловой",
        "(עשן) פוטו" to "дымовой оптический",
        "(ION) עשן" to "дымовой ионизационный"
    )

    /** Тип устройства, как он напечатан → понятное название (иврит, русский). */
    private val TYPES: Map<String, Pair<String, String>> = mapOf(
        "(עשן) פוטו" to ("גלאי עשן אופטי" to "дымовой оптический"),
        "עשן (פוטו)" to ("גלאי עשן אופטי" to "дымовой оптический"),
        "(ION) עשן" to ("גלאי עשן יוני" to "дымовой ионизационный"),
        "עשן (ION)" to ("גלאי עשן יוני" to "дымовой ионизационный"),
        "חום" to ("גלאי חום" to "тепловой"),
        "MONITOR" to ("מודול ניטור" to "монитор-модуль"),
        "TROUBLE MON" to ("מודול ניטור תקלה" to "модуль контроля неисправности"),
        "SMOKE CONVEN" to ("מודול גלאים קונבנציונליים" to "модуль неадресных датчиков"),
        "CONTROL" to ("מודול שליטה" to "модуль управления"),
        "RELAY" to ("ממסר" to "реле"),
        "PULL STATION" to ("לחצן אש" to "ручной извещатель")
    )

    fun typeHe(t: String): String = TYPES[t]?.first ?: t
    fun typeRu(t: String): String? = TYPES[t]?.second

    fun ru(s: String): String? {
        RU[s]?.let { return it }
        Regex("תקלת הארקה בעניבה (\\d+)").find(s)?.let { return "замыкание на землю, шлейф " + it.groupValues[1] }
        Regex("תקלה בלוח תצוגה (\\d+)").find(s)?.let { return "неисправность дисплея/повторителя " + it.groupValues[1] }
        return null
    }
}
