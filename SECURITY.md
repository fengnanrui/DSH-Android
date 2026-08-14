# Security policy

请通过 GitHub Private Vulnerability Reporting 私下报告安全问题。报告和提交中不得包含真实 API Key、会话内容、私有工作区或设备标识。

安全边界：

- API Key 以 Android Keystore 中不可导出的 AES-GCM 密钥加密，应用禁用备份；
- 供应商地址必须使用 HTTPS，应用不允许明文流量；
- 文件工具通过 canonical path 校验限制在应用私有工作区；
- 写入、精确替换、shell、后台 Job 与网络获取默认逐次审批；
- shell 在 Android 应用 UID 沙箱内执行，默认限制 20 秒/64 KiB，并可在插件设置中收紧或放宽；
- 文件读取/写入限制 256 KiB，导入附件限制 10 MiB；
- 外部文件只经 Android 系统文件选择器导入，不申请相机、麦克风、定位或通用存储权限。

“全部自动允许”会放大模型提示注入造成的风险，仅应在可信任务中短时使用。
