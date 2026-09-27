package com.freeturn.app.domain.admin

/**
 * Детектор обрыва флотилии. Чистая логика без Android: решение по одному опросу
 * сервера, чтобы её можно было покрыть юнит-тестами. Одиночный клиент офлайн -
 * это норма, а не инцидент: алерт включается только когда есть кому обрываться и
 * не онлайн никого дольше порога.
 */
object FleetOutage {

    /** Одиночный клиент офлайн не считается обрывом флотилии. */
    const val MIN_KNOWN_CLIENTS = 2

    /** «Все офлайн» дольше порога - это уже сигнал, а не штатная тишина. */
    const val OUTAGE_SECONDS = 180L

    /** Результат одного опроса. */
    data class State(
        val outage: Boolean,
        /** Сколько секунд длится обрыв, 0 - обрыва нет. */
        val outSinceSec: Long,
        /** Epoch первого наблюдения «все офлайн»; null - флот не в обрыве. */
        val downSinceEpoch: Long?
    )

    /**
     * @param knownClients сколько клиентов известно (есть кому обрываться)
     * @param onlineClients сколько из них онлайн прямо сейчас
     * @param nowSec текущее время в epoch-секундах
     * @param downSinceEpoch прошлое наблюдение «все офлайн», null - его не было
     */
    fun check(knownClients: Int, onlineClients: Int, nowSec: Long, downSinceEpoch: Long?): State {
        val fleetDown = knownClients >= MIN_KNOWN_CLIENTS && onlineClients == 0
        val downSince = if (fleetDown) downSinceEpoch ?: nowSec else null
        val outage = downSince != null && nowSec - downSince >= OUTAGE_SECONDS
        return State(
            outage = outage,
            outSinceSec = if (downSince != null) nowSec - downSince else 0L,
            downSinceEpoch = downSince
        )
    }
}