# Muditracks

A small, deliberately boring OwnTracks client for the **Mudita Kompakt** (Android 12,
E Ink). It posts OwnTracks-compatible location messages to a **Home Assistant OwnTracks
HTTP webhook** and is built to keep working when the phone is asleep.

The regular OwnTracks app is powerful, but on the Kompakt it is easy to lose location
updates when the screen is off. Muditracks reduces the problem to the parts that matter:

- a real foreground service with `foregroundServiceType="location"`,
- a short partial wake lock around every fix and HTTP request,
- an `AlarmManager` watchdog that also fires in Doze,
- restart after boot and after the service is killed,
- a persistent FIFO for fixes that could not be delivered,
- no Google Play Services, no FusedLocationProvider, no MQTT library.

The UI uses [Mudita MMD](https://github.com/mudita/MMD) from the local Maven repository.

## Home Assistant setup

1. In Home Assistant go to **Settings → Devices & services → Add integration →
   OwnTracks**.
2. Home Assistant shows a **webhook URL** (and an optional encryption key). Muditracks
   uses the unencrypted HTTP mode, so only the URL is needed. Keep the URL private: the
   long webhook ID is the only secret in HTTP mode.
3. Enter the URL, a username (sent as `X-Limit-U`), and a device name (sent as
   `X-Limit-D`) in the app.
4. The tracker appears in Home Assistant as `device_tracker.<username>_<device>`.

The app sends the same JSON fields as the Android OwnTracks app (`_type`, `lat`, `lon`,
`acc`, `tst`, `batt`, `bs`, `tid`, `conn`, `topic`, ...). Home Assistant requires a
non-zero accuracy and the `X-Limit-U` header; both are always included.

## Build

Requirements:

- JDK 17 or newer (the build box uses Temurin 21),
- Android SDK with platform 36 and build-tools 34+,
- MMD `1.0.2` installed in `~/.m2` (the `settings.gradle.kts` uses `mavenLocal()`).

```bash
./gradlew :app:assembleDebug --offline
./gradlew :app:testDebugUnitTest --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build may need network access unless the Gradle cache is already populated.

## Configuration

The in-app settings screen is the normal way to configure the app. For a device that is
prepared from a build machine, `local.properties` can provide first-start defaults. The
file is gitignored, so the private webhook never lands in the repository:

```properties
sdk.dir=/path/to/Android/sdk
owntracks.url=https://home.example/api/webhook/…
owntracks.user=mk
owntracks.device=kompakt
owntracks.tid=kt
```

Those values become `BuildConfig` fields. Without `local.properties` the app starts with
empty fields and asks for the URL in the UI.

## Permissions

The app asks for:

- precise location and background location,
- notifications (Android 13+ only; the Kompakt runs Android 12),
- exemption from battery optimization,
- exact alarms.

Granting battery-optimization exemption and background location is important for reliable
updates in deep sleep. The status section shows which permissions are still missing and
offers shortcuts to the relevant system screens.

## How a cycle works

1. The foreground service and the alarm wake the device.
2. A partial wake lock is held for at most three minutes.
3. The best recent fix is chosen from GPS, network, passive, and cached providers; a fresh
   fix is requested if the cached one is stale.
4. The JSON payload is posted to the webhook with `X-Limit-U` / `X-Limit-D`.
5. On failure the payload is appended to `files/pending.jsonl` and retried on the next
   cycle. Delivery is capped so a long outage cannot create an unbounded request burst.
6. The next alarm is scheduled.

The default interval is 5 minutes. Values from 1 to 120 minutes are accepted. With exact
alarms granted, the watchdog can fire at the configured interval even in Doze; without
the permission Android may defer the alarm.

## Scope and limitations

- HTTP webhook only. MQTT and the OwnTracks Recorder `/pub` endpoint are not implemented.
- No OwnTracks region/waypoint or beacon messages.
- Payload encryption (Home Assistant's optional libsodium key) is not implemented; use
  HTTPS or a trusted LAN.
- The webhook URL is stored in the app's private preferences. It is not committed to git.

## License

MIT. See [LICENSE](LICENSE).
