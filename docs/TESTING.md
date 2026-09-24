# Testing and acceptance checklist

## Automated checks

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
ANDROID_HOME=/path/to/android-sdk ./gradlew connectedDebugAndroidTest
```

单元测试核对供应商注册表、HTTPS 默认值、三种协议历史序列化、错误脱敏、工具边界、预设能力与有界输出。设备测试断言不存在 WebView，遍历设置和运行中心，执行原生工具编排，验证损坏历史恢复、停止审批、插件授权、跨会话队列、子进程取消、原生超时和日志限额。协议 fixture 通过不等于真实供应商请求通过。

## 2026-09 重构验收

本轮在独立、只读镜像的 Android 16 / API 36 arm64 模拟器运行，不使用其他任务的模拟器应用数据。新功能和未实现能力见 [功能矩阵](REFACTOR_2026-09.md)。下方原有人工清单的未勾选项仍需逐项验收，不能因新增自动化测试而整体标记完成。

2026-09-21，0.2.0-rc.1 / versionCode 5 的本地检查：

- `testDebugUnitTest`：19 项通过。
- `connectedDebugAndroidTest`：16 项通过，包含真正的 Activity recreate + 草稿保留、跨会话队列、取消等待、进程组子进程停止和原生超时。
- `lintDebug` / `assembleRelease`：通过；lint 仍有 2 个现存 warning（OldTargetApi、DataExtractionRules），无 error。
- release APK 安装和冷启动通过；本轮只使用测试签名和模拟器，不替代物理设备、正式签名和真实模型验收。

自动化结果来自对应 Gradle XML/HTML 报告；不包含任何付费模型请求。原生 QA 使用 adb UI 树定位控件；性能数据只作为单次模拟器样本，不把空闲 CPU 低或构建成功写成“真机发烫已修复”。

最终 APK 的人工 UI 流程：设置 → 模型 → 插件列表 → 输入 `timer` → 预设 → 设置 → 系统深色主题。UI 树确认过滤后输入仍聚焦，截图确认键盘与导航栏不遮挡、浅色系统导航按钮可见、深色文字可读。

该流程的一次模拟器采样：总 PSS 40,471 KB，Activity 1 个，WebView 0 个；随后三次 1 秒间隔的空闲进程采样均为 0.0% CPU。gfxinfo 为 165 帧、30 帧 deadline miss（18.18%），P95 19 ms / P99 65 ms，slow UI thread 0。此结果**不能宣称无卡顿**；模拟器渲染和录制影响尚未通过真机 Perfetto 分离，也不能据此判断电池温度或长期内存泄漏。

最终 release APK SHA-256：`14be88169d25ab7073904bb44fe2ba131153a21ac18211892ed416b14fd838be`。发布附件应与此值一致；新构建必须重新记录，不可复用此验收记录。

## Functional regression

### 2026-09-25 源码回归

- 修复取消/失败后排队草稿未通知当前界面的问题；恢复文本立即显示，同时保留新草稿和普通状态更新时的光标位置。两项新增回归在修复前失败，修复后通过。
- 修复模型请求期间停用插件后，`delegate_task` / `ask_user` 仍能执行的问题。它们现在与其他原生工具共用执行前授权和取消检查。新增用例在修复前观察到多余模型请求及提问回调，修复后禁止执行。
- 使用独立只读 API 36 模拟器；不覆盖 v0.2.0-rc.1 的既有发布附件，也不把本次构建当作真机或付费模型验收。
- `testDebugUnitTest` 19 项、`connectedDebugAndroidTest` 20 项全部通过；`lintDebug` 与 `assembleDebug` 通过，仍保留上面注明的两个既有 lint warning。

## 待完成的人工回归

- [x] 无端口转发安装、冷启动并浏览四个底部页面。
- [ ] 保存供应商、模型和 API Key；重启后配置保留且 Key 不显示明文。
- [ ] 创建、继续、长按删除会话。
- [ ] 发送真实请求并继续多轮对话；停止一个运行中的 Agent。
- [ ] 模型调用读取、搜索、写入工作区；写入前出现审批。
- [ ] shell 与 HTTPS 获取被拒绝时 Agent 收到拒绝结果，允许时正常返回。
- [ ] Agent 调用 `update_plan` / `create_goal` / `save_workflow` 后原生运行中心出现数据。
- [ ] `.dsh/skills/<name>/SKILL.md` 能被列出并读取。
- [ ] `delegate_task` 返回独立子 Agent 的分析结果。
- [ ] `ask_user` 暂停 Agent 并显示原生回答弹窗，回答后继续执行。
- [ ] 启动、刷新和停止后台 Job，日志出现在私有工作区。
- [ ] 后台 Job 达到并发上限会被拒绝，达到超时会终止，离开应用进程后不残留无限任务。
- [ ] 从系统文件选择器导入文本附件并让 Agent 读取。
- [ ] 切换 DeepSeek/OpenAI、Anthropic 与 Gemini 协议各完成一次请求。
- [ ] 旋转、退到后台、进程重启后本地会话和工作区仍存在。
- [ ] Android 日志不包含 API Key。

## Security and resource checks

```bash
adb shell dumpsys package com.fengnanrui.dshandroid
adb shell dumpsys meminfo com.fengnanrui.dshandroid
adb shell top -b -n 1
adb shell dumpsys battery
```

确认 APK 只申请 INTERNET/ACCESS_NETWORK_STATE，没有 WebView、通用存储、相机、麦克风或定位权限。温控测试分别记录应用运行、USB 调试开启和关闭后的 CPU 与电池温度。

2026-08-14 的真机自动化结果仅属于当时版本，不代表本轮预发布构建。真实模型回归不把凭据写入测试代码，必须由测试者在手机上通过 Keystore 设置页输入自己的 API Key 后执行。
