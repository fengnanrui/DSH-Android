# DSH Android

DSH Android 是参考 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 与 [DSH Desktop](https://github.com/dataelement/dsh-desktop) 交互方式重新实现的**原生、独立手机 Agent**。它不是网页套壳，不含 WebView，不连接电脑端 Harness，安装 APK 后即可在手机上使用。

本项目是独立社区项目，与 DeepSeek AI、DataElement 无隶属或背书关系。

当前开发版本为 **0.2.0-rc.1**。这是原生运行时和手机界面的加固预览版，**不是上游全部功能的等价移植**。已实现能力、缺口和本轮验证边界见 [重构与功能矩阵](docs/REFACTOR_2026-09.md)。已发布的 0.1.9 保留，不用不同构建覆盖同一版本。

## 原生功能

- 原生 Android 会话、聊天、附件、工作区、计划、目标、后台 Jobs 与可重复 Agent 工作流；
- DeepSeek、OpenAI、Anthropic、Gemini、xAI、Moonshot、MiniMax、智谱、Mistral、OpenRouter、Groq、Together 及自定义提供方管理，可切换 OpenAI / Anthropic / Gemini 协议；
- 应用内维护 136 个插件 ID，其中 20 个已有 Android 原生实现并可真实启停，其余 116 个明确显示为“仅兼容标识”；此目录不是上游当前版本的完整插件实现；
- 标准、PTC、极简、创造四套能力不同的内置 Agent 预设：PTC 提供最多 8 步原生 JSON 工具编排，不是上游 TypeScript Code Mode SDK；创造模式提供插件检查、配置导出和预设创建工具；
- 本机 Agent 多轮工具循环，最多 8 个工具回合，可停止；
- 工作区目录/读取/写入/精确替换/全文与文件搜索、单次 Android shell、后台 Jobs、HTTPS 获取、时间、计划、目标、工作流、技能、配置清单、用户提问和轻量子 Agent 工具；后台 Jobs 具有并发、超时、日志限额和进程组回收；
- 写文件、shell 和网络访问的原生审批弹窗及三档权限模式；
- `.dsh/skills/<name>/SKILL.md` 本地技能；
- Android 系统文件选择器导入附件，不申请通用存储权限；
- API Key 由不可导出的 Android Keystore AES-GCM 密钥加密，应用禁用系统备份；
- 原子写入的本地 JSON 会话、自动备份与损坏文件保留；工具调用协议上下文可跨重启恢复；前台任务可跨 Activity 重建保留，队列与草稿按会话隔离；另有无密钥配置导出、浅色/深色/跟随系统外观，以及 `/new`、`/plan`、`/settings`、`/stop`、`/help` 命令；
- Android 9 / API 28 及以上。

上游的 Node.js、Electron、`node-pty` 和浏览器前端没有被塞进 APK；相应能力用 Android/Java 原生代码重新实现，适配了手机的生命周期、沙箱、密钥和审批模型。

## 使用

1. 安装并打开 APK。
2. 在“设置 → 模型”中选择或添加提供方，点击卡片编辑 HTTPS 地址、模型与 API Key。
3. 回到“会话”，直接给手机上的 Agent 发消息。
4. 导入和生成的文件在“工作区”查看；计划、目标、Jobs 与工作流在“任务”页管理。

默认只对当前应用私有工作区执行工具。高风险工具每次询问；切换自动批准模式前请理解模型提示注入和命令执行风险。

Shell 的工作目录不是额外的安全沙箱：命令具有本应用 UID 的权限。进程组和超时用于资源回收，不是对恶意命令的完整隔离。应用进程被系统回收后不会自动续跑 Agent；历史可以恢复，正在执行的请求需要重新发起。

## 构建与测试

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，发布 APK 位于 `app/build/outputs/apk/release/app-release.apk`。项目使用 compile/target SDK 35、min SDK 28、Java 17、AGP 8.6.1 和 Gradle 8.9。

目前 release 构建沿用调试签名配置，仅用于测试分发；不是正式生产签名。签名不一致时不要卸载已有应用来强行升级，以免丢失本地会话和 Keystore 密钥。

验收清单见 [docs/TESTING.md](docs/TESTING.md)，真机记录见 [docs/DEVICE_VALIDATION.md](docs/DEVICE_VALIDATION.md)。

## English summary

DSH Android is a native, standalone Android agent inspired by the interaction model of DeepSeek Harness and DSH Desktop. It contains no WebView and requires no desktop or remote Harness service. Sessions, workspaces, approvals, plans, skills, direct model-provider calls, encrypted credentials and the agent tool loop run in the Android application itself.

## License and upstream rules

本项目采用 [MIT License](LICENSE)。上游署名、商标说明及未复制代码的边界记录在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。仓库不得提交 API Key、用户会话、私有工作区或设备标识。
