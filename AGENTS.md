# PakEko Agent Notes

This is a native Kotlin Android app for detecting supported bank payment notifications locally. The current target is myBCA only, package `com.bca.mybca.omni.android`.

## Environment

Use the Nix flake from the repository root. It provides JDK 17, Gradle, Kotlin, Android SDK platforms 35/36, build tools 35.0.0, and `adb`.

```sh
nix develop
```

You can also run commands directly through the shell:

```sh
nix develop -c gradle testDebugUnitTest
nix develop -c gradle assembleDebug
```

No separate Android Studio or manually installed Android SDK is required for normal builds.

## Build And Test

Run unit tests:

```sh
nix develop -c gradle testDebugUnitTest
```

Build the debug APK:

```sh
nix develop -c gradle assembleDebug
```

The APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For a fuller local check:

```sh
nix develop -c gradle testDebugUnitTest assembleDebug
```

## Install On A Device

Connect the Android device with USB debugging enabled, then verify it is visible:

```sh
nix develop -c adb devices
```

Install or update the debug build:

```sh
nix develop -c adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app id is:

```text
id.local.transfermonitor
```

After install, open PakEko and enable notification access when prompted. Android exposes this under notification listener settings, and the listener service is `id.local.transfermonitor/.monitor.BankNotificationListenerService`.

## Runtime Notes

- The listener should only accept myBCA notifications from `com.bca.mybca.omni.android`.
- Supported notification events are stored in local SQLite.
- Detections are written when the parser extracts an amount from a supported notification.
- The app has buttons for Refresh, Reparse, Export JSON, and Export DB.
- Use Reparse after changing parser logic so existing stored events get new detections.
- Reparse is local-only and must not send webhooks.
- Individual notifications can be parsed from the Notifications tab. If parsing succeeds and a webhook URL is saved, the UI asks before sending the webhook.
- Webhook delivery posts newly detected payments to the user-configured URL. Manual "Send latest" is available in the app.
- Webhook delivery status is stored in SQLite. Detections show delivery state from `webhook_deliveries`.
- Webhook idempotency uses a SHA-256 key from notification title + message body. The key is also sent as `idempotency_key` in the JSON payload and `Idempotency-Key` header.
- Duplicate notification title/message pairs should not create a second detection or automatic webhook delivery.
- myBCA `Pengeluaran` / sent notifications should remain notification-only: store them with an ignored reason, but do not create parsed transactions or send webhooks.
- The notification listener requests a system rebind in `onListenerDisconnected()`.
- Cleartext HTTP is currently allowed for local webhook testing.

## Parser Notes

Prefer template-first parsing for known myBCA notification text. Keep generic IDR parsing as a lower-confidence fallback so wording drift is still visible.

Current known templates should match both title and body:

- English title: `Financial Diary`
- Indonesian title: `Catatan Finansial`
- Incoming-transfer body wording with `Account Transfer` or `Transfer Rekening`

When adding a new notification sample, add a focused unit test in `app/src/test/java/id/local/transfermonitor/util/PaymentNotificationParserTest.kt`.

## Repository Notes

- Exported SQLite files (`*.db`, `*.sqlite`, `*.sqlite3`) are ignored and should not be committed.
- Prefer `rg` for searching.
- Keep parser version `idr-v1` unless the storage/webhook contract intentionally changes.
