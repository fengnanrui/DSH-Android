# Testing and acceptance checklist

## Automated checks

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
ANDROID_HOME=/path/to/android-sdk ./gradlew connectedDebugAndroidTest
```

单元测试核对供应商注册表、HTTPS 默认值、三种原生协议、工具边界、20 个原生插件与 116 个兼容标识，并验证四类预设的真实能力差异。七项设备测试断言不存在 WebView，遍历会话和运行中心，实际执行 PTC 原生步骤与 Job 并发限制，同时验证工具调用上下文持久化及损坏会话自动恢复。

## Functional regression

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

2026-08-14 真机自动化已通过。真实模型回归不把凭据写入测试代码，必须由测试者在手机上通过 Keystore 设置页输入自己的 API Key 后执行。
