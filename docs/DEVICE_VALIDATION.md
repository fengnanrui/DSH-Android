# Android 9 device validation

Validated on 2026-08-14 against a real DSH Desktop Harness instance.

## Device and runtime

- Device family: Huawei EVA-DL00
- Android: 9 / API 28
- WebView: Mulch Chromium 121.0.6167.178
- Connection: `adb reverse tcp:3080 tcp:<DSH Desktop random loopback port>`
- App package: `com.fengnanrui.dshandroid`
- App version: `0.1.0` (`versionCode 1`)

No device serial, API key, credential, or session content is recorded in this repository.

## Results

| Check | Result |
|---|---|
| Endpoint unit tests | 5 passed |
| Android lint | Passed |
| Debug APK assembly | Passed |
| Streamed APK install | `Success` |
| On-device instrumentation | `OK (1 test)` |
| Harness document state | `complete` |
| Harness plugin resources | 37 loaded, 0 failed |
| Android mobile style marker | Present |
| Session/workspace browser state | Present in origin-scoped local storage |
| Narrow frame | `56px 304px 0px` at a 360px frame |
| Mobile settings panel | 344×576 at 360×592 viewport |
| Mobile settings navigation | 344px wide, horizontal |

The real page exposed sidebar, workspace, session, settings, model selection, permission mode, agent preset, command, send, and details controls. The smoke test expanded the sidebar, opened Settings, verified computed mobile geometry, then restored the UI. It did not send a paid model request or alter server credentials.

## Resource and thermal observation

The app process was installed and running with one WebView. A later process sample reported about 64 MB PSS for the application process and 0% sampled application CPU.

The phone itself was already thermally unhealthy during USB testing:

- battery temperature rose from 42.0°C to 46.0°C while charging;
- `adbd` alone continuously consumed roughly 89–100% CPU, dominated by kernel time;
- Android logs repeatedly reported `Receiving file descriptor from ADB failed` across many unrelated processes;
- DSH Android did not appear as a CPU consumer in the same sample.

This points to the custom Android 9 ROM's ADB/JDWP transport, not DSH Android, as the heat source. Restarting `adbd` reproduced the high CPU immediately. After testing, disable USB debugging and unplug the cable so the device can cool; re-enable debugging only when another ADB session is needed.

## Reproduction

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
scripts/run-device-tests.sh

adb reverse tcp:3080 tcp:<host-harness-port>
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.fengnanrui.dshandroid)
DSH_INTERACTIVE=1 node scripts/webview-smoke.mjs
```
