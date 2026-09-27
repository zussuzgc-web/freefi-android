package com.freeturn.app.domain.share

import android.content.Context
import com.freeturn.app.data.config.SshConfig
import com.freeturn.app.data.control.AdminLoadData
import com.freeturn.app.data.control.PeerAddData
import com.freeturn.app.data.control.PeerConfData
import com.freeturn.app.data.control.ShareInfoData
import com.freeturn.app.data.control.ShareListData
import com.freeturn.app.data.control.decodeBase64
import com.freeturn.app.data.share.ShareInfo
import com.freeturn.app.data.share.SharedClient
import com.freeturn.app.data.share.SharedClientParser
import com.freeturn.app.data.share.WgPeer
import com.freeturn.app.data.share.WgPeerParser
import com.freeturn.app.domain.server.ServerCommand
import com.freeturn.app.domain.server.ServerCommandException
import com.freeturn.app.domain.server.ServerControl
import com.freeturn.app.domain.server.asUnit
import com.freeturn.app.domain.server.requireData
import com.freeturn.app.domain.ssh.SSHManager
import java.util.Base64

/** Статус подписки своего клиента на сервере (главный экран). */
data class MySubscription(
    val found: Boolean = false,
    /** Epoch-секунды конца подписки; null = бессрочно. */
    val expiresEpoch: Long? = null,
    /** Срок наступил (доступ на сервере может быть уже снят). */
    val expired: Boolean = false,
    /** Свой клиент сейчас в VPN (свежий handshake). */
    val online: Boolean = false
)

class ShareRepository(context: Context, ssh: SSHManager) {

    private val control = ServerControl(context, ssh)

    data class NewPeer(val pubkey: String, val ip: String, val clientConf: String)

    data class PeerAccess(val clientConf: String, val clientId: String)

    companion object {
        /** Окно «онлайн» для своего клиента: handshake младше этого возраста. */
        const val ONLINE_WINDOW_SECONDS = 180L
    }

    suspend fun shareInfo(cfg: SshConfig): Result<ShareInfo> =
        control.run(cfg, ServerCommand.ShareInfo)
            .requireData<ShareInfoData>()
            .map { ShareInfo(it.mode, it.obfProfile, it.obfKey, it.wgBackend, it.statusPort) }

    suspend fun addPeer(
        cfg: SshConfig,
        name: String,
        endpoint: String,
        clientId: String,
        expireAtUnix: Long = 0L
    ): Result<NewPeer> =
        control.run(cfg, ServerCommand.PeerAdd(nameB64(name), endpoint, clientId, expireAtUnix))
            .requireData<PeerAddData>()
            .mapCatching { d ->
                val conf = decodeBase64(d.clientConfB64)
                if (d.peer.pub.isBlank() || conf.isNullOrBlank()) {
                    throw ServerCommandException("server returned no peer config")
                }
                NewPeer(d.peer.pub, d.peer.ip, conf)
            }

    suspend fun listShared(cfg: SshConfig): Result<Pair<List<WgPeer>, List<SharedClient>>> =
        control.run(cfg, ServerCommand.ShareList)
            .requireData<ShareListData>()
            .map { WgPeerParser.from(it) to SharedClientParser.from(it) }

    /** Статус подписки своего клиента ([clientId]) на сервере — для главного экрана. */
    suspend fun mySubscription(cfg: SshConfig, clientId: String): Result<MySubscription> =
        listShared(cfg).map { (peers, clients) ->
            val peer = peers.firstOrNull { it.cid == clientId }
            if (peer != null) {
                MySubscription(
                    found = true,
                    expiresEpoch = peer.expiresEpoch,
                    expired = peer.expired,
                    online = peer.lastHandshakeEpoch
                        ?.let { hs -> (System.currentTimeMillis() / 1000L) - hs < ONLINE_WINDOW_SECONDS } == true
                )
            } else {
                val cl = clients.firstOrNull { it.clientId == clientId }
                if (cl != null) {
                    MySubscription(found = true, expiresEpoch = cl.expiresEpoch, expired = cl.expired)
                } else {
                    MySubscription(found = false)
                }
            }
        }

