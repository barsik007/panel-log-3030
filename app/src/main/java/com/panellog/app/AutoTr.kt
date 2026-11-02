package com.panellog.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Автоперевод меток мест и незнакомых сообщений с иврита на русский.
 * Google ML Kit на телефоне: модель скачивается один раз (~30 МБ), дальше без интернета.
 * Каждый перевод сохраняется, поэтому одна и та же метка переводится один раз.
 */
object AutoTr {
    private var prefs: SharedPreferences? = null
    private var translator: Translator? = null
    private var ready = false
    private var downloading = false
    private val pending = LinkedHashSet<String>()
    private val inFlight = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val notify = Runnable { onUpdate?.invoke() }
    private val HEB = Regex("[\\u05D0-\\u05EA]")

    /** Вызывается (с задержкой, пачкой), когда появились новые переводы. */
    var onUpdate: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("translations", Context.MODE_PRIVATE)
    }

    /** Готовый перевод или null (тогда можно вызвать request). */
    fun get(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || !HEB.containsMatchIn(t)) return null
        return prefs?.getString(t, null)
    }

    fun request(texts: Collection<String>) {
        if (prefs == null) return
        for (x in texts) {
            val t = x.trim()
            if (t.isEmpty() || !HEB.containsMatchIn(t)) continue
            if (prefs?.contains(t) == true || t in inFlight) continue
            pending.add(t)
        }
        if (pending.isEmpty()) return
        val tr = translator ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.HEBREW)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
        ).also { translator = it }
        if (ready) { drain(); return }
        if (downloading) return
        downloading = true
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { downloading = false; ready = true; drain() }
            .addOnFailureListener { downloading = false }
    }

    private fun drain() {
        val tr = translator ?: return
        val batch = pending.toList()
        pending.clear()
        for (t in batch) {
            inFlight.add(t)
            tr.translate(t)
                .addOnSuccessListener { res ->
                    inFlight.remove(t)
                    prefs?.edit()?.putString(t, res)?.apply()
                    handler.removeCallbacks(notify)
                    handler.postDelayed(notify, 300)
                }
                .addOnFailureListener { inFlight.remove(t) }
        }
    }

    /** Текст + перевод в скобках, если перевод уже есть. */
    fun withRu(text: String): String {
        val r = get(text) ?: return text
        return "$text ($r)"
    }
}
