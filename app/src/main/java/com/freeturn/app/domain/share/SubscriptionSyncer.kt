package com.freeturn.app.domain.share

import com.freeturn.app.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Живое обновление срока подписки гостя (сервер без SSH): дёргает statusd
 * по http и пишет актуальный subscriptionExpiresEpoch в DataStore. Триггеры:
 * подключение VPN (ProxyService) и ручной refresh с главного экрана.
 * Хранит срок на диске - он переживает рестарты и не теряется без сети.
 */
class SubscriptionSyncer(
    private val prefs: AppPreferences,
    private val statusd: StatusdClient
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = AtomicBoolean(false)

    /** Fire-and-forget с коалесценцией: частые триггеры дают один запрос. */
    fun syncNow() {
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                refresh()
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * Статус guest-сервера ([serverId] или активный) с персистом нового срока.
     * null - не guest, статус недоступен или запись не найдена. Флаги SSH не
     * читаем: у владельца властный share-list, но и у него statusd безопасен.
     */
    suspend fun refresh(serverId: String? = null): MySubscription? {
        val snap = prefs.serversSnapshot.first()
        val target = serverId?.let { id -> snap.list.firstOrNull { it.id == id } }
            ?: snap.active ?: snap.list.firstOrNull() ?: return null
        val live = statusd.fetch(target)?.takeIf { it.found } ?: return null
        val fresh = live.expiresEpoch ?: return null
        if (fresh != target.client.subscriptionExpiresEpoch) {
            prefs.updateServer(target.id) {
                it.copy(client = it.client.copy(subscriptionExpiresEpoch = fresh))
            }
        }
        return live
    }
}