    /**
     * Conf пира + его cid. [candidateClientId]/[name] - backfill: пир без
     * cid-маппинга (выдан до allowlist) регистрируется этим cid на сервере.
     */
    suspend fun peerConf(
        cfg: SshConfig,
        pubkey: String,
        candidateClientId: String,
        name: String,
        expireAtUnix: Long = 0L
    ): Result<PeerAccess> =
        control.run(cfg, ServerCommand.PeerConf(pubkey, candidateClientId, nameB64(name), expireAtUnix))
            .requireData<PeerConfData>()
            .mapCatching { d ->
                val conf = decodeBase64(d.clientConfB64)
                    ?: throw ServerCommandException("server returned no peer config")
                PeerAccess(conf, d.clientId)
            }

    suspend fun removePeer(cfg: SshConfig, pubkey: String): Result<Unit> =
        control.run(cfg, ServerCommand.PeerRemove(pubkey)).asUnit()

    suspend fun addClient(
        cfg: SshConfig,
        name: String,
        clientId: String,
        expireAtUnix: Long = 0L
    ): Result<Unit> =
        control.run(cfg, ServerCommand.ClientAdd(nameB64(name), clientId, expireAtUnix)).asUnit()

    suspend fun removeClient(cfg: SshConfig, clientId: String): Result<Unit> =
        control.run(cfg, ServerCommand.ClientRemove(clientId)).asUnit()

    /**
     * «Сброс сессии»: разрыв устройства-держателя клиента на сервере (ядро роняет
     * активную сессию) и разблокировка для повторного входа. Peer/conf/cid и срок
     * подписки сохраняются; следующий коннект любого устройства принимается заново.
     */
    suspend fun bounceClient(
        cfg: SshConfig,
        name: String,
        clientId: String,
        expireAtUnix: Long = 0L
    ): Result<Unit> =
        control.run(cfg, ServerCommand.ClientBounce(nameB64(name), clientId, expireAtUnix)).asUnit()

    /** Продление срока жизни client_id на сервере (идемпотентно). */
    suspend fun setExpiry(cfg: SshConfig, clientId: String, expireAtUnix: Long): Result<Unit> =
        control.run(cfg, ServerCommand.SetExpiry(clientId, expireAtUnix)).asUnit()

    /** Ленивый автоотзыв истёкших (вызывается при необходимости). */
    suspend fun sweep(cfg: SshConfig): Result<Unit> =
        control.run(cfg, ServerCommand.Sweep).asUnit()

    /** Установка systemd timer автоотзыва (нужна задеплоенная копия control.sh). */
    suspend fun sweepInstall(cfg: SshConfig): Result<Unit> =
        control.run(cfg, ServerCommand.SweepInstall).asUnit()

    /** Пишет зеркало списка клиентов панели админа на сервер (best-effort, не блокирует панель). */
    suspend fun adminSave(cfg: SshConfig, clientsJson: String): Result<Unit> =
        control.run(cfg, ServerCommand.AdminSave(adminB64(clientsJson))).asUnit()

    /** Тянет зеркало списка клиентов с сервера; null = файла нет / не читается. */
    suspend fun adminLoad(cfg: SshConfig): Result<String?> =
        control.run(cfg, ServerCommand.AdminLoad)
            .requireData<AdminLoadData>()
            .map { decodeBase64(it.adminClientsB64) }

    /** Пишет локальную копию control.sh на сервер (для systemd timer). */
    suspend fun deployControlScript(cfg: SshConfig): Result<Unit> =
        control.deployControlScript(cfg).asUnit()

    // До 64 символов: влезает в b64-лимит скрипта и не раздувает маркер в conf.
    private fun nameB64(name: String): String = Base64.getEncoder()
        .encodeToString(name.trim().take(64).toByteArray(Charsets.UTF_8))

    private fun adminB64(json: String): String = Base64.getEncoder()
        .encodeToString(json.toByteArray(Charsets.UTF_8))
}
