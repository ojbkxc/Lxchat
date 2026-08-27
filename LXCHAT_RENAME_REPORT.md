# LxChat 全量改名完成报告

## 一、执行概要
- 源：原项目本地副本（**未改动，保留作备份**）
- 目标目录：`C:\GitHub\Lxchat`（当前工作仓库，已连接远程 `github.com/ojkbxc/Lxchat.git`）
- 目标：把软件内所有 `agora` / `Agora` 标识符全部替换为 `lxchat` / `LxChat`，使反编译 APK 中不可见任何原标识
- 编码：CRLF 行尾全程保留，未做转换
- 包名 `com.lxseek.chat` 不变（符合原有约定）

## 二、替换规则
| 原 | 新 | 适用 |
|---|---|---|
| `Agora` | `LxChat` | 类名、显示名、Manifest 组件、URL 首部大写 |
| `agora` | `lxchat` | 内部标识符（DB 名、Keystore 别名、HKDF、原生库、扩展名、XML 标签、深链、媒体目录） |
| `AGORA` | `LXCHAT` | 全大写常量、服务端环境变量 |
| `ojbkxc/Agora` | `ojbkxc/Lxchat` | 实际远程仓库名；README 内上游链接 `ojkbxc/Agora` 保留作开源署名 |

## 三、改动统计
- 文本文件修改：**205 个**（首轮 199 + 第二轮修正 6）
- 无扩展名 / 配置文件补充修改：**2 个**（`fastlane/Fastfile`、`server/crash/nginx-crash.location`）
- 文件 / 目录重命名：**21 个**
- 源码 / 资源 / 配置残留 `agora`：**0**（grep 全面验证，跳过二进制）
- README 文案保留上游署名："LxChat 是基于 Agora 的派生项目"（不进入 APK，不影响反编译目标）

## 四、关键标识符映射（节选）
- **类**：`AgoraApplication`→`LxChatApplication`、`AgoraForegroundService`→`LxChatForegroundService`、`AgoraTheme`→`LxChatTheme`、`AgoraMotionPolicy`(+`Local`/`Provide`/`resolve`)→`LxChat*`、`AgoraHaptics`(+`Local`/`NoOp`/`remember`/`Platform`)→`LxChat*`、`AgoraVM`/`AgoraAPI`/`AgoraSSE`/`AgoraUI`/`AgoraTTFT`→`LxChat*`
- **字符串常量**：`agora_secrets_v1`→`lxchat_secrets_v1`、`conch-agora-v1`→`lxchat-conch-v1`、`agora_db`→`lxchat_db`、`agora_export_version`→`lxchat_export_version`、`<agora_runtime_context>`/`<agora_user_message>`→`<lxchat_*>`、`agora_responded`→`lxchat_responded`
- **原生库**：`agora_llama`→`lxchat_llama`、`agora_proot`→`lxchat_proot`（CMakeLists.txt + `loadLibrary` 已一致）
- **深链**：`agora://`→`lxchat://`
- **资源 / 资产**：`agora*.png` / `agora*.svg` → `lxchat*`（6 个文件）
- **URL**：`ojbkxc/Agora` → `ojbkxc/lxchat`（9+ 处）
- **工程 / CI**：`settings.gradle.kts` 的 `rootProject.name = "LxChat"`、`mkdocs.yml` 的 `site_name: LxChat User Manual` / `repo_url: .../ojbkxc/lxchat`、CI 产物名 `lxchat-*`、`fastlane` 元数据已同步

## 五、一致性验证（均已通过）
- Manifest 组件名 `.LxChatApplication` / `.service.LxChatForegroundService` / `Theme.LxChat*` 与类文件一致
- CMake 库名 `lxchat_llama` / `lxchat_proot` 与 Kotlin `loadLibrary` 一致
- 数据锚点 `lxchat_db` / `lxchat_export_version` / `.lxchat` / `lxchat_runtime_context` 全部更新
- `app_name` = `LxChat`
- 关键旧名残留扫描（`AgoraApplication` / `agora_db` / `agora_llama` / `Theme.Agora` 等）：**0**

## 六、后续必做（改名之外，与构建 / 发布相关）
1. **submodule 初始化**：`thirdparty/llama.cpp`、`thirdparty/proot` 的内容已随仓库提交，CI 构建正常，无需额外初始化。
2. **NDK 原生库重编**：已完成并通过 CI 验证（`:app:buildCMakeDebug[arm64-v8a][lxchat_llama,lxchat_proot]` 构建成功）。
3. **GitHub 远程仓库**：已完成——远程 `github.com/ojkbxc/Lxchat.git` 已建立并持续推送，CI 正常。
4. **server 部署配置**：`server/crash`、`server/rating` 的环境变量与路径已改 `LXCHAT` / `lxchat`，部署脚本需同步（不影响 APK）。

## 七、复核命令
```bash
cd C:/GitHub/Lxchat
grep -rIin "agora" . | grep -v "/.git/"   # 期望：仅命中 README.md / README_CN.md / 本报告（上游署名与历史记录）
```
