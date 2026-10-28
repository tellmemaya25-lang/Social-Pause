package com.socialpause.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.os.Build
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import java.text.DateFormat
import java.util.Date
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: PickerViewModel = viewModel(factory = PickerViewModel.Factory(AndroidAppCatalog(applicationContext), LocalSelectionStore(applicationContext)))
                    SocialPauseApp(vm)
                }
            }
        }
    }
}

@Composable
private fun SocialPauseApp(vm: PickerViewModel) {
    var page by rememberSaveable { mutableStateOf("home") }
    val state by vm.state.collectAsStateWithLifecycle()
    val live by SessionRuntime.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var recovered by remember { mutableStateOf<RuntimeState?>(null) }
    var durationChoice by rememberSaveable { mutableStateOf("30") }
    var customMinutes by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var confirmStart by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf("") }
    val duration = selectedDuration(durationChoice, customMinutes)
    LaunchedEffect(live) {
        recovered = if (live != null) live else withContext(Dispatchers.IO) { SessionStore(context).reconcile() }
    }
    val current = live ?: recovered
    fun startService() {
        val selected = state.selected
        val minutes = when (val d = selectedDuration(durationChoice, customMinutes)) {
            is SessionDuration.Timed -> d.minutes
            SessionDuration.Indefinite -> -1
            null -> return
        }
        if (selected.isEmpty() || state.unavailable.isNotEmpty() || state.loading || state.error || state.selectionError) return
        val intent = Intent(context, PauseVpnService::class.java).setAction(ACTION_START)
            .putExtra(EXTRA_ID, UUID.randomUUID().toString())
            .putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(selected))
            .putExtra(EXTRA_MINUTES, minutes)
        try {
            ContextCompat.startForegroundService(context, intent)
            errorMessage = ""
            page = "home"
        } catch (_: Exception) {
            errorMessage = context.getString(R.string.service_start_error)
            page = "home"
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startService()
        else errorMessage = context.getString(R.string.consent_denied)
    }
    BackHandler(enabled = page != "home") { page = if (page == "duration") "picker" else "home" }
    if (confirmStart) AlertDialog(
        onDismissRequest = { confirmStart = false },
        title = { Text(stringResource(R.string.consent_title)) },
        text = { Column { Text(stringResource(R.string.consent_description)); if (current?.status == SessionStatus.ACTIVE) Text(stringResource(R.string.edit_warning)) } },
        confirmButton = { TextButton(onClick = {
            confirmStart = false
            try {
                val prepare = VpnService.prepare(context)
                if (prepare == null) startService() else consent.launch(prepare)
            } catch (_: Exception) { errorMessage = context.getString(R.string.service_start_error) }
        }) { Text(stringResource(R.string.continue_label_dialog)) } },
        dismissButton = { TextButton(onClick = { confirmStart = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false }, title = { Text(stringResource(R.string.stop_title)) },
        text = { Text(stringResource(R.string.stop_description)) },
        confirmButton = { TextButton(onClick = {
            confirmStop = false
            current?.id?.let { id ->
                try {
                    if (SessionRuntime.state.value?.id == id) context.startService(Intent(context, PauseVpnService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id))
                } catch (_: Exception) { errorMessage = context.getString(R.string.service_start_error) }
            }
        }) { Text(stringResource(R.string.stop)) } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.keep_pausing)) } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text(stringResource(R.string.clear_selection_title)) },
        text = { Text(stringResource(R.string.clear_selection_description)) },
        confirmButton = { TextButton(onClick = { vm.clearSelection(); confirmClear = false }) { Text(stringResource(R.string.clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) {
        when (page) {
            "home" -> {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.intro))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.prototype_notice), color = MaterialTheme.colorScheme.error)
                    if (errorMessage.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(errorMessage, color = MaterialTheme.colorScheme.error) }
                    if (current != null) {
                        Spacer(Modifier.height(16.dp))
                        SessionCard(current, onStop = { confirmStop = true }, onEdit = { vm.reload(); page = "picker" })
                    } else {
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { vm.reload(); page = "picker" }) { Text(stringResource(R.string.choose_apps)) }
                    }
                    TextButton(onClick = { page = "privacy" }) { Text(stringResource(R.string.how_it_works)) }
                }
            }
            "picker" -> {
                Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.visibility_note), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = state.query, onValueChange = vm::search, label = { Text(stringResource(R.string.search_apps)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.loading -> Text(stringResource(R.string.loading_apps))
                        state.error -> Column { Text(stringResource(R.string.load_error)); TextButton(onClick = vm::reload) { Text(stringResource(R.string.retry)) } }
                        state.apps.isEmpty() -> Text(stringResource(R.string.no_apps))
                        state.filteredApps.isEmpty() -> Column { Text(stringResource(R.string.no_results)); TextButton(onClick = { vm.search("") }) { Text(stringResource(R.string.clear_search)) } }
                        else -> LazyColumn {
                            items(state.filteredApps, key = { it.packageName }) { app ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = app.packageName in state.selected, role = Role.Checkbox, onValueChange = { vm.toggle(app.packageName) }), verticalAlignment = Alignment.CenterVertically) {
                                    AppIcon(app.packageName)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) { Text(app.label); Text(app.packageName, style = MaterialTheme.typography.bodySmall) }
                                    Checkbox(checked = app.packageName in state.selected, onCheckedChange = null)
                                }
                            }
                        }
                    }
                }
                if (state.selectionError) Text(stringResource(R.string.selection_save_error), color = MaterialTheme.colorScheme.error)
                if (!state.loading && !state.error && state.unavailable.isNotEmpty()) {
                    Text(stringResource(R.string.unavailable_selection), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.removeUnavailable() }) { Text(stringResource(R.string.remove_unavailable)) }
                }
                Text(stringResource(R.string.selected_count, state.selected.size))
                if (state.selected.isNotEmpty()) TextButton(onClick = { confirmClear = true }, enabled = !state.loading && !state.error) { Text(stringResource(R.string.clear_selection)) }
                if (state.selected.isEmpty()) Text(stringResource(R.string.choose_at_least_one), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { page = "duration" }, enabled = !state.loading && !state.error && !state.selectionError && state.unavailable.isEmpty() && state.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.continue_label)) }
            }
            "privacy" -> PrivacyScreen(onBack = { page = "home" }, requestNotifications = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) })
            else -> DurationScreen(state = state, choice = durationChoice, onChoiceChange = { durationChoice = it }, customMinutes = customMinutes, onCustomMinutesChange = { customMinutes = it }, onEditApps = { page = "picker" }, onStart = { confirmStart = true }, startEnabled = duration != null && state.selected.isNotEmpty() && !state.loading && !state.error && !state.selectionError && state.unavailable.isEmpty())
        }
    }
}

