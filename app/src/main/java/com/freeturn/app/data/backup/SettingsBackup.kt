package com.freeturn.app.data.backup

import com.freeturn.app.data.config.ClientId
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson
import org.json.JSONArray
import org.json.JSONObject

/** Содержимое бэкапа: профили + активный + личность устройства + интерфейсные тоггл-настройки. */
data class BackupData(
    val servers: List<Server>,
    val activeId: String?,
    // Постоянный client-id устройства (allowlist на сервере). Без него restore на новом
    // девайсе даёт свежий cid -> сервер отклоняет подключение, поэтому поле обязательное.
    val ownClientId: String,
    val dynamicTheme: Boolean,
    val accentColor: Int,
    val backgroundColor: Int,
    val nerdMode: Boolean,
    val privacyMode: Boolean,
    val seasonalDecor: Boolean,
    val autoConnect: Boolean,
    val hotspotProxy: Boolean,
    val suppressUpdatePrompt: Boolean,
    val suppressTgPrompt: Boolean
)

/** Сериализация [BackupData] в JSON (серверы - через тот же [ServerJson], что и в DataStore). */
object SettingsBackup {
    private const val FORMAT_VERSION = 6

    fun encode(data: BackupData): String = JSONObject().apply {
        put("v", FORMAT_VERSION)
        put("servers", JSONArray(ServerJson.encodeList(data.servers)))
        data.activeId?.let { put("activeId", it) }
        put("ownClientId", data.ownClientId)
        put("dynamicTheme", data.dynamicTheme)
        put("accentColor", data.accentColor)
        put("backgroundColor", data.backgroundColor)
        put("nerdMode", data.nerdMode)
        put("privacyMode", data.privacyMode)
        put("seasonalDecor", data.seasonalDecor)
        put("autoConnect", data.autoConnect)
        put("hotspotProxy", data.hotspotProxy)
        put("suppressUpdatePrompt", data.suppressUpdatePrompt)
        put("suppressTgPrompt", data.suppressTgPrompt)
    }.toString()

    fun decode(json: String): BackupData {
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            throw BackupCrypto.FormatException("bad payload")
        }
        // Битый список валим здесь же, до затирания профиля: восстановить "0 серверов"
        // на пустом месте можно только потерей данных. Старые бэкапы без ключа - пустой
        // список, как и раньше.
        val servers = o.optJSONArray("servers")?.let {
            ServerJson.decodeListOrNull(it.toString()) ?: throw BackupCrypto.FormatException("bad servers")
        } ?: emptyList()
        // Битый/отсутствующий cid валим здесь, до затирания профиля: применить такой бэкап -
        // значит подменить личность на чужую и получить обрыв на allowlist.
        val cid = o.optString("ownClientId")
        if (!ClientId.isValid(cid)) throw BackupCrypto.FormatException("bad client id")
        return BackupData(
            servers = servers,
            activeId = o.optString("activeId").takeIf { it.isNotBlank() },
            ownClientId = cid,
            dynamicTheme = o.optBoolean("dynamicTheme", true),
            accentColor = o.optInt("accentColor", 0),
            backgroundColor = o.optInt("backgroundColor", 0),
            nerdMode = o.optBoolean("nerdMode", true),
            privacyMode = o.optBoolean("privacyMode", false),
            seasonalDecor = o.optBoolean("seasonalDecor", true),
            autoConnect = o.optBoolean("autoConnect", false),
            hotspotProxy = o.optBoolean("hotspotProxy", false),
            suppressUpdatePrompt = o.optBoolean("suppressUpdatePrompt", false),
            suppressTgPrompt = o.optBoolean("suppressTgPrompt", false)
        )
    }
}
