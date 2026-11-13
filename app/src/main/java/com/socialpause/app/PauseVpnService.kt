package com.socialpause.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream

private const val CHANNEL = "pause_session"
private const val NOTIFICATION_ID = 42
const val ACTION_START = "com.socialpause.app.START"
const val ACTION_STOP = "com.socialpause.app.STOP"
const val EXTRA_ID = "session_id"
const val EXTRA_PACKAGES = "packages"
const val EXTRA_MINUTES = "minutes"

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        // Never create a service merely because an old notification was tapped.
        if (intent.action != ACTION_STOP || SessionRuntime.state.value?.id != id) return
        context.startService(Intent(context, PauseVpnService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id))
    }
}

/** Android adapter: routes system events into [SessionController] and provides its platform collaborators. */
@OptIn(ExperimentalCoroutinesApi::class)
class PauseVpnService : VpnService() {
    // One thread runs every controller command, so commands never interleave.
    private val sessionThread = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + sessionThread)
    private var descriptor: ParcelFileDescriptor? = null
    private var reader: Job? = null
    private var timer: Job? = null
    private var packageWatch: Job? = null
    @Volatile private var sessionStartId = 0
    @Volatile private var latestStartId = 0
    @Volatile private var newestStartRequest = 0
    private lateinit var store: SessionStore
    private lateinit var controller: SessionController

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        controller = SessionController(
            tunnel = { packages -> establishTunnel(packages) },
            clock = object : SessionClock {
                override fun elapsedRealtime() = SystemClock.elapsedRealtime()
                override fun wallTime() = System.currentTimeMillis()
            },
            scheduler = object : ExpiryScheduler {
                override fun schedule(id: String, generation: Long, elapsedDeadline: Long) {
                    timer = scope.launch {
                        while (true) {
                            val remaining = elapsedDeadline - SystemClock.elapsedRealtime()
                            if (remaining <= 0) break
                            delay(remaining)
                        }
                        controller.expire(generation)
                    }
                }
                override fun cancel() { timer?.cancel(); timer = null }
            },
            store = store,
            host = object : SessionHost {
                override fun onActive(state: RuntimeState, generation: Long) = watch(state, generation)
                override fun onEnded() = stopIfCurrent()
            },
            isInstalled = { pkg -> runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess },
            hasConsent = { prepare(this) == null },
            ownPackage = packageName,
        )
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        val action = intent.action
        if (action == ACTION_START) {
            newestStartRequest = startId
            val id = intent.getStringExtra(EXTRA_ID) ?: ""
            val packages = intent.getStringArrayListExtra(EXTRA_PACKAGES)?.toSet() ?: emptySet()
            val minutes = intent.getIntExtra(EXTRA_MINUTES, -2)
            // Promote immediately, before performing storage or package queries.
            try { promote(notification(id, getString(R.string.status_starting), null)) }
            catch (_: Exception) {
                val failed = RuntimeState(id, SessionStatus.ERROR, packages, System.currentTimeMillis(), null, null, "foreground")
                store.write(failed); SessionRuntime.publish(failed); stopSelf(); return START_NOT_STICKY
            }
            scope.launch {
                sessionStartId = startId
                controller.start(id, packages, minutes)
            }
        } else if (action == ACTION_STOP) {
            val id = intent.getStringExtra(EXTRA_ID)
            scope.launch { controller.stop(id) }
        } else {
            // System restart/always-on is deliberately unsupported: no silent restart.
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun promote(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun establishTunnel(packages: Set<String>): TunnelHandle {
        val builder = Builder().setSession(getString(R.string.app_name)).setBlocking(true)
            .addAddress("10.111.0.1", 32).addRoute("0.0.0.0", 0)
            .addAddress("fd00:111::1", 128).addRoute("::", 0)
        packages.forEach { builder.addAllowedApplication(it) }
        val fd = builder.establish() ?: error("VPN establishment failed")
        descriptor = fd
        return TunnelHandle {
            stopWatching()
            try { fd.close() } catch (_: Exception) { }
            if (descriptor === fd) descriptor = null
        }
    }

    /** Starts the reader and package watch for an active session; both report back with [generation]. */
    private fun watch(state: RuntimeState, generation: Long) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state.id, getString(R.string.status_active), state.endsAt))
        val fd = descriptor ?: return
        reader = scope.launch(Dispatchers.IO) {
            try {
                val input = FileInputStream(fd.fileDescriptor)
                val buffer = ByteArray(32767)
                while (isActive && input.read(buffer) >= 0) { /* discard without inspecting */ }
            } catch (_: Exception) { /* reported below; stale generations are ignored */ }
            scope.launch { controller.tunnelClosed(generation) }
        }
        packageWatch = scope.launch {
            while (isActive) {
                delay(15_000)
                controller.checkPackages(generation)
            }
        }
    }

    private fun stopWatching() {
        packageWatch?.cancel(); packageWatch = null
        reader?.cancel(); reader = null
    }

    private fun stopIfCurrent() {
        // Do not tear down a newer start that is queued behind this one.
        if (newestStartRequest > sessionStartId) return
        if (stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        scope.launch { controller.revoked() }
    }

    override fun onDestroy() {
        // Synchronous on purpose: the process may die right after onDestroy returns.
        runBlocking(sessionThread) { controller.destroyed() }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(id: String, title: String, end: Long?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, StopReceiver::class.java).setAction(ACTION_STOP)
            .setData(Uri.parse("socialpause://session/$id")).putExtra(EXTRA_ID, id)
        val stop = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val description = if (end == null) getString(R.string.notification_indefinite) else getString(R.string.notification_end, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(end)))
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title).setContentText(description).setContentIntent(open).setOngoing(true)
            .addAction(0, getString(R.string.stop), stop).build()
    }
}