private fun selectedDuration(choice: String, custom: String): SessionDuration? = when (choice) {
    "15" -> SessionDuration.Timed.create(15)
    "30" -> SessionDuration.Timed.create(30)
    "60" -> SessionDuration.Timed.create(60)
    "custom" -> parseCustomDuration(custom)
    "indefinite" -> SessionDuration.Indefinite
    else -> null
}

@Composable
private fun SessionCard(session: RuntimeState, onStop: () -> Unit, onEdit: () -> Unit) {
    val status = when (session.status) {
        SessionStatus.STARTING -> R.string.status_starting
        SessionStatus.ACTIVE -> R.string.status_active
        SessionStatus.STOPPING -> R.string.status_stopping
        SessionStatus.COMPLETED -> R.string.status_completed
        SessionStatus.INTERRUPTED -> R.string.status_interrupted
        SessionStatus.ERROR -> R.string.status_error
        SessionStatus.IDLE -> R.string.status_stopped
    }
    Text(stringResource(status), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.session_start, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.startedAt))))
    if (session.endsAt != null) Text(stringResource(R.string.notification_end, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.endsAt))))
    else Text(stringResource(R.string.notification_indefinite))
    if (session.status == SessionStatus.ACTIVE && session.elapsedDeadline != null) {
        var now by remember(session.id) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(session.id, session.elapsedDeadline) { while (true) { now = SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(1000) } }
        val remaining = ((session.elapsedDeadline - now).coerceAtLeast(0) + 999) / 1000
        Text(stringResource(R.string.remaining_seconds, remaining / 60, remaining % 60))
    }
    Text(stringResource(R.string.session_apps))
    session.packages.sorted().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (session.status in setOf(SessionStatus.ACTIVE, SessionStatus.STARTING, SessionStatus.STOPPING)) {
        Text(stringResource(R.string.status_note))
        Text(stringResource(R.string.notification_permission_note), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onStop) { Text(stringResource(R.string.stop)) }
        OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.edit_session)) }
    } else {
        if (session.status == SessionStatus.ERROR || session.status == SessionStatus.INTERRUPTED) {
            Text(stringResource(R.string.interrupted_detail))
            val detail = when (session.reason) {
                "package" -> R.string.reason_package
                "consent", "revoked" -> R.string.reason_consent
                "foreground" -> R.string.reason_foreground
                "storage" -> R.string.reason_storage
                "establish", "invalid" -> R.string.reason_establish
                else -> R.string.reason_unknown
            }
            Text(stringResource(detail))
        }
        Button(onClick = onEdit) { Text(stringResource(R.string.review_restart)) }
    }
}

