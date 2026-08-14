# DSH Android

An unofficial Android client for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness), designed from the host lifecycle and security ideas in [DSH Desktop](https://github.com/dataelement/dsh-desktop).

中文说明见下方。DSH Android is an independent community project and is not affiliated with DeepSeek AI or DataElement.

## What it does

DSH Android connects a phone to a real Harness Web server over USB forwarding, a trusted LAN, or HTTPS. It keeps the complete Harness backend on the machine where Node.js, shells, project files, plugins, credentials, and sessions actually live, while providing an Android-native mobile shell for:

- persistent server configuration and connection recovery;
- the full Harness Web UI, WebSocket connection, sessions, settings, models, plugins, tools, plans, subagents, jobs, and workspaces;
- Android file upload and multi-file selection;
- authenticated downloads to the system Downloads folder;
- camera, microphone, and location permission mediation for same-origin pages;
- HTTP Basic authentication, strict TLS failure handling, and external-link isolation;
- back navigation, reload, fullscreen content, cache controls, and a phone-sized settings layout;
- Android 9 / API 28 compatibility.

The app intentionally does **not** pretend to run Harness locally. Current Harness requires Node.js 22+ and desktop-native modules such as `node-pty`; upstream currently validates desktop platforms, not Android/Bionic. Keeping the runtime on the host preserves the real tool environment and avoids an unreliable partial port.

## Quick start over USB

Requirements:

- a host running Node.js 22 or newer;
- Android Platform Tools (`adb`);
- an Android 9 or newer phone with USB debugging enabled.

Start Harness on the host:

```bash
npx @deepseek-ai/dsh web --host 127.0.0.1 --port 3080
```

Forward the phone's loopback port and install the debug APK:

```bash
adb reverse tcp:3080 tcp:3080
./gradlew installDebug
```

Open DSH Android. Its default address is `http://127.0.0.1:3080/`.

If DSH Desktop selected a random loopback port, forward the fixed phone port to it instead:

```bash
adb reverse tcp:3080 tcp:62048
```

Replace `62048` with the active Harness port.

## LAN and HTTPS

For LAN use, configure Harness or a reverse proxy so the phone can reach it, then enter that URL in the app. Plain HTTP to a non-loopback host triggers a warning because session data and API interactions are unencrypted. HTTPS is recommended outside USB forwarding.

DSH Android never ignores invalid TLS certificates. Cross-origin HTTP(S) links open in the system browser instead of inheriting the Harness WebView session.

## Build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Project defaults:

- compile SDK 35;
- target SDK 35;
- minimum SDK 28;
- Java 17;
- Android Gradle Plugin 8.6.1;
- Gradle 8.9.

See [docs/TESTING.md](docs/TESTING.md) for the regression checklist and [docs/DEVICE_VALIDATION.md](docs/DEVICE_VALIDATION.md) for the Android 9 real-device evidence.

## 中文说明

DSH Android 是一个非官方的 DeepSeek Harness 手机客户端。它不在手机里伪装运行不受上游支持的 Node/PTY 环境，而是通过 USB 端口转发、可信局域网或 HTTPS 连接真实 Harness 服务。这样模型配置、凭据、插件、会话、工作区、终端和工具都继续使用 Harness 的原生实现，手机只负责安全连接和移动界面。

已经适配的手机能力包括：地址保存和重试、窄屏设置页、WebSocket、上传/多选文件、下载、网页摄像头/麦克风/定位授权、返回键、全屏、HTTP Basic Auth、严格证书校验和外链隔离。最低支持 Android 9。

USB 使用方法：

```bash
npx @deepseek-ai/dsh web --host 127.0.0.1 --port 3080
adb reverse tcp:3080 tcp:3080
./gradlew installDebug
```

然后打开应用，使用默认地址 `http://127.0.0.1:3080/` 即可。

## License and upstream rules

DSH Android is released under the [MIT License](LICENSE). Upstream attribution and trademark clarification are recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). No upstream API key, user credential, proprietary asset, or generated session is committed to this repository.
