# 2026-09 原生重构与功能边界

本轮目标是修复可复现问题、改善手机操作并建立回归证据，不把插件名字或设置入口当作功能等价实现。DSH Android 保持 Java 原生界面与端内运行时，不连接桌面端，不引入 WebView。上游许可证与署名继续保留在 THIRD_PARTY_NOTICES.md。

## 本轮代码变化

- 从 Activity 提取 SessionController，负责运行、跨会话队列、草稿、待处理审批及提问；Activity 重建只更换界面监听器。停止/失败会把未发送队列退回各自草稿，禁止删除运行或排队的会话。
- AgentRuntime 使用可取消的单运行状态；停止可中断审批/提问等待和模型请求。模型配置在每次运行开始固定；工具不仅在请求中筛选，实际执行时也重新检查预设和插件授权。
- ConversationHistory 修补被停止或中断的工具调用，避免下一轮携带缺少结果的协议历史。Anthropic/Gemini 的同轮工具结果合并，思考签名/原始内容块和 OpenAI reasoning_content 在本地往返中保留。
- 模型错误先去除凭据再截断，禁止携带密钥的请求自动跨站重定向；模型响应和网页/终端输出有大小上限。搜索跳过符号链接和已访问目录，避免目录循环与越界遍历。
- ManagedShell 在启动用户命令前取得独立进程组，停止时回收同组子进程；组内另有原生超时守卫，不只依赖 Java watchdog。后台 Jobs 按设置限制日志字节数，超限停止生产者。主动创建新 session 的恶意进程不在完整隔离承诺内。
- 手机 UI 使用系统栏、刘海和键盘真实 insets；去掉固定底部补白；窄屏标题省略、状态换行、长表单滚动。插件过滤复用视图，避免每次输入重建页面导致焦点/键盘丢失；修正深色输入框。

## 功能矩阵：不是上游等价表

| 能力 | Android 当前实现 | 尚未等价或待验收 |
| --- | --- | --- |
| 手机界面 | 原生会话、工作区、任务、设置；主题、插件搜索和模型表单 | 无完整英文界面；Android 9 真机与不同厂商系统需补测 |
| 模型 | OpenAI / Anthropic / Gemini 请求适配和自定义提供方 | 本轮协议测试使用 fixture，未使用真实付费 API 验证所有供应商/模型 |
| 插件 | 20 个原生实现，116 个明确标记仅兼容标识 | 不是 136 个可执行插件，更不是上游全部 JS 插件加载器 |
| 预设 | 四种不同的工具集合，可自定义 | PTC 是最多 8 步 JSON 编排，不是 TypeScript SDK；创造模式不是上游完整运行时自省 |
| 终端 | 受审批的单次 Android shell；超时/输出限制/进程组取消 | 无持久 PTY、交互终端、跨命令 shell 环境或完整 Linux 工具链 |
| 网络 | 有限额 HTTPS 获取 | 不等于搜索引擎、浏览器自动化或完整网页检索插件 |
| 子 Agent | 独立无工具分析请求 | 不是递归多工具子代理调度系统 |
| 生命周期 | 跨 Activity 重建保留前台运行；中断历史可再继续 | 没有长期后台 foreground service，也不保证系统杀进程后自动续跑 |
| Jobs | 并发、日志大小、超时、取消与关闭清理 | 不是系统调度器；旧日志文件保留供用户查看，不自动删除 |

## 三个仓库的关联

- Harness fork 默认分支：<https://github.com/fengnanrui/deepseek-harness>。修复小视口浮层、滚动弹窗与复制反馈的异步生命周期；按官方暂不接外部 PR 的规则提交 [Discussion #7335](https://github.com/deepseek-ai/deepseek-harness/discussions/7335)。
- Desktop fork：<https://github.com/fengnanrui/DeepseekHarness-Desktop>。复用已有 fork 并同步上游，然后修复 Quick Tunnel 启动清理和移动桥接界面的缩放、触摸、焦点、减弱动画；提交 [PR #491](https://github.com/dataelement/dsh-desktop/pull/491)。
- Android 借鉴这些生命周期、视口和资源所有权边界；没有把 Desktop 手机桥接网页装进 APK，也没有宣称运行了上游 Node runtime。

## 验收原则

测试命令、版本和已执行结果见 TESTING.md。模拟器 CPU/内存只作本轮应用回归参考，不能证明真实手机不发烫。本轮未检测到 USB 真机，旧版真机记录也不能代替新版验收。真实模型调用和真机温控完成之前，本版本保持预发布状态。
