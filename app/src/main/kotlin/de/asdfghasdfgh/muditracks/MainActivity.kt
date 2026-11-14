package de.asdfghasdfgh.muditracks

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
import java.text.SimpleDateFormat
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
        StatusStore.log("Dienst gestartet.")
        message = "Dienst gestartet."
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
            message = "Ohne Standortberechtigung kann nicht gesendet werden."
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
            message = error
            return
        }
        store.save(candidate)
        message = "Gespeichert."
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
            text = "Muditracks",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        TextMMD(
            text = "OwnTracks → Home Assistant",
            fontSize = 14.sp,
        )
        TextMMD(
            text = if (status.running) "● Dienst läuft" else "○ Dienst gestoppt",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()
        SectionTitle("Verbindung")
        TextFieldMMD(
            value = url,
            onValueChange = { url = it },
            label = { TextMMD("Webhook-URL") },
            placeholder = { TextMMD("https://…/api/webhook/…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        TextFieldMMD(
            value = user,
            onValueChange = { user = it },
            label = { TextMMD("Benutzername (X-Limit-U)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextFieldMMD(
            value = device,
            onValueChange = { device = it },
            label = { TextMMD("Geräte-ID (X-Limit-D)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextFieldMMD(
            value = tid,
            onValueChange = { tid = it },
            label = { TextMMD("Tracker-ID (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextFieldMMD(
            value = password,
            onValueChange = { password = it },
            label = { TextMMD("Passwort (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        SectionTitle("Senden")
        TextFieldMMD(
            value = interval,
            onValueChange = { interval = it.filter(Char::isDigit).take(3) },
            label = { TextMMD("Intervall in Minuten") },
            supportingText = { TextMMD("1–120 Minuten, auch im Ruhezustand") },
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
                TextMMD("Selbstsignierte TLS-Zertifikate", fontSize = 15.sp)
                TextMMD(
                    "Nur nötig, wenn Home Assistant ein eigenes Zertifikat nutzt.",
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
            TextMMD("Speichern & starten")
        }
        OutlinedButtonMMD(
            onClick = {
                if (ConfigStore(context).load().validationError() != null) {
                    message = "Bitte zuerst eine gültige Konfiguration speichern."
                } else {
                    TrackingService.sendNow(context)
                    message = "Sendeauftrag ausgelöst."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextMMD("Jetzt senden")
        }
        OutlinedButtonMMD(
            onClick = {
                ConfigStore(context).enabled = false
                if (TrackingService.isRunning) TrackingService.stop(context)
                StatusStore.setRunning(false)
                StatusStore.log("Tracking gestoppt.")
                message = "Dienst gestoppt."
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextMMD("Stopp")
        }

        if (message.isNotBlank()) {
            TextMMD(
                text = message,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        HorizontalDivider()
        SectionTitle("Berechtigungen")
        PermissionLine("Standort", fineGranted)
        PermissionLine("Hintergrundstandort", backgroundGranted)
        PermissionLine("Benachrichtigungen", notificationsGranted)
        PermissionLine("Akku-Optimierung aus", batteryExempt)
        PermissionLine("Exakte Alarme", exactAlarms)

        if (!backgroundGranted) {
            OutlinedButtonMMD(
                onClick = {
                    backgroundLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextMMD("Hintergrundstandort erlauben")
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
                TextMMD("Akku-Optimierung erlauben")
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
                TextMMD("Exakte Alarme erlauben")
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
            TextMMD("App-Einstellungen öffnen")
        }

        HorizontalDivider()
        SectionTitle("Status")
        StatusLine("Letzter Fix", formatTimestamp(status.lastFixAt, status.lastFixLat, status.lastFixLon))
        StatusLine("Zuletzt gesendet", formatTimestamp(status.lastSentAt))
        StatusLine("Warteschlange", "${status.pending} Punkt(e)")
        StatusLine("Letzter HTTP-Status", if (status.lastCode == 0) "–" else status.lastCode.toString())
        if (status.lastError.isNotBlank()) {
            StatusLine("Fehler", status.lastError)
        }
        if (status.logs.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            TextMMD("Protokoll", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            text = "MMD · E Ink · ${BuildConfig.VERSION_NAME}",
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    TextMMD(
        text = text.uppercase(Locale.GERMANY),
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
        TextMMD(if (granted) "erlaubt" else "fehlt", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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

private fun formatTimestamp(millis: Long, lat: Double? = null, lon: Double? = null): String {
    if (millis <= 0) return "–"
    val time = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date(millis))
    return if (lat != null && lon != null) {
        "%.5f, %.5f · %s".format(Locale.US, lat, lon, time)
    } else {
        time
    }
}
