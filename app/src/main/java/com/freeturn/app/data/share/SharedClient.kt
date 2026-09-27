package com.freeturn.app.data.share

import com.freeturn.app.data.control.ShareListData

/** Гость из allowlist без WG-пира (`share-list` clients): свой бэкенд на стороне гостя. */
data class SharedClient(
    val clientId: String,
    /** Имя = comment в clients.json. */
    val name: String,
    /** Ссылка уже была импортирована другим устройством. */
    val consumed: Boolean = false,
    /** Epoch-секунды до конца подписки; null/0 - бессрочно. */
    val expiresEpoch: Long? = null,
    /** Срок наступил (отзыв на сервере уже сделан/в очереди). */
    val expired: Boolean = false
)

/** Маппинг `share-list` data ([ShareListData]) в доменные [SharedClient]. */
object SharedClientParser {

    fun from(data: ShareListData): List<SharedClient> =
        data.clients.mapNotNull { c ->
            val id = c.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SharedClient(
                clientId = id,
                name = decodeNameB64(c.nameB64),
                consumed = c.consumed,
                expiresEpoch = c.expires?.takeIf { it > 0 },
                expired = c.expired
            )
        }
}
