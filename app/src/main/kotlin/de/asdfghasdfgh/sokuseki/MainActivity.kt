package de.asdfghasdfgh.sokuseki

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatusStore.init(this)
        setContent {
            ThemeMMD {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TrackerScreen()
                }
            }
        }
    }
}

@Composable
private fun TrackerScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val store = remember { ConfigStore(context) }
    val initial = remember { store.load() }
    val status by StatusStore.state.collectAsStateWithLifecycle()

    var url by remember { mutableStateOf(initial.url) }
    var user by remember { mutableStateOf(initial.user) }
    var device by remember { mutableStateOf(initial.device) }
    var tid by remember { mutableStateOf(initial.tid) }
    var password by remember { mutableStateOf(initial.password) }
    var interval by remember { mutableStateOf(initial.intervalMinutes.toString()) }
    var trustAll by remember { mutableStateOf(initial.trustAllCerts) }
    var message by remember { mutableStateOf("") }

    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val fineGranted = remember(resumeTick) { hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) }
    val backgroundGranted = remember(resumeTick) { hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val notificationsGranted = remember(resumeTick) {
        Build.VERSION.SDK_INT < 33 ||
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    val batteryExempt = remember(resumeTick) {
        (context.getSystemService(PowerManager::class.java))
            .isIgnoringBatteryOptimizations(context.packageName)
    }
    val exactAlarms = remember(resumeTick) {
        val manager = context.getSystemService(AlarmManager::class.java)
        Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    }

    fun startTrackingNow() {
        TrackingService.start(context)
        StatusStore.log(context.getString(R.string.log_started))
        message = context.getString(R.string.message_started)
        if (!batteryExempt) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startTrackingNow() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || fineGranted
        if (!fine) {
            message = context.getString(R.string.message_need_location)
        } else if (!backgroundGranted) {
            backgroundLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        } else {
            startTrackingNow()
        }
    }

    fun requestStart() {
        val candidate = initial.copy(
            url = url,
            user = user,
            device = device,
            tid = tid,
            password = password,
            intervalMinutes = interval.toIntOrNull() ?: TrackConfig.DEFAULT_INTERVAL_MINUTES,
            trustAllCerts = trustAll,
        )
        val error = candidate.validationError()
        if (error != null) {
            message = configErrorText(context, error)
            return
        }
        store.save(candidate)
        message = context.getString(R.string.message_saved)
        val missing = buildList {
            if (!fineGranted) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else if (!backgroundGranted) {
            backgroundLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        } else {
            startTrackingNow()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextMMD(
            text = stringResource(R.string.screen_title),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        TextMMD(
            text = stringResource(R.string.screen_subtitle),
            fontSize = 14.sp,
        )
        TextMMD(
            text = stringResource(
                if (status.running) R.string.status_running else R.string.status_stopped
            ),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()
        SectionTitle(stringResource(R.string.section_connection))
        TextFieldMMD(
            value = url,
            onValueChange = { url = it },
            label = { TextMMD(stringResource(R.string.field_webhook_url)) },
            placeholder = { TextMMD(stringResource(R.string.hint_webhook_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        TextFieldMMD(
            value = user,
            onValueChange = { user = it },
            label = { TextMMD(stringResource(R.string.field_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextFieldMMD(
            value = device,
            onValueChange = { device = it },
            label = { TextMMD(stringResource(R.string.field_device)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextFieldMMD(
            value = tid,
            onValueChange = { tid = it },
            label = { TextMMD(stringResource(R.string.field_tid)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextFieldMMD(
            value = password,
            onValueChange = { password = it },
            label = { TextMMD(stringResource(R.string.field_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        SectionTitle(stringResource(R.string.section_sending))
        TextFieldMMD(
            value = interval,
            onValueChange = { interval = it.filter(Char::isDigit).take(3) },
            label = { TextMMD(stringResource(R.string.field_interval)) },
            supportingText = { TextMMD(stringResource(R.string.hint_interval)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(stringResource(R.string.switch_trust_all), fontSize = 15.sp)
                TextMMD(
                    stringResource(R.string.hint_trust_all),
                    fontSize = 11.sp,
                )
            }
            SwitchMMD(
                checked = trustAll,
                onCheckedChange = { trustAll = it },
            )
        }

        ButtonMMD(
            onClick = { requestStart() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextMMD(stringResource(R.string.button_save_start))
        }
        OutlinedButtonMMD(
            onClick = {
                if (ConfigStore(context).load().validationError() != null) {
                    message = context.getString(R.string.message_invalid_config)
                } else {
                    TrackingService.sendNow(context)
                    message = context.getString(R.string.message_send_triggered)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextMMD(stringResource(R.string.button_send_now))
        }
        OutlinedButtonMMD(
            onClick = {
                ConfigStore(context).enabled = false
                if (TrackingService.isRunning) TrackingService.stop(context)
                StatusStore.setRunning(false)
                StatusStore.log(context.getString(R.string.log_stopped))
                message = context.getString(R.string.message_stopped)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextMMD(stringResource(R.string.button_stop))
        }

        if (message.isNotBlank()) {
            TextMMD(
                text = message,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        HorizontalDivider()
        SectionTitle(stringResource(R.string.section_permissions))
        PermissionLine(stringResource(R.string.permission_location), fineGranted)
        PermissionLine(stringResource(R.string.permission_background_location), backgroundGranted)
        PermissionLine(stringResource(R.string.permission_notifications), notificationsGranted)
        PermissionLine(stringResource(R.string.permission_battery), batteryExempt)
        PermissionLine(stringResource(R.string.permission_exact_alarms), exactAlarms)

        if (!backgroundGranted) {
            OutlinedButtonMMD(
                onClick = {
                    backgroundLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextMMD(stringResource(R.string.button_allow_background))
            }
        }
        if (!batteryExempt) {
            OutlinedButtonMMD(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextMMD(stringResource(R.string.button_allow_battery))
            }
        }
        if (!exactAlarms) {
            OutlinedButtonMMD(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextMMD(stringResource(R.string.button_allow_exact_alarms))
            }
        }
        OutlinedButtonMMD(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextMMD(stringResource(R.string.button_app_settings))
        }

        HorizontalDivider()
        SectionTitle(stringResource(R.string.section_status))
        StatusLine(
            stringResource(R.string.status_last_fix),
            formatTimestamp(status.lastFixAt, status.lastFixLat, status.lastFixLon),
        )
        StatusLine(stringResource(R.string.status_last_sent), formatTimestamp(status.lastSentAt))
        StatusLine(
            stringResource(R.string.status_queue),
            stringResource(R.string.status_queue_value, status.pending),
        )
        if (status.pending > 0) {
            TextMMD(
                stringResource(R.string.status_queue_hint),
                fontSize = 10.sp,
            )
        }
        StatusLine(
            stringResource(R.string.status_last_http),
            if (status.lastCode == 0) "–" else status.lastCode.toString(),
        )
        if (status.lastError.isNotBlank()) {
            StatusLine(stringResource(R.string.status_error), status.lastError)
        }
        if (status.logs.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            TextMMD(
                stringResource(R.string.logs_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            status.logs.takeLast(10).forEach { line ->
                TextMMD(
                    text = line,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        TextMMD(
            text = stringResource(R.string.footer, BuildConfig.VERSION_NAME),
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    TextMMD(
        text = text.uppercase(Locale.getDefault()),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun PermissionLine(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextMMD(label, fontSize = 13.sp)
        TextMMD(
            stringResource(
                if (granted) R.string.permission_granted else R.string.permission_missing
            ),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TextMMD(label, fontSize = 11.sp)
        TextMMD(value.ifBlank { "–" }, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun configErrorText(context: Context, error: ConfigError): String = when (error) {
    ConfigError.URL -> context.getString(R.string.error_url)
    ConfigError.USER -> context.getString(R.string.error_user)
    ConfigError.DEVICE -> context.getString(R.string.error_device)
    ConfigError.INTERVAL -> context.getString(
        R.string.error_interval,
        TrackConfig.MIN_INTERVAL_MINUTES,
        TrackConfig.MAX_INTERVAL_MINUTES,
    )
}

private fun formatTimestamp(millis: Long, lat: Double? = null, lon: Double? = null): String {
    if (millis <= 0) return "–"
    val time = DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.MEDIUM,
        Locale.getDefault(),
    ).format(Date(millis))
    return if (lat != null && lon != null) {
        "%.5f, %.5f · %s".format(Locale.US, lat, lon, time)
    } else {
        time
    }
}
