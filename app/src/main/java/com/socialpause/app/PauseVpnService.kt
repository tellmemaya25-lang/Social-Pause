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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.util.UUID

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

class PauseVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var descriptor: ParcelFileDescriptor? = null
    private var reader: Job? = null
    private var timer: Job? = null
    private var packageWatch: Job? = null
    private var sessionStartId = 0
    private var latestStartId = 0
    private var newestStartRequest = 0
    private var generation = 0L
    private lateinit var store: SessionStore

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
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
            scope.launch { mutex.withLock { startSession(id, packages, minutes, startId) } }
        } else if (action == ACTION_STOP) {
            val id = intent.getStringExtra(EXTRA_ID)
            scope.launch { mutex.withLock { if (SessionRuntime.state.value?.id == id) finish(SessionStatus.IDLE, "stopped", startId) } }
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

    private suspend fun startSession(id: String, packages: Set<String>, minutes: Int, startId: Int) {
        closeTunnel()
        sessionStartId = startId
        val now = System.currentTimeMillis()
        SessionRuntime.publish(RuntimeState(id, SessionStatus.STARTING, packages, now, null, null))
        val validId = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        if (!validId || packages.isEmpty() || packageName in packages || (minutes != -1 && minutes !in 1..720)) {
            fail(id, packages, now, "invalid"); return
        }
        if (prepare(this) != null) { fail(id, packages, now, "consent"); return }
        val end = if (minutes == -1) null else now + minutes * 60_000L
        val elapsedEnd = if (minutes == -1) null else SystemClock.elapsedRealtime() + minutes * 60_000L
        val starting = RuntimeState(id, SessionStatus.STARTING, packages, now, end, elapsedEnd)
        SessionRuntime.publish(starting)
        if (!withContext(Dispatchers.IO) { store.write(starting) }) { fail(id, packages, now, "storage"); return }
        try {
            val builder = Builder().setSession(getString(R.string.app_name)).setBlocking(true)
                .addAddress("10.111.0.1", 32).addRoute("0.0.0.0", 0)
                .addAddress("fd00:111::1", 128).addRoute("::", 0)
            for (pkg in packages) {
                // Both resolution and addAllowedApplication must succeed, or fail the entire session.
                packageManager.getApplicationInfo(pkg, 0)
                builder.addAllowedApplication(pkg)
            }
            val fd = builder.establish() ?: error("VPN establishment failed")
            descriptor = fd
            val token = ++generation
            val active = starting.copy(status = SessionStatus.ACTIVE)
            if (!withContext(Dispatchers.IO) { store.write(active) }) { fail(id, packages, now, "storage"); return }
            SessionRuntime.publish(active)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(id, getString(R.string.status_active), end))
            reader = scope.launch(Dispatchers.IO) {
                try {
                    val input = FileInputStream(fd.fileDescriptor)
                    val buffer = ByteArray(32767)
                    while (isActive && input.read(buffer) >= 0) { /* discard without inspecting */ }
                } catch (_: Exception) { /* handled below if still current */ }
                scope.launch(Dispatchers.Main) {
                    mutex.withLock { if (token == generation) finish(SessionStatus.INTERRUPTED, "reader", sessionStartId) }
                }
            }
            if (elapsedEnd != null) timer = scope.launch {
                while (true) {
                    val remaining = elapsedEnd - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    delay(remaining)
                }
                scope.launch { mutex.withLock { if (token == generation) finish(SessionStatus.COMPLETED, "expired", sessionStartId) } }
            }
            packageWatch = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(15_000)
                    val allAvailable = packages.all { pkg -> runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess }
                    if (!allAvailable) {
                        scope.launch(Dispatchers.Main) { mutex.withLock { if (token == generation) finish(SessionStatus.INTERRUPTED, "package", sessionStartId) } }
                        break
                    }
                }
            }
        } catch (_: Exception) { fail(id, packages, now, "establish") }
    }

    private suspend fun fail(id: String, packages: Set<String>, now: Long, reason: String) {
        closeTunnel()
        val failed = RuntimeState(id, SessionStatus.ERROR, packages, now, null, null, reason)
        SessionRuntime.publish(failed)
        withContext(Dispatchers.IO) { store.write(failed) }
        stopIfCurrent(sessionStartId)
    }

    private suspend fun finish(status: SessionStatus, reason: String, startId: Int) {
        val current = SessionRuntime.state.value ?: return
        closeTunnel()
        val terminal = current.copy(status = status, elapsedDeadline = null, reason = reason)
        SessionRuntime.publish(terminal)
        withContext(Dispatchers.IO) { store.write(terminal) }
        stopIfCurrent(startId)
    }

    private fun stopIfCurrent(@Suppress("UNUSED_PARAMETER") startId: Int) {
        // Do not tear down a newer start that is queued behind the mutex.
        if (newestStartRequest > sessionStartId) return
        if (stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun closeTunnel() {
        generation++
        timer?.cancel(); timer = null
        packageWatch?.cancel(); packageWatch = null
        try { descriptor?.close() } catch (_: Exception) { }
        descriptor = null
        reader?.cancel(); reader = null
    }

    override fun onRevoke() {
        scope.launch { mutex.withLock { finish(SessionStatus.INTERRUPTED, "revoked", sessionStartId) } }
    }
    override fun onDestroy() {
        val current = SessionRuntime.state.value
        closeTunnel()
        if (current != null && current.status in setOf(SessionStatus.STARTING, SessionStatus.ACTIVE, SessionStatus.STOPPING)) {
            val terminal = current.copy(status = SessionStatus.INTERRUPTED, elapsedDeadline = null, reason = "service")
            SessionRuntime.publish(terminal)
            store.write(terminal)
        }
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
