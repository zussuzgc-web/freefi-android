package com.freeturn.app

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.util.Log
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.di.appModule
import com.freeturn.app.domain.proxy.LogFile
import com.freeturn.app.domain.proxy.LogLevel
import com.freeturn.app.domain.proxy.ProxyEngine
import com.freeturn.app.domain.proxy.ProxyStore
import com.freeturn.app.service.ProxyNotifier
import com.freeturn.app.service.ProxyWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    private val appPreferences: AppPreferences by inject()
    private val engine: ProxyEngine by inject()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // Раньше всего: крэш до лога может никак себя не показать - тогда его нечем разбирать.
        installCrashHandler()
        // ed25519/curve25519 работает через Bouncy Castle в classpath. jsch 2.x подхватывает его сам.
        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(appModule)
        }
        // До первой строки: файловый лог - единственное, что переживает перезапуск.
        ProxyStore.attachFile(LogFile(File(filesDir, "logs")))
        // Строка в середине лога сессии = процесс убивали и подняли заново; без неё
        // sticky-рестарт неотличим от обычной работы.
        ProxyStore.log("Процесс запущен")
        // Раз за процесс: в onCreate сервиса эти транзакции доставались главному потоку
        // ровно на нажатии кнопки.
        ProxyNotifier.createChannels(this)
        reportPreviousExit()
        warmUpCore()
        observeWidgetState()
    }

    /**
     * Крэш из корутины, из службы или Compose не пишет в лог - процесс умирает, а разбирать
     * нечего. Хендлер пишет стек синхронно: в лог-файл, дублируя в ProxyStore и logcat, -
     * и лишь затем пропускает дальше (Android сам закроет процесс после нашего возврата).
     *
     * Нативный крэш ядра Kotlin не перехватит - для него при следующем запуске
     * [reportPreviousExit] вытащит [ApplicationExitInfo] с причиной и сигналом.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stack = throwable.stackTraceToString()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date(System.currentTimeMillis()))
            val header = "===== КРЭШ $stamp, поток ${thread.name} | ${throwable.javaClass.name} ====="
            Log.e("FreefyCrash", "$header\n$stack")
            runCatching {
                File(filesDir, "crash.log").appendText("$header\n$stack\n\n")
            }
            runCatching { ProxyStore.log(header, LogLevel.Error) }
            runCatching { ProxyStore.log(stack, LogLevel.Error) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Прошлый процесс не дошёл до штатной остановки - его убили с живой сессией. Без
     * этой строки убийство неотличимо от обычного перезапуска приложения.
     */
    private fun reportPreviousExit() {
        scope.launch {
            if (appPreferences.cleanExitFlow.first()) return@launch
            ProxyStore.log("Прошлый процесс убит с активной сессией", LogLevel.Warning)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Нативный крэш ядра (SIGSEGV и т.п.) не ловится Kotlin-обработчиком - причина
                // смерти процесса видна только отсюда. Причина и сигнал прямо указывают на него.
                reportPreviousExitReason()
            }
        }
    }

    @androidx.annotation.RequiresApi(30)
    private fun reportPreviousExitReason() {
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return
        val exit = am.getHistoricalProcessExitReasons(packageName, 1, 0).firstOrNull() ?: return
        val signal = if (exit.status > 0) " сигнал=${exit.status}" else ""
        ProxyStore.log(
            "Прошлое завершение: ${exit.description}$signal, pid=${exit.pid}",
            LogLevel.Warning
        )
        // Для крэша прикладывается Java-трассировка (или текст "no fatal exception"); нативный
        // SIGSEGV тогда и только тогда, когда его нет, а причина — "Signal ... (SIGSEGV...)".
        val trace = runCatching {
            exit.getTraceInputStream()?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (!trace.isNullOrBlank()) {
            ProxyStore.log("Трассировка прошлого завершения:\n$trace", LogLevel.Error)
        }
    }

    // Первое обращение к ядру грузит нативную библиотеку и поднимает Go-runtime -
    // без прогрева эта задержка достаётся первому нажатию "Запустить".
    private fun warmUpCore() {
        scope.launch(Dispatchers.IO) {
            runCatching { engine.version }
                // Обычно это провал загрузки нативной библиотеки - запуск всё равно
                // упадёт, но уже без внятной причины в логе.
                .onFailure { ProxyStore.log("Ядро не загрузилось: ${it.message}", LogLevel.Error) }
        }
    }

    // Перерисовывает виджет при смене статуса прокси или активного сервера
    // (RemoteViews не реактивны - их надо толкать вручную).
    private fun observeWidgetState() {
        combine(
            ProxyStore.status,
            appPreferences.serversSnapshot
        ) { status, snap ->
            listOf(status.busy, status.phase, status.active, status.total, snap.active?.name)
        }
            .distinctUntilChanged()
            .onEach { ProxyWidgetProvider.refresh(this) }
            .launchIn(scope)
    }
}
