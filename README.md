# DSH Android

DSH Android 是参考 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 与 [DSH Desktop](https://github.com/dataelement/dsh-desktop) 交互方式重新实现的**原生、独立手机 Agent**。它不是网页套壳，不含 WebView，不连接电脑端 Harness，安装 APK 后即可在手机上使用。

本项目是独立社区项目，与 DeepSeek AI、DataElement 无隶属或背书关系。

## 原生功能

- 原生 Android 会话、聊天、附件、工作区、计划、目标、后台 Jobs 与可重复 Agent 工作流；
- DeepSeek、OpenAI、Anthropic、Gemini、xAI、Moonshot、MiniMax、智谱、Mistral、OpenRouter、Groq、Together 及自定义提供方管理，可切换 OpenAI / Anthropic / Gemini 协议；
- 与上游当前主组合对齐的 136 个稳定插件 ID 清单、搜索、分类、启停状态，以及终端、Agent 循环和网页搜索配置；
- 标准、PTC、极简、创造四套内置 Agent 预设，以及基于任一能力组创建或复制自定义预设；
- 本机 Agent 多轮工具循环，最多 8 个工具回合，可停止；
- 工作区目录/读取/写入/精确替换/全文与文件搜索、Android shell、后台 Jobs、HTTPS 获取、时间、计划、目标、工作流、技能、配置清单、用户提问和轻量子 Agent 工具；
- 写文件、shell 和网络访问的原生审批弹窗及三档权限模式；
- `.dsh/skills/<name>/SKILL.md` 本地技能；
- Android 系统文件选择器导入附件，不申请通用存储权限；
- API Key 由不可导出的 Android Keystore AES-GCM 密钥加密，应用禁用系统备份；
- 本地 JSON 会话与运行中心持久化、无密钥配置导出、消息排队、浅色/深色/跟随系统外观，以及 `/new`、`/plan`、`/settings`、`/stop`、`/help` 命令；
- Android 9 / API 28 及以上。

上游的 Node.js、Electron、`node-pty` 和浏览器前端没有被塞进 APK；相应能力用 Android/Java 原生代码重新实现，适配了手机的生命周期、沙箱、密钥和审批模型。

## 使用

1. 安装并打开 APK。
2. 在“设置 → 模型”中选择或添加提供方，点击卡片编辑 HTTPS 地址、模型与 API Key。
3. 回到“会话”，直接给手机上的 Agent 发消息。
4. 导入和生成的文件在“工作区”查看；计划、目标、Jobs 与工作流在“任务”页管理。

默认只对当前应用私有工作区执行工具。高风险工具每次询问；切换自动批准模式前请理解模型提示注入和命令执行风险。

## 构建与测试

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。项目使用 compile/target SDK 35、min SDK 28、Java 17、AGP 8.6.1 和 Gradle 8.9。

验收清单见 [docs/TESTING.md](docs/TESTING.md)，真机记录见 [docs/DEVICE_VALIDATION.md](docs/DEVICE_VALIDATION.md)。

## English summary

DSH Android is a native, standalone Android agent inspired by the interaction model of DeepSeek Harness and DSH Desktop. It contains no WebView and requires no desktop or remote Harness service. Sessions, workspaces, approvals, plans, skills, direct model-provider calls, encrypted credentials and the agent tool loop run in the Android application itself.

## License and upstream rules

本项目采用 [MIT License](LICENSE)。上游署名、商标说明及未复制代码的边界记录在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。仓库不得提交 API Key、用户会话、私有工作区或设备标识。
