package com.freeturn.app.domain.proxy

/**
 * Решение watchdog'а живости туннеля. Чистая логика без Android/ядра, чтобы её
 * можно было покрыть юнит-тестами.
 *
 * Сигнал - свежесть handshake: у здорового WG re-key обновляет его каждые ~2 мин
 * даже при полном бездействии пользователя (keepalive 25 c), поэтому протухший
 * handshake говорит о мёртвом канале (CGNAT заспал NAT-mapping и т.п.), а не о
 * просто тихом. Сначала лёгкий пинок, дольше порога - полный перезапуск сессии.
 */
object TunnelWatchdog {

    /** Handshake старше порога - туннель под подозрением: нужен лёгкий пинок. */
    const val STALE_SOFT_SEC = 240L

    /** Handshake старше порога после пинка - полный перезапуск сессии. */
    const val STALE_HARD_SEC = 480L

    sealed interface Action {
        /** Всё свежо или дел нет - просто продолжаем тикать. */
        data object Healthy : Action
        /** Протух впервые - пинок ядру (пересоздать аллокации). */
        data object Kick : Action
        /** Пинок был, не помог - полный перезапуск сессии. */
        data object Restart : Action
        /** Туннеля нет / первый handshake ещё не случился - ждём. */
        data object Waiting : Action
    }

    data class Decision(val action: Action, val kickPending: Boolean)

    /**
     * @param handshakeAgeSec сек с последнего handshake; -1 - не было (или туннеля нет)
     * @param kickPending был ли уже лёгкий пинок на текущем цикле протухания
     */
    fun decide(up: Boolean, handshakeAgeSec: Long, kickPending: Boolean): Decision {
        if (!up || handshakeAgeSec < 0) return Decision(Action.Waiting, false)
        return when {
            handshakeAgeSec < STALE_SOFT_SEC -> Decision(Action.Healthy, false)
            handshakeAgeSec < STALE_HARD_SEC ->
                if (kickPending) Decision(Action.Healthy, true) else Decision(Action.Kick, true)
            else -> Decision(Action.Restart, false)
        }
    }
}