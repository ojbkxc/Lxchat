# Lxchat 安卓环境适配审查报告（/goal 全量检查修复）

日期：2026-09-08 ｜ 提交：02256a3 + 0d30bfa ｜ CI：main@0d30bfa build = success

## 结论

本轮 /goal（"全量检查修复安卓环境不匹配项"）已完成。修复分两批提交，
全部经 GitHub Actions（ci.yml build）验证通过。以下为问题清单与处置。

## 已修复（Stop hook 点名项全部闭环）

| # | 问题 | 根因 | 修复 | 提交 |
|---|------|------|------|------|
| 1 | 导入工具 HTTP 请求完全绕过凭据明文守卫 | ImportedToolProvider.executeHttp 直连 HttpURLConnection，绕过代理/SSRF 屏障/凭据守卫 | 改走 HttpClient.getText/postTextResponse | 02256a3 |
| 2 | OAuth token 表单（code_verifier/refresh_token/client_secret）明文发送无拦截 | guardCleartextCredentials 只检查 header，body 凭据是盲区 | 新增 sensitiveBody 参数；接入 OAuthPkceUtils.postFormToken、McpOAuth.tokenRequest | 02256a3 |
| 3 | OneBot HTTP API Bearer 头明文发送无拦截 | AiocqhttpApi.postJson 自行建请求 | 接入 guardCleartextCredentials | 02256a3 |
| 4 | cleartext 报错后错误气泡给"重试"动作（重试永远失败） | 守卫 IOException 落入 Unknown，未归入 Configuration | StreamTermination.emitTransportError 识别该异常 → Configuration，UI 出"修复端点"动作 | 02256a3 |
| 5 | Git 工具首次调用必败 | 沙箱镜像无 git 二进制 | withRepo 前探测 + 自动 apkInstall("git")，失败不阻断 | 02256a3 |
| 6 | isLocalHost 把公网 IPv6 字面量（2606:4700::1111 等无点地址）误判为本地主机 → 明文放行 | `!h.contains('.')` 分支在 IPv6 判断之前 | 含冒号先返回 false；补 3 个单测（HttpClientLocalHostTest） | 0d30bfa |
| 7 | network_security_config 与 isLocalHost 语义不一致 | `.local` 前导点是非法 NSC 写法（等于未放行）；缺 ::1/RFC1918 | 改 `*.local` 合法通配；补 ::1 与常用 RFC1918/CGNAT 地址；XML 注释文档化 NSC 不支持 CIDR/裸主机名的边界 | 0d30bfa |
| 8 | 短信命令 smsf#shell 在无 root/无 Shizuku 设备上必然失败且无指引 | 直接执行，错误信息裸露 | 执行前探测：未装→Play 链接；未运行→启动 Shizuku；未授权→去授权 | 0d30bfa |
| 9 | 网络 TTS（Bearer apiKey）明文发送无拦截 | TtsManager.synthesizeNetSpeech 直连 HttpURLConnection | 接入 guardCleartextCredentials（Authorization: Bearer） | 0d30bfa |
| 10 | Ollama 默认 http://localhost:11434 在 Android 上不可达（localhost=手机自身） | 默认值面向桌面场景 | 设置页 Base URL 下加 LAN/Tailscale 引导文案（中英）；fetchModels 复用 defaultBaseUrl；保留 localhost（adb forward/Termux 场景合法）并注释说明 | 0d30bfa |

## 评估后保留原状（非缺陷）

- **SystemCleanToolProvider ROM 专属路径**（/system/bin/ClearBox、/data/adb/...）：
  本就是探测式设计 —— root + `test -x` 探测，普通 ROM 上工具集整体隐藏、
  不报错、不崩溃。属刻意的降级行为。已补文档注释（0d30bfa）说明 Magisk
  模块布局来源与"换模块只改一处"的路径收拢设计。
- **Ollama localhost 默认值不改为 LAN IP**：任意写死的 IP 不会比 localhost
  更正确；正确做法是引导用户填写（已做）。

## 验证

- `git status`：工作区干净，无未提交改动
- check-runs API：0d30bfa → completed / success
- Release v1.0.2 已发布（02256a3 前身 b2e4c7e 构建），双 APK 资产齐全
