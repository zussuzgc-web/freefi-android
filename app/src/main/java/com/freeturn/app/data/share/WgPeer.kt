package com.freeturn.app.data.share

import com.freeturn.app.data.control.ShareListData
import java.util.Base64

/** WG-пир сервера из subcommand `share-list`. */
data class WgPeer(
    val pubkey: String,
    /** Имя из маркера ft-user. Пусто - пир без маркера (старые установки, ручные правки). */
    val name: String,
    val ip: String,
    /** Реальный публичный endpoint (адрес:порт последнего handshake), если известен. */
    val endpoint: String? = null,
    /** Epoch-секунды последнего handshake. null - ни разу / интерфейс не поднят. */
    val lastHandshakeEpoch: Long?,
    /** На сервере сохранён клиентский conf - можно выдать ссылку повторно. */
    val hasStoredConf: Boolean,
    /** Пир самого владельца (wireguard-client.conf мастера) - не отзываем. */
    val isSelf: Boolean,
    /** Ссылка уже была импортирована другим устройством. */
    val consumed: Boolean = false,
    /** Epoch-секунды до конца подписки; null/0 - бессрочно. */
    val expiresEpoch: Long? = null,
    /** Срок наступил (отзыв на сервере уже сделан/в очереди). */
    val expired: Boolean = false,
    /** client_id пира (.cid-маппинг). */
    val cid: String? = null,
    /** Epoch-секунды создания пира (mtime .cid/conf на сервере). */
    val createdEpoch: Long? = null,
    /** Epoch-секунды начала текущей сессии (серверный трекинг). null = нет данных. */
    val sessionStartEpoch: Long? = null
)

/** Маппинг `share-list` data ([ShareListData]) в доменные [WgPeer]. */
object WgPeerParser {

    fun from(data: ShareListData): List<WgPeer> {
        val selfPub = data.selfPub
        return data.peers.mapNotNull { p ->
            val pub = p.pub.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WgPeer(
                pubkey = pub,
                name = decodeNameB64(p.nameB64),
                ip = p.ip,
                endpoint = p.endpoint?.takeIf { it.isNotBlank() },
                lastHandshakeEpoch = p.hs?.takeIf { it > 0 },
                hasStoredConf = p.hasConf,
                isSelf = selfPub.isNotEmpty() && pub == selfPub,
                consumed = p.consumed,
                expiresEpoch = p.expires?.takeIf { it > 0 },
                expired = p.expired,
                cid = p.cid,
                createdEpoch = p.created?.takeIf { it > 0 },
                sessionStartEpoch = p.sessionStartEpoch?.takeIf { it > 0 }
            )
        }
    }
}

/** base64(UTF-8) -> имя; битое значение -> пусто. Общий хелпер парсеров share-вывода. */
internal fun decodeNameB64(b64: String?): String {
    if (b64.isNullOrBlank()) return ""
    return try {
        String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        ""
    }
}
