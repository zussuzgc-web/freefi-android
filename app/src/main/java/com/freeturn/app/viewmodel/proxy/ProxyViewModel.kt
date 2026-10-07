package com.freeturn.app.viewmodel.proxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.proxy.LogEntry
import com.freeturn.app.domain.proxy.ProxyEngine
import com.freeturn.app.domain.proxy.ProxyPhase
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import com.freeturn.app.domain.proxy.ProxyStatus
import com.freeturn.app.domain.proxy.ProxyStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProxyViewModel(
    private val launcher: ProxyServiceLauncher,
    private val prefs: AppPreferences,
    private val engine: ProxyEngine
) : ViewModel() {

    val status: StateFlow<ProxyStatus> = ProxyStore.status
    val logs: StateFlow<List<LogEntry>> = ProxyStore.logs

    fun start() = launcher.start()

    fun stop() = launcher.stop()

    /** Окно видно - только на это время ядро опрашивают на метрики. */
    fun setMetricsVisible(visible: Boolean) = engine.setMetricsEnabled(visible)

    /**
     * Возврат приложения на передний план. Живую сессию не трогаем - открытие окна
     * не событие сети, а пинок ядру рециклил бы рабочие аллокации. Мёртвую поднимаем
     * либо с явного согласия ([AppPreferences.autoConnectFlow]), либо когда она упала
     * сама (фаза Error при живом намерении [AppPreferences.proxyDesiredFlow]) -
     * намерение переживает смерть процесса и перезагрузку, поэтому без различий
     * открытие приложения поднимало VPN само и отбирало tun у чужого.
     *
     * [vpnConsent] - проверка согласия на VpnService, зовётся последней: сам
     * `VpnService.prepare()` отзывает разрешение у активного чужого VPN. Без согласия
     * в WG-режиме стартовать нечем, а спрашивать молча, без действия пользователя, нельзя.
     */
    fun onForeground(vpnConsent: () -> Boolean) {
        val phase = ProxyStore.status.value.phase
        // Ошибка своей сессии (обрыв сети, исчерпанные автоповторы) - не чужой
        // туннель: намерение живо, пока пользователь сам не выключил прокси, а чужой
        // VPN у нас отобрал бы согласие и снёс это намерение через onRevoke. Такой
        // сценарий поднимаем без autoConnect - иначе каждый обрыв превращался бы в
        // ручное включение.
        if (phase != ProxyPhase.Idle && phase != ProxyPhase.Error) return
        viewModelScope.launch {
            if (!prefs.proxyDesiredFlow.first()) return@launch
            if (phase == ProxyPhase.Idle && !prefs.autoConnectFlow.first()) return@launch
            if (prefs.clientConfigFlow.first().wireGuardActive && !vpnConsent()) return@launch
            launcher.start()
        }
    }

    /** Окно закрыл пользователь; ядро ждёт решения дальше и выдаст новую капчу. */
    fun dismissCaptcha() = ProxyStore.setCaptcha("")

    /** С экрана чистим и файл: сохранять историю против воли пользователя незачем. */
    fun clearLogs() = ProxyStore.clearLogFile()

    /** false - отправлять нечего. */
    suspend fun exportLogs(target: java.io.File): Boolean = ProxyStore.exportLogFile(target)
}
