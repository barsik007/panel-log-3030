package com.panellog.app

import android.content.Context

/**
 * Автоперевод отключён (Google ML Kit убран — лишний вес приложения).
 * Переводятся только известные сообщения панели и типы датчиков (словарь в PanelDecoder);
 * метки мест остаются на иврите. Интерфейс оставлен, чтобы позже можно было подключить
 * свой словарь или перевод через сервер.
 */
object AutoTr {
    var onUpdate: (() -> Unit)? = null
    fun init(ctx: Context) {}
    fun get(text: String): String? = null
    fun request(texts: Collection<String>) {}
    fun withRu(text: String): String = text
}
