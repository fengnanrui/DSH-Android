# Testing and acceptance checklist

## Automated checks

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

Some Android 9 custom ROMs stall Gradle's install orchestration even while direct ADB remains healthy. The equivalent deterministic fallback is:

```bash
scripts/run-device-tests.sh
```

The unit suite covers endpoint normalization, scheme restrictions, embedded-credential rejection, loopback handling, and origin generation.
The device suite verifies the real WebView's JavaScript/DOM storage capabilities, mixed-content policy, third-party cookie isolation, upload content access, and mobile User-Agent.

For a debuggable WebView connected through ADB, `scripts/webview-smoke.mjs` records real Harness plugin, session, DOM, sidebar, and settings-layout evidence:

```bash
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.fengnanrui.dshandroid)
DSH_INTERACTIVE=1 node scripts/webview-smoke.mjs
```

## Android 9 real-device setup

1. Start a real Harness server on the host.
2. Run `adb reverse tcp:3080 tcp:<host-harness-port>`.
3. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Confirm the device WebView is Chromium 121 or newer with `adb shell dumpsys webviewupdate`.

## Functional regression

- [ ] First launch connects to `http://127.0.0.1:3080/` through USB forwarding.
- [ ] Harness boot entries and plugin modules load without JavaScript or WebSocket errors.
- [ ] Create a new session and send a prompt through a configured provider.
- [ ] Stop/cancel a running turn, retry it, and continue the conversation.
- [ ] Open and close sidebar, session history, details, trajectory, and settings.
- [ ] Configure or select a model without exposing the API key in Android logs.
- [ ] Create/select a workspace using Harness's server-side directory browser.
- [ ] Exercise Plan, approvals, user questions, goals, jobs, skills, subagents, and plugin settings when enabled on the server.
- [ ] Upload one file and multiple files through the Android document picker.
- [ ] Download a produced file and confirm it appears in Downloads.
- [ ] Open a cross-origin source link and confirm it leaves the WebView.
- [ ] Reload, rotate, background/foreground, and use Android Back without losing the server-side session.
- [ ] Deny camera/microphone/location once and confirm the page does not receive permission.
- [ ] Confirm an invalid TLS certificate is blocked with no bypass option.
- [ ] Disconnect, edit the server address, reconnect, and verify persistence after process restart.

## Resource and thermal checks

- Capture app PSS with `adb shell dumpsys meminfo com.fengnanrui.dshandroid`.
- Capture top CPU consumers with `adb shell top -b -n 1`.
- Capture battery temperature with `adb shell dumpsys battery` (`temperature` is tenths of a degree Celsius).
- Test with USB debugging disabled after regression to distinguish application load from device-ROM `adbd` behavior.