@Composable
private fun PrivacyScreen(onBack: () -> Unit, requestNotifications: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.vpn_explanation))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.privacy_explanation))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.limitations_explanation))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.notification_permission_note))
        if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = requestNotifications) { Text(stringResource(R.string.enable_notifications)) }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun DurationScreen(state: PickerState, choice: String, onChoiceChange: (String) -> Unit, customMinutes: String, onCustomMinutesChange: (String) -> Unit, onEditApps: () -> Unit, onStart: () -> Unit, startEnabled: Boolean) {
    val duration = selectedDuration(choice, customMinutes)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.duration_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.selected_count, state.selected.size))
        TextButton(onClick = onEditApps) { Text(stringResource(R.string.edit_apps)) }
        Text(stringResource(R.string.selected_apps), style = MaterialTheme.typography.titleSmall)
        state.apps.filter { it.packageName in state.selected }.forEach { app ->
            Text(stringResource(R.string.app_with_package, app.label, app.packageName), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.selectableGroup()) {
            listOf(
                "15" to R.string.duration_15, "30" to R.string.duration_30,
                "60" to R.string.duration_60, "custom" to R.string.duration_custom,
                "indefinite" to R.string.duration_indefinite,
            ).forEach { (value, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = choice == value, role = Role.RadioButton, onClick = { onChoiceChange(value) }
                    ), verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = choice == value, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(label))
                }
                if (value == "custom" && choice == value) {
                    OutlinedTextField(
                        value = customMinutes, onValueChange = onCustomMinutesChange,
                        label = { Text(stringResource(R.string.custom_minutes)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = customMinutes.isNotEmpty() && duration == null,
                        supportingText = {
                            if (customMinutes.isNotEmpty() && duration == null) Text(stringResource(R.string.invalid_duration))
                        }, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        when (duration) {
            is SessionDuration.Timed -> {
                val end = Date(System.currentTimeMillis() + duration.minutes * 60_000L)
                Text(stringResource(R.string.approximate_end, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(end)))
            }
            SessionDuration.Indefinite -> Text(stringResource(R.string.indefinite_explanation))
            null -> Unit
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.offline_reminder))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.duration_notice), color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.vpn_unavailable), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStart, enabled = startEnabled) { Text(stringResource(R.string.start_pause)) }
        OutlinedButton(onClick = onEditApps) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName).mutate()
                Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, it.width, it.height)
                    drawable.draw(Canvas(it))
                }
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        }
    }
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp)) }
    }
}
