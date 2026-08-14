# Android 9 device validation

目标设备：Huawei EVA-DL00，Android 9 / API 28。仓库不记录设备序列号、API Key、凭据或会话内容。

## 旧版调查结论（已废弃实现）

2026-08-14 的首次测试发现旧版只是 WebView/电脑端 Harness 客户端，因此该实现已从 `main` 删除，不能作为纯手机版验收结果。测试同时定位到手机发热主因：

- 电池在 USB 充电/调试期间由 42.0°C 上升到 46.0°C；
- ROM 的 `adbd` 持续占用约 89–100% CPU，主要为内核时间；
- 日志在多个无关进程中反复出现 `Receiving file descriptor from ADB failed`；
- 当时 DSH 应用采样 CPU 为 0%。

重启 `adbd` 后高占用立即复现，证据指向该定制 ROM 的 ADB/JDWP 传输异常，而非应用业务循环。测试后已关闭 USB 调试帮助设备降温。

## 原生独立版验收状态

| Check | Result |
|---|---|
| WebView/远端服务代码移除 | Passed |
| 单元测试 | Passed |
| Android lint | Passed |
| Debug APK assembly | Passed |
| 原生 UI/状态设备测试 | Passed：7 项 instrumentation 测试；包含 PTC 步骤、Job 并发、会话恢复 |
| 通用/模型/插件/预设页面导航 | Passed |
| 计划/目标/Jobs/工作流页面导航 | Passed |
| Android 9 安装/冷启动 | Passed：Release APK，versionCode 4 / 0.1.9，冷启动约 376 ms |
| 独立模型真实请求 | 需用户在手机配置自己的 API Key |
| 权限清单 | Passed：仅 INTERNET、ACCESS_NETWORK_STATE |
| 空闲内存 | 约 31 MiB PSS（0.1.9） |
| USB 调试温控复测 | DSH 0% CPU，root `adbd` 约 87% CPU，45.0°C；测试后已停止主机 ADB 服务 |

2026-08-14 的 0.1.9 最终测试已按上述方式完成。发布包覆盖安装成功、签名校验通过、7/7 项设备测试通过，且设置页与原生插件清单在 1080×1920 屏幕正确显示。DSH 冷启动后的短暂 CPU 活动会迅速回落；采样时 DSH 为 0%，持续满核的是 root `adbd`。NexusAI 手势服务在测试期间还出现过系统级“屡次停止运行”弹窗，属于另一进程，也会干扰 UI 自动化。继续调试前应先让设备降温，不能把该 ROM/调试链路异常归因于 DSH 的业务循环。
