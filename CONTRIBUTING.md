# Contributing

Issues 与 pull requests 均欢迎。提交前：

1. 不得包含 API Key、凭据、会话导出、用户工作区或设备标识。
2. 保持 Android 9 / API 28 兼容，主界面不得引入 WebView 或电脑端服务依赖。
3. 新工具必须限制到工作区，并为副作用设计明确审批。
4. 供应商传输必须使用 HTTPS；密钥不得写入日志或普通偏好。
5. 运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`，行为变化同步更新测试文档。
