package com.freeturn.app.data.share

/**
 * Фактические параметры запущенного сервера (`share-info`).
 * Используется вместо локального [com.freeturn.app.data.server.ServerOpts] для точности.
 */
data class ShareInfo(
    /** Режим проброса живого сервера: "udp" | "tcp". */
    val mode: String = "",
    /** Пусто = сервер не запускался из приложения; при живых args хотя бы "none". */
    val obfProfile: String = "",
    val obfKey: String = "",
    /** Есть WG-conf в /etc/wireguard -> шарим VPN-доступ (peer-add). Иначе - прокси. */
    val wgBackend: Boolean = false,
    /** TCP-порт HTTP-statusd на сервере; 0 = нет (стакан решает только по ссылке). */
    val statusPort: Int = 0
) {
    /** run.args найден - серверным значениям можно верить. */
    val hasRunArgs: Boolean get() = obfProfile.isNotEmpty()
}
