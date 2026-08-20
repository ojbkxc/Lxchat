# AGENTS.md —LxChat 项目代理工作指引

> 本文件供 AI 编码代理（含未来会话）进入项目时**首先自读**，快速对齐项目定位、当前进度、架构契约与下一步任务，然后**继续完善未完成的代码**。
> 优先级：本文件> `ARCHITECTURE.md`（架构文档，490 行）> `README.md` / `README_CN.md`。

---

## R0. 强制规则（MANDATORY，不可绕过）

> 本节为*最高优先级的强制约束*，凌驾于一切其他指引之上。违反即视为流程失败。

1. **每次会话必须先自读本文件**：进入项目后，在执行任何写代码搜索/构建动作之前，必须先 `read` 完整 `AGENTS.md`，对齐「当前进度」「下一步任务」「接口契约」。
2. **每次会话结束前必须回写本文件**：无论本次完成了几项任务（含 0 项，即仅排查/失败），在结束前**必须**用`edit`/`write` 更新本文件至少一处：
   - **必须**更新「— 变更日志」追加一行（最新在上），记录本次做了什么、改了哪些文件、是否通过验证、下一步建议。
   - **必须**更新「— 当前进度」与「— 下一步任务」的勾选状态以反映真实状态（新完成的挪到「已完成」区，新发现的问题加入「已知小问题」）。
   - 若改动了接口契约，**必须**同步更新「— 关键接口契约」。
   - 若改动了目录结构或新增删除文件，**必须**同步更新「— 仓库结构」。
3. **本文件是单一事实源（single source of truth）*：当本文件与代码、与 `ARCHITECTURE.md`、与口头描述出现矛盾时，**先以代码为准**，然后*立即回写本文件*消除漂移；禁止让本文件与代码长期不一致。
4. **不得删除或弱化本节*：任何对「搂R0 强制规则」的删减、降级、加「视情况而定」修饰，都需用户明鐟同意；代理自身不得自行放宽。
5. **跟进是义务而非可选*：即使用户未要求「更改AGENTS.md」，每次会话结束前也必须执行回写；用户明确说「不用更新」时才可跳过，并在变更日志注明「依用户要求跳过本次回写」。
6. **语言只保留中英文**（MANDATORY）：App 的语言资源**从* `values/`（英文）为`values-zh/`（简体中文）。**禁止**新增 `values-es`/`values-fr`/`values-de`/`values-ru`/`values-ja`/`values-ko`/`values-ar`/`values-vi`/`values-pt-rBR`/`values-zh-rTW` 等其他语言目录。语言选项在`SettingsLanguagePage.kt` 为`MainActivity.attachBaseContext()` 中声明，两者必须同步（当前为`system`/`en`/`zh`）。
7. **不打包自定义字体**（MANDATORY）：**禁止**在`res/font/` 下添加`.ttf`/`.otf` 文件。UI 字体使用 `FontFamily.Default`（系统默认），代码终端字体使用 `FontFamily.Monospace`（系统等宽）。字体定义在 `ui/theme/Type.kt`（`OutfitFamily`/`MonoFamily`）。
8. **编译验证必须提交到GitHub 上编译*（MANDATORY）：本地为离线环境，缺Android SDK/NDK/CMake 工具链，**无法** `./gradlew assembleFdroidRelease`。因此*任何代码改动后的编译验证必须通过 `git commit && git push` 提交到GitHub**（`origin = https://github.com/ojbkxc/lxchat.git`，分支`master`），用GitHub CI（`.github/workflows/build.yml`，见 搂R2）执行构建。**禁止**在未 push 到GitHub 编译通过前声称某子任务「完成已验证」。
9. **通过 GitHub 编译报错迭代修复**（MANDATORY）：push 后若 GitHub CI 编译/测试失败，**必须**读取 CI 日志中的报错，据报错本地修复后**再次 commit & push**，循环直至CI 全绿。**不得**跳过 CI 失败直接推进下一子任务；**不得**用`@Suppress`/注释掉测试降低 lint 阈值等方式绕过 CI 报错（除非用户明确同意）。CI 全绿是子任务完成的**唯一**编译验证判据。
10. **自动推进项目（auto-continue，默认行为）**（MANDATORY）：用户说「自动继续。「继续。「auto」或未明确叫停时，代理**必须自主连续推进**项目任务，不得每完成一小步就停下来询问下一步。具体要求：
    - 进入项目后按 搂0 流程**自主**挑选下一个最高优先级的最小可独立交付子任务并开工，不等用户逐项指派。
    - 单个子任务完成后**立即**开始下一个，无需请求许可；仅在遇到「方向性分歧」「破坏性操作」「违反硬约束」「信息严重不足且无法合理推断」时才用 `question` 工具询问用户。
    - 推进过程为**主动**资搂R2.3 CI 修复闭环、搂R0 回写，不要等用户提醒。
    - 用户未说「自动继续」时也鼓励减少不必要的中途提问，但可在阶段切换时简要汇报进度；用户说「自动继续」后到**连续作业**直到任务全部完成或遇阻才停下汇报。
    - 停下汇报时应附「已完成的/ 正在做的 / 下一步打算做的」三段式摘要，便于用户一句话继续（如「继续」「换方向」「停」）。

---

## R2. GitHub CI 编译验证策略（MANDATORY，配后搂R0.8–R0.9）

> 本节落实 搂R0.8/R0.9 的「提交到 GitHub 编译 + 据报错修复」闭环。本地离线不可编译，GitHub CI 更**唯一**编译验证通道。

### R2.1 CI 触发条件
- **push tag `v*`**（如 `v1.0.0`）或手动 `workflow_dispatch` 触发 `.github/workflows/build.yml`。
- CI 在GitHub-hosted runner（ubuntu-latest，可联网拉SDK/NDK/依赖）上执行，规避本地离线缺工具链问题。
- 流水线结构：`get-version` →`build-android` →`release`（详见搂R2.2）。

### R2.2 CI 必须执行的步骤（全绿才算通过，
```
# .github/workflows/build.yml 执行流程
1. get-version: 件git tag 提取 TAG (v1.0.0) 和VERSION (1.0.0)
2. build-android:
   - checkout (submodules: recursive) —拉取 llama.cpp + proot 子模块
   - setup JDK 21 (temurin) + Android SDK + NDK 28.2.13676358
   - 恢复签名密钥 (KEYSTORE_BASE64 secret →local.properties)
   - ./build-proot.sh force —构建 PRoot 原生二进到(libproot_*.so, libtalloc.so)
   - ./gradlew -p build-logic test —构建插件测试
   - ./gradlew verifyKotlinFileSize —源码大小策略 (每文件≤999 血
   - ./gradlew assembleFdroidRelease —构建 F-Droid Release APK
   - 重命后 app-fdroid-release.apk →LxChat-v{VERSION}-android-arm64-v8a.apk
3. release: gh release create —上传 APK 到GitHub Release
```

### R2.3 据报错修复的迭代流程（每次push 后必走）
1. `git push origin master`（或 `git push origin v1.0.0` 触发发版）。
2. 用`gh run watch` 或浏览器查看 `https://github.com/ojbkxc/lxchat/actions` 的运行结果。
3. 若失败：`gh run view --log-failed` 取报错日志，定位首个 `error:` / `FAILED` / `e: file://` 行。
4. 本地按报错修代码（修 import/类型/资源引用/Composable 签名等），*为*绕过（不 `@Suppress`、不删测试、不降低 lint 阈值）。
5. `git commit && git push`，回到步骤2，直至CI 全绿。
6. CI 全绿后才能在 搂4/搂6 勾选该子任务「完成」并在搂9 变更日志注明「CI 全绿验证通过」。

### R2.4 本地可做的静态检查（push 前自检，减少CI 往返）
- **`git status` 确认无残留未 commit 修改**：会话开始前和commit 前各执行一次，确保所有修改的文件都被 staged。**这是最常见的CI 失败根因之一**——修改了文件但忘记commit，CI 用的是旧版本。
- 人工 review：import 路径、Composable 签名、资源引用（`R.string.*`/`R.drawable.*`）、`@Composable` 注解。
- **Kotlin 类型检查（本地无法编译，必须人工查，*）
  - `suspend` 函数/lambda：鐟记lambda 类型匹配（`suspend (T) -> Unit` vs `(T) -> Unit`）。`Flow.emit()` / `MutableSharedFlow.emit()` 更suspend，不能在普选lambda 中调用。
  - `nullable` 类型：鐟记`String?` vs `String` 传递正确。`StateFlow<T?>.value` 返回 `T?`，传给非空参数需加`?: return` 成`!!`。
  - 新增参数：鐟认所有调用点都传了正确类型的参数。
- **新增字符串资源*：鐟记`values/strings.xml`（en） `values-zh/strings.xml`（zh）*都*添加了同后key。
- **新增设置项*：鐟记`SettingsPreferenceSchema` + `SettingsManager` + `SettingsRepository` + UI 四层**都*添加了。
- 确认无`R.font.*` 引用（搂R0.7 禁止自定义字体）。
- 确认无非 en/zh 的语言资源目录或语言选项（搂R0.6）。
- 确认 Kotlin 文件不超过999 行（`./gradlew verifyKotlinFileSize` 基线）。

### R2.5 CI workflow 维护
- 若新增依赖或改变构建配置（NDK 版本、ABI、flavor），同步更新 `.github/workflows/build.yml` 为`app/build.gradle.kts`。
- 若新增signing secret，在 GitHub repo Settings →Secrets 配置后更改workflow 的`env` 映射。

---

## 0. 进入项目后的标准流程（必读）

1. **通读本文件*（尤其是「搂R0 强制规则」「当前进度」「下一步任务」「编码约定」五节）。
1b. **`git status` 检查残留修支*：若工作目录有未 commit 的修改（来自前次会话遗漏），先理解其内容并commit，再开始新工作。**不要**在新工作开始前 `git stash` 成`git checkout -- .` 丢弃前次修改——先搞清楚是什么、是否需要保留。
2. 按「下一步任务」的优先级顺序挑选一为**最小可独立交付**的子任务开工。
3. 开工前用`read`/`grep`/`glob` 阅读相关已有代码，*复用既有 Composable、ViewModel、Repository 与命后*，不要另起炉灶。
4. 每完成一个子任务：执血搂R2.4 静态检查清单，然后 `git add -A && git status` 确认所有修改已 staged，`git commit && git push` 触发 CI 验证。
5. **回写本文件*（强制，见搂R0）：更新「当前进度」「下一步任务」勾选状态，并在「变更日志」追加一行。
6. **不要**主动 `git commit`，除非用户明确要求。**不要**写未经请求的 README/文档。**不要**加注释除非用户要求。
7. **会话结束前再次鐟记搂R0 的回写已执行**；若未执行，补做后再结束。

---

## 1. 项目定位（一句话）

LxChat 更**BYOK（Bring Your Own Key）LLM 客户端* —Android 原生应用（Kotlin + Jetpack Compose），支持多LLM 提供商、智能代理工作流、本在LLM 推理（llama.cpp via NDK）、远程设备控制。所有数据本地存储，无遥测、无追踪。MIT 许可证。

## 2. 硬约束（任何改动都不得违反）

| 维度 | 约束 | 验证方式 |
|---|---|---|
| 应用 ID | `com.lxseek.chat` | `app/build.gradle.kts` |
| ABI | **从`arm64-v8a`** | `ndk { abiFilters }` |
| SDK | minSdk 24 / targetSdk 36 / compileSdk 36 | `defaultConfig` |
| NDK | `28.2.13676358` | `ndkVersion` |
| 语言 | Kotlin 2.3.21 + Compose BOM 2026.05.01 | `gradle/libs.versions.toml` |
| i18n | **从en + zh**（搂R0.6）| `res/values*/` 目录 |
| 字体 | **无自定义字体**（搂R0.7）| `res/font/` 不存在|
| 源码大小 | 每Kotlin 文件 ≤999 血| `./gradlew verifyKotlinFileSize` |
| 版本 | versionName `1.0.59` / versionCode `60` | `defaultConfig` |
| 产物命名 | `LxChat-v{VERSION}-android-arm64-v8a.apk` | CI `build.yml` |
| 许可译| MIT | `LICENSE` |

新增依赖前先评估对APK 体积的影响；优先使用 `gradle/libs.versions.toml` 版本目录统一管理。

## 3. 仓库结构与模块划到

```
LxChat/
├── AGENTS.md                          # 本文件（代理工作指引）
├── ARCHITECTURE.md                    # 架构文档，90 行）
├── README.md / README_CN.md           # 英文/中文说明
├── build.gradle.kts                   # 顶层构建（声明插件）
├── settings.gradle.kts                # include(":app") + includeBuild("build-logic")
├── gradle.properties                  # Gradle 配置
├── gradle/libs.versions.toml          # 版本目录（AGP/Kotlin/Compose/Room 等）
├── build-proot.sh                     # PRoot 原生二进制构建脚本（232 行）
├── mkdocs.yml                         # MkDocs 文档配置（en + zh）
├── app/                               # 为Android 应用模块（唯一 Gradle 模块）
─  ├── build.gradle.kts              # 应用构建配置（flavors: play + fdroid）
─  ├── proguard-rules.pro            # ProGuard 规则
─  ├── schemas/                      # Room DB schema 快照（v10–v22）
─  └── src/
─      ├── main/                      # 主源码
─      ─  ├── AndroidManifest.xml
─      ─  ├── assets/               # Provider 图标（SVG/PNG）
─      ─  ├── cpp/                  # JNI 原生代码（CMake）
─      ─  ─  ├── CMakeLists.txt    # 构建 lxchat_llama + lxchat_proot
─      ─  ─  ├── llama_jni.cpp     # llama.cpp JNI 绑定
─      ─  ─  ├── llama_chat_jni.cpp
─      ─  ─  └── proot_jni.cpp     # PRoot JNI stub
─      ─  ├── java/com/lxseek/chat/
─      ─  ─  ├── LxChatApplication.kt   # Application（持有AppContainer）
─      ─  ─  ├── MainActivity.kt       # 唯一 Activity（Compose 入口）
─      ─  ─  ├── api/               # LLM Provider 适配器（39 文件）
─      ─  ─  ─  ├── LlmProvider.kt    # Provider 接口 + StreamEvent
─      ─  ─  ─  ├── HttpClient.kt     # OkHttp 单例 + SSE
─      ─  ─  ─  ├── openai/           # OpenAI/DeepSeek/Qwen/OpenRouter/Groq/Custom
─      ─  ─  ─  ├── anthropic/        # Anthropic Claude
─      ─  ─  ─  ├── gemini/           # Google Gemini
─      ─  ─  ─  ├── ollama/           # 本地 Ollama
─      ─  ─  ─  └── local/            # llama.cpp 本地推理
─      ─  ─  ├── data/              # Room + DataStore + Repository，9 文件，
─      ─  ─  ├── model/             # 数据模型 / DTO，7 文件，
─      ─  ─  ├── viewmodel/         # ViewModel + 生成控制器（92 文件）
─      ─  ─  ├── ui/                # Compose UI，22 文件，
─      ─  ─  ─  ├── chat/          # 聊天界面，3 文件，
─      ─  ─  ─  ├── settings/      # 设置界面，7 文件，
─      ─  ─  ─  ├── theme/         # Type.kt / Theme.kt / Color.kt， 文件，
─      ─  ─  ─  ├── tasks/         # 任务历史， 文件，
─      ─  ─  ─  ├── onboarding/    # 欢迎引导， 文件，
─      ─  ─  ─  └── components/    # 通用组件， 文件，
─      ─  ─  ├── tool/              # 工具提供者（25 文件）

─      ─  ─  ├── service/           # 前台服务 + WorkManager， 文件，
─      ─  ─  ├── mcp/               # MCP 协议客户端（4 文件）
─      ─  ─  ├── sandbox/           # 沙盒接口
─      ─  ─  ├── automation/        # 任务、循环、调度
─      ─  ─  ├── di/AppContainer.kt # 手动 DI 容器
─      ─  ─  └── util/              # 工具类（CrashReporter / AppExecutors / ErrorSanitizer / TtsManager / SshClient 等）
─      ─  └── res/                   # 资源
─      ─      ├── values/            # 英文（默认）—7 为xml
─      ─      ├── values-zh/         # 简体中改—6 为xml
─      ─      ├── values-night/      # 夜间主题
─      ─      ├── drawable/          # 图标
─      ─      ├── raw/               # 欢迎视频（MP4）
─      ─      └── xml/               # backup/data extraction rules
─      ├── fdroid/                    # F-Droid flavor（PRoot 沙盒）
─      ├── play/                      # Google Play flavor（无 PRoot）
─      └── test/                      # 单元测试
├── server/                            # 服务端代码
─  ├── rating/                        # 评分提交 API（Python/SQLite, port 8091）
─  └── crash/                         # 崩溃报告接收（Python/JSONL, port 8092）
├── thirdparty/                        # 第三方原生依资
─  ├── llama.cpp/                     # git submodule
─  ├── proot/                         # git submodule
─  └── talloc/                        # 内联源码
├── build-logic/                       # Gradle included build（字节码修复 + 源码大小策略）
├── docs/                              # MkDocs 用户手册（en + zh）
├── fastlane/                          # fastlane 自动化（Fastfile/Appfile/Gemfile + 元数换en-US + zh-CN）
─  ├── Fastfile                       # lane 定义（build_fdroid/build_play/github_release/validate_metadata/generate_changelog/release，
─  ├── Appfile                        # package_name("com.newoether.lxchat")
─  ├── Gemfile                        # fastlane Ruby 依赖
─  └── metadata/android/             # F-Droid 元数据（en-US + zh-CN，含 changelogs + screenshots）
├── scripts/                           # 辅助脚本（round_icon.py）
├── config/                            # 源码大小基线配置
└── .github/workflows/
    ├── build.yml                      # CI/CD: 构建 APK + GitHub Release
    ├── ci.yml                         # PR/push 编译检查
    ├── fastlane.yml                   # fastlane 元数据验证（PR/push 触发）
    └── mkdocs.yml                     # 文档部署到GitHub Pages
```

**数据流*：`UI (Compose) →ViewModel →Repository →(Room/DataStore | LlmProvider →OkHttp SSE | LlamaEngine JNI)`；工具调用经 `tool/`；后台任务经 `service/` + WorkManager。

## 4. 当前进度（截至2026-08-20）

### ✅已完成
- **任务3 启动时自动下载中文 ASR 模型**（2026-08-20，本次会话，coding-engineer team-mate）：在 `AppContainer.startProcessServices()` 新增独立后台协程，启动时检查中文 Vosk 模型（`zh`，vosk-model-small-cn-0.22，42MB）是否已下载，未下载则自动触发下载。① 用独立 `appScope.launch(Dispatchers.IO)`，与 `ensureRunRecovery`/`automationScheduler` 并行，不阻塞启动；② 先检查 `"zh" !in vosk.getDownloadedLanguages()`，已下载则跳过，避免重复下载；③ `try/catch(Throwable)` 包裹，任何失败都不影响 App 启动（appScope 的 SupervisorJob + CoroutineExceptionHandler 已兜底，显式 catch 更清晰）；④ `downloadModel("zh").collect {}` 收集 Flow 直到完成（downloadModel 内部 emit Complete/Error 后 close）；⑤ 用 `DebugLog.d/e` 记录日志（DebugLog 无 i 方法，用 d 替代）。修改 1 文件：`AppContainer.kt`（242→257 行，+15）。**约束遵守**：文件 257 行 ≤999 ✅，代码与注释均英文 ✅，未新增字符串资源 ✅（后台行为，日志用 DebugLog），未 bump 版本号 ✅，未新增依赖 ✅，未新增 import（用全限定名）✅。**未 git commit**（依任务要求）。
- **任务40 ASR 默认中文 + 麦克风单次录音时长上限**（2026-08-20，本次会话，coding-engineer team-mate）：① 将 `voice/voice_language` 默认值从 `en` 改为 `zh`（SettingsManager + SettingsRepository）；② 修复 `VoiceConversationController.transcribeWithVosk()` 语言不匹配 bug — 当 Vosk 已就绪但加载的语言与用户选择的不一致时（如用户选 `zh` 但 Vosk 仍持有 `en` 模型），旧代码跳过初始化直接转写导致乱码；新代码增加 `voskTranscriber.getCurrentLanguage() != langCode` 检查并移除硬编码 `"en"` 回退；③ 给 `SINGLE_ASR` 单次录音加 90 秒上限（`MAX_SINGLE_ASR_DURATION_MS = 90_000L`），超时自动调用 `stopCaptureAndTranscribe()` 转写已采集音频，在 `startSingleAsr`/`stopSingleAsr`/`stop`/`finishConversationTurn`/`handleTranscriptionResult` 各路径正确取消超时 Job。修改 3 文件：`SettingsManager.kt`（+1/-1）、`SettingsRepository.kt`（+1/-1）、`VoiceConversationController.kt`（955→982 行，+34/-7）。**约束遵守**：文件 ≤999 行 ✅，代码与注释均英文 ✅，未新增字符串资源 ✅，未 bump 版本号 ✅。commit `ceda24ab`。**未 push**（GitHub 网络不可达，按 R0.8 待后续 push 验证 CI）。
- **任务41 ASR/语音日志清理按钮**（2026-08-20，本次会话，coding-engineer team-mate）：在设置页 ASR 诊断区添加"清空 ASR 日志"按钮，点击调用 `AppLog.clear()` 清空内存日志并显示 Toast 提示。修改 3 文件：`SettingsAsrDiagnosticsSection.kt`（202→210 行，+8）— 在现有按钮 Row 内（copy log / save to downloads 之后）新增 `TextButton`，onClick 调用 `AppLog.clear()` + `Toast.makeText` 显示 `R.string.asr_log_cleared`；`values/strings.xml`（+2）— 新增 `asr_clear_log`="Clear ASR Log" + `asr_log_cleared`="ASR log cleared"；`values-zh/strings.xml`（+2）— 新增 `asr_clear_log`="清空 ASR 日志" + `asr_log_cleared`="ASR 日志已清空"。**约束遵守**：文件 ≤999 行 ✅，代码与注释均英文 ✅，用户可见文本 en/zh 双语 ✅，未 bump 版本号 ✅。commit `435e8e8d`。**未 push**（GitHub 网络不可达，按 R0.8 待后续 push 验证 CI）。
- **任务24 VoskTranscriber 流式会话自动初始化**（2026-08-19，本次会话，coding-engineer team-mate）：修复 `VoskTranscriber.startStreamingSession()` 在模型文件已下载但 `initialize()` 未调用时（如进程重启或调用方遗漏 init）直接返回 false 导致流式语音识别失败的问题。① `startStreamingSession()` 改为 `suspend fun`，在 `synchronized(streamingLock)` 块之前添加 auto-init 逻辑：当 `!isModelLoaded || model == null` 且 `am/final.mdl` 存在时自动调用 `initialize(languageCode)`；② `getLanguageByCode()` 添加 `Log.w` 警告日志，未知语言代码回退时可观测。修改 1 文件：`VoskTranscriber.kt`（+24/-4）。调用点 `VoiceConversationController.kt:436` 已在协程作用域内，无需修改。**约束遵守**：文件 806 行 ≤999 ✅，代码与注释均英文 ✅，未新增字符串资源 ✅，未 bump 版本号 ✅。commit `ec99db01`，**已 push**，CI #32252119499 全绿通过（conclusion=success）。
- **任务17 TTS barge-in + 强制 TTS 播放**（2026-08-19，本次会话，coding-engineer team-mate）：修复实时语音对话中 TTS 不播放的问题。① 新回复到达时停止当前 TTS 播放（barge-in），切换到最新消息；② 实时语音对话模式下强制开启 TTS 自动播放（隐藏的、强制的，不依赖用户设置）。修改 2 文件：`VoiceConversationController.kt`（949→955 行，+10/-2）— 新增 `isConversationStreaming()` 暴露流式会话状态 + `handleTranscriptionResult` CONVERSATION 分支添加 `TtsManager.stop()` barge-in + `observeLlmAndTts` 条件改为 `isStreamingConversation || ttsAutoPlayOn()`；`ChatViewModel.kt`（998→999 行，+3/-1）— `onStreamCommit` 回调添加 `voiceStreaming` 变量并修改 TTS 播放条件。**约束遵守**：文件 ≤999 行 ✅，未新增字符串资源 ✅，未 bump 版本号 ✅，代码与注释均用英文 ✅。commit `ee98a23a`，CI 全绿验证通过（conclusion=success）。
### 🟡 已知问题
- **PRoot 二进制需 CI 构建**：`build-proot.sh` 产物（`libproot_*.so`, `libtalloc.so`）被 `.gitignore` 忽略，CI 中由 `./build-proot.sh force` 现场构建。
- **签名密钥**：Release 签名需在GitHub Secrets 配置 `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`；未配置时回退 debug 签名。

### ❌未完成
1. 滑动连续多选：未实现（已支持长按进入多选）。

## 5. 关键接口契约（不要破坏既有签名）

### 应用入口（已固化，
- `LxChatApplication`：持有`AppContainer`（手加DI 容器，进程级单例）。
- `MainActivity.attachBaseContext(newBase: Context)`：根换`SettingsManager.appLanguage` 设置 Locale（当前仅 `en`/`zh`/`system`）。
- `MainActivity.onCreate()`：安装Splash →初始匀DebugLog →创建通知渠道 →请求通知权限 →Compose `setContent { LxChatTheme { ... } }`。

### LLM Provider 契约（已固化）
- `LlmProvider` 接口（`api/LlmProvider.kt`）：定义 `StreamEvent` 密封类（TextChunk / thoughtChunk / ToolCallUpdate / ToolCallRequest / UsageUpdate / Retrying / Error）。
- `HttpClient`（`api/HttpClient.kt`）：OkHttp 单例，SSE 流式解析（`BufferedSource` 逐行译`data:`）。
- Provider 实现：OpenAI / Anthropic / Gemini / DeepSeek / Qwen / OpenRouter / Groq / Ollama / Custom / Local（llama.cpp JNI）。

### 主题与字体契约（已固化，搂R0.7，
- `OutfitFamily = FontFamily.Default`（UI 文本）—`ui/theme/Type.kt`。
- `MonoFamily = FontFamily.Monospace`（代码终端/崩溃日志）—`ui/theme/Type.kt`。
- `LxChatTheme(themeMode, colorSchemePreset, schemeStyle, dynamicColor, fontPreference, customFontPath, content)` —`ui/theme/Theme.kt`。
- `ChatType` 对象：聊天界面的排版 scale（title/input/body/sub/meta/code 六层），`chatFontFamily` 可变（由 Theme.kt 根据 fontPreference 设置）。

### 数据层契约（已固化）
- Room Database v22（`data/local/ChatDatabase`）：树形消息结构，schema 快照在`app/schemas/`。
- `SettingsManager`（`data/SettingsManager`）：DataStore Preferences，管理所有用户设置（appLanguage / themeMode / colorScheme / fontPreference 等）。
- `AppContainer`（`di/AppContainer.kt`）：手动 DI，提供`chatViewModelFactory()` / `conversationRepository` 等进程级单例。

### i18n 契约（已固化，搂R0.6）
- 语言选项：`SettingsLanguagePage.kt` 为`LanguageOption("system"|"en"|"zh")`。
- Locale 映射：`MainActivity.attachBaseContext()` 为`when (langCode) { "zh" -> Locale("zh","CN"); "en" -> Locale("en"); else -> null }`。
- 文档语言映射：`DocumentationFab.kt` 为`langTag.startsWith("zh") -> "zh/"`，其他→英文根。
- 系统提示标题：`DefaultSystemPrompt.titleForLocale()` 为`"zh" -> 简体中文标题`，其他→"Default"。

### Product Flavors（已固化）
- `play`：Google Play 版，`PlaySandboxManager`（无 PRoot）。
- `fdroid`：F-Droid 版，`ProotSandboxManager`（PRoot + Alpine Linux）。
- CI 构建 fdroid flavor：`./gradlew assembleFdroidRelease`。

### 原生构建（已固化）
- CMake（`app/src/main/cpp/CMakeLists.txt`）：构建 `lxchat_llama`（llama.cpp JNI， `lxchat_proot`（PRoot JNI stub）。
- PRoot 二进制（`build-proot.sh`）：构建 `libproot_exec.so` / `libproot_loader.so` / `libtalloc.so` →`app/src/{main,fdroid}/jniLibs/arm64-v8a/`。
- 子模块：`thirdparty/llama.cpp` + `thirdparty/proot`（checkout 需 `--recurse-submodules`）。

### Agent 能力深化 P2 契约，026-08-17 落地，已固化，
- **`ToolTierPolicy`**（`tool/ToolTierPolicy.kt`）：工具分档下发策略。
  - `ToolTier` 枚举：`Core` / `Extended` / `Dangerous`（可见性递减，Core 始终下发，Dangerous 需显式授权）。
  - `tierOf(name: String): ToolTier`：工具名 →档位映射（file_write/file_edit 属Extended 档，安全性由 RiskLevel + 确认门控保障，tier 仅控制可见性）。
  - `allowedTiers(ctx: GenerationContext): Set<ToolTier>`：根换`ctx.toolTier`，core"/"extended"/"all"）或 `agentMode` 回退决定允许档位集合。
  - `filterByTier(definitions: List<ToolDefinition>, ctx: GenerationContext): List<ToolDefinition>`：链式过滤，被`GenerationToolExecutor.definitions` 调用。
  - `GenerationContext.toolTier: String = "all"`（`viewmodel/GenerationContracts.kt` 新增字段）。
- **`ActionTraceBus`**（`tool/ActionTraceBus.kt`）：行动轨迹总线，进程级 object 单例。
  - 256 束`ArrayDeque` 环形缓冲匀+ `Mutex` 保护并发。
  - `record(entry: ActionTraceEntry)`：记录一次工具执行（`GenerationToolExecutor.execute` 调用，execute 开始记录startMs + 从工具参数JSON 提取 server 字段）。
  - `snapshot(limit: Int = 50): List<ActionTraceEntry>`：取最过limit 条（旧→新）。
  - `clear()`：清空缓冲区。
  - `toJson(limit: Int = 50): String`：序列化为JSON（供 `get_action_trace` 工具返回）。
- **`ActionTraceEntry`**（data class）：`toolName` / `argumentsSummary` / `resultSummary` / `isError` / `server` / `conversationId` / `runId` / `timestampMs` / `durationMs`。
- **`ActionTraceToolProvider`**（`tool/ActionTraceToolProvider.kt`）：实现 `ToolProvider`，暴露`get_action_trace` ReadOnly 工具（无副作用，返回 `ActionTraceBus.toJson()`）。
- **`execute_shell_batch`**（`tool/ShellToolDefinitions.kt` + `tool/ShellToolProvider.kt`）：批量多服务器并行执行工具。
  - 参数：`command: String` / `servers: Array<String>` / `timeout_ms: Int` / `workdir: String`。
  - `servers` 空数组时 fallback 到`ctx.shellDevices` 所有已配置服务器（排除 Local Sandbox），`items` schema 已补（修多zen provider 校验）。
  - 执行：`coroutineScope { servers.map { async { ... } }.awaitAll() }` 并行 + 一次性confirm 门控 + `parseBackendResult` 聚合 JSON。
  - `riskLevel = RiskLevel.Moderate`。

## 6. 下一步任务（按优先级，逐项勾选）

> 每项都是可独立交付的最小单元。完成即打勾并移到「已完成」区。

- [ ] 功能开发/ bug 修复 / 性能优化等用户指派任务。

## 7. 编码约定（强制）

- **语言**：代码与注释一律英文（标识符、doc comment、日志消息）；本文件和面向用户的文档用简体中文。
- **不写注释**除非用户要求；让类型与函数名自解释。KDoc（`/** */`）允许且鼓励用于 public API。
- **UI**，00% Jetpack Compose + Material 3，无 XML 布局（`themes.xml` 仅用于启动屏）。单 Activity 架构。
- **架构**：MVVM + Coroutines & Flow。ViewModel 持有 `StateFlow`，UI 通过 `collectAsState()` 订阅。
- **DI**：手加DI via `AppContainer`，不用Hilt/Dagger。
- **网络**：OkHttp + SSE，不用Retrofit/Ktor。流式响应逐行解析 `data:` 行。
- **序列匀*：`kotlinx.serialization`（JSON）。
- **存储**：Room（树形消息）+ DataStore Preferences。数据库迁移需新增 schema 快照到`app/schemas/`。
- **i18n**（搂R0.6）：件`values/`（en， `values-zh/`（zh）。新增字符串需同时在两处添加。`SettingsLanguagePage.kt` 为`MainActivity.attachBaseContext()` 必须同步。
- **字体**（搂R0.7）：`OutfitFamily` = `FontFamily.Default`，`MonoFamily` = `FontFamily.Monospace`。禁此`R.font.*` 引用。
- **命名**：Composable 函数 PascalCase（如 `ChatApp`），ViewModel/Repository/Manager 后缀明鐟，包名单数。
- **源码大小**：每 Kotlin 文件 ≤999 行（`./gradlew verifyKotlinFileSize` 强制）。
- **测试**：单元测试放 `app/src/test/`，F-Droid 专属测试支`app/src/testFdroid/`。
- **产物**：CI 产出 `LxChat-v{VERSION}-android-arm64-v8a.apk`，仅 `arm64-v8a` ABI。

## 8. 常用命令

```bash
# 构建 F-Droid Release APK（CI 主目标）
./gradlew assembleFdroidRelease

# 构建 Google Play Release APK
./gradlew assemblePlayRelease

# 构建 Play AAB bundle
./gradlew bundlePlayRelease

# 单元测试
./gradlew test

# 构建插件测试（字节码修复 + 源码大小策略）
./gradlew -p build-logic test

# 源码大小策略验证（每文件 ≤999 行）
./gradlew verifyKotlinFileSize

# 构建 PRoot 原生二进制（需 NDK 28.2.13676358）
./build-proot.sh

# Lint
./gradlew lint

# 发版（触发CI 流水线）
git tag v1.0.0
git push origin v1.0.0
# →CI 自动构建 LxChat-v1.0.0-android-arm64-v8a.apk 并发布到 GitHub Release

# 查看 CI 运行状性
gh run watch
gh run view --log-failed    # 失败时查看报错日志
```

环境：本地离线，缺Android SDK/NDK/CMake，**无法**本地 `./gradlew assembleFdroidRelease`。编译验证走 GitHub CI（搂R2）。子模块 checkout 需 `--recurse-submodules`。

## 9. 变更日志（追加新行，最新在上）

- 2026-08-20 task id=2 修复发送按钮显示逻辑（本次会话，coding-engineer team-mate）：文本框有输入时即使未选模型/切换中也显示发送箭头，点击无效模型时 Toast 提示先选模型。
  - **未 commit**（依任务要求"不要 git commit"）**fix(chat)**: show send arrow on input even without valid model。
    - `ComposerSendButton.kt`（247→267 行）— 新增 `hasInput` 变量（text/attachments 非空）；`fabIcon` 在 `singleAsrRecording` 之后、`canSend` 之前新增 `hasInput -> ComposerActionIcon.SEND` 分支，使有输入时优先显示发送箭头而非 IDLE 波形；SEND 点击分支在 `singleAsrRecording`/`pendingSend` 检查后新增 `!isModelValid` 早返回 + Toast 提示 `toast_select_model_first`；`canSend` 定义用 `hasInput` 替代内联表达式（语义等价），颜色保持 `if (canSend) primary else surfaceVariant`。
    - `values/strings.xml`（+1）— 新增 `toast_select_model_first`="Please select a model first"。
    - `values-zh/strings.xml`（+1）— 新增 `toast_select_model_first`="请先选择模型"。
  - **约束遵守**：`ComposerSendButton.kt` 267 行 ≤999 ✅；代码注释均英文 ✅；en/zh 双语 ✅；未 bump 版本号 ✅；Composable 签名不变 ✅。
  - **验证**：本地静态检查通过，**未 push**（依任务要求"不要 git commit"，等 team leader 统一验证 CI）。

- 2026-08-20 task id=1 统一 TTS/ASR 日志到「语音日志」区（本次会话，coding-engineer team-mate）：在生成设置页内新建统一「语音日志」Section，合并 TTS/ASR 日志查看/复制/保存/清空。
  - **未 commit**（依任务要求"不要 git commit"）**refactor(settings)**: unify TTS/ASR logs into Voice Logs section。
    - 新建 `SettingsVoiceLogSection.kt`（195 行）— `@Composable fun SettingsVoiceLogSection(context, ttsDiagnostic, ttsInitStatus, ttsSpeakResult, ttsLangResult, voskTranscriber)`，用 `SettingsGroup(voice_log_section_title)` 包裹两个子区块：TTS 子区块（标题 + 引擎信息 + 日志文本 TtsManager.getLogText() 每 3 秒刷新 + 4 按钮 导出/复制/保存/清空）+ ASR 子区块（标题 + 日志文本 AppLog.getFilteredText(ASR_LOG_TAGS,30) 每 3 秒刷新 + 3 按钮 复制/保存/清空）。ASR_LOG_TAGS 从 SettingsAsrDiagnosticsSection 迁移至此。
    - `SettingsGenerationPage.kt`（969→912 行）— 移除 TTS Section 内日志块（原 490-554 行：引擎信息 + 4 按钮），移除未使用 import `CrashReporter`；在 ASR 诊断区调用之后（第 706 行）新增 `SettingsVoiceLogSection(...)` 调用，传入 ttsContext/ttsDiagnostic/ttsInitStatus/ttsSpeakResult/ttsLangResult/voskTranscriber。
    - `SettingsAsrDiagnosticsSection.kt`（210→139 行）— 移除日志文本显示 + 3 按钮 Row + ASR_LOG_TAGS 定义；清理未使用 import（Arrangement/Row/TextButton/LaunchedEffect/mutableStateOf/remember/setValue/Color/AppLog/CrashReporter/delay）；硬编码红色 `Color(0xFFE53935)` 改为 `MaterialTheme.colorScheme.error`。保留引擎就绪检查、语言警告、Vosk 诊断文本。Composable 签名不变。
    - `values/strings.xml`（+3）— 新增 `voice_log_section_title`="Voice Logs" + `voice_log_tts_subtitle`="TTS Log" + `voice_log_asr_subtitle`="ASR Log"。
    - `values-zh/strings.xml`（+3）— 新增 `voice_log_section_title`="语音日志" + `voice_log_tts_subtitle`="TTS 日志" + `voice_log_asr_subtitle`="ASR 日志"。
  - **约束遵守**：每文件 ≤999 行 ✅（195/912/139）；代码与注释均英文 ✅；颜色用 MaterialTheme.colorScheme.* 语义化 ✅（移除硬编码 0xFFE53935）；i18n 仅 en/zh ✅；未 bump 版本号 ✅；未新增字体/依赖 ✅；Composable 签名不变 ✅；复用既有 CrashReporter/AppLog/TtsManager API ✅。
  - **验证**：本地静态检查通过，**未 push**（依任务要求"不要 git commit"，等 team leader 统一验证 CI）。

- 2026-08-20 task id=3 启动时自动下载中文 ASR 模型（本次会话，coding-engineer team-mate）：在 `AppContainer.startProcessServices()` 新增独立后台协程自动下载中文 Vosk 模型。
  - **未 commit**（依任务要求"不要 git commit"）**feat(asr)**: auto-download zh Vosk model on startup。
    - `AppContainer.kt:80-94` — `startProcessServices()` 新增第二个 `appScope.launch(Dispatchers.IO)` 协程：① 构造 `VoskTranscriber(appContext)`；② 检查 `"zh" !in vosk.getDownloadedLanguages()`，未下载则 `vosk.downloadModel("zh").collect {}` 收集 Flow 直到完成；③ `try/catch(Throwable)` 包裹，失败用 `DebugLog.e` 记录，不影响 App 启动；④ 用 `DebugLog.d` 记录下载开始/完成/跳过日志（DebugLog 无 i 方法，用 d 替代）。与原有 `ensureRunRecovery`/`automationScheduler` 协程并行，不阻塞启动。
  - **约束遵守**：`AppContainer.kt` 257 行 ≤999 ✅；代码与注释均英文 ✅；无新增字符串资源 ✅（后台行为，日志用 DebugLog）；无 bump 版本号 ✅；无新增依赖 ✅；无新增 import（用全限定名 `com.lxseek.chat.speech.VoskTranscriber`/`com.lxseek.chat.util.DebugLog`）✅。
  - **DownloadState 行为确认**：`VoskTranscriber.downloadModel()` (L328) 用 `callbackFlow`，成功路径 emit `Downloading`→`Extracting`→`Complete(modelDir)` 后 `close()`，失败路径 emit `Error(message)` 后 `close()`，`collect {}` 会正常结束不挂起。`getDownloadedLanguages()` (L147) 通过检查 `am/final.mdl` 存在性判断已下载，避免重复下载。
  - **验证**：本地静态检查通过，**未 git commit**（依任务要求），**未 push**（按 R0.8 待后续 push 验证 CI）。

- 2026-08-20 task id=40 ASR 默认中文 + 麦克风单次录音时长上限（本次会话，coding-engineer team-mate）：将 ASR 默认语言改为中文、修复 Vosk 语言不匹配 bug、给单次录音加 90 秒超时。
  - `ceda24ab` **feat(asr)**: default to zh + fix language mismatch + single-asr recording timeout。
    - `SettingsManager.kt:404` — `voiceLanguage` 默认值 `"en"` → `"zh"`。
    - `SettingsRepository.kt:110` — `voiceLanguage` eager 初始默认值 `"en"` → `"zh"`（`hot()` 第二参数，DataStore 加载前使用）。
    - `VoiceConversationController.kt:90` — 新增 `private var singleAsrTimeoutJob: Job? = null`。
    - `VoiceConversationController.kt:94-97` — 新增 `companion object` 含 `MAX_SINGLE_ASR_DURATION_MS = 90_000L`。
    - `VoiceConversationController.kt:153-163` — `startSingleAsr()` 在 `beginListening()` 后启动超时 Job，`delay(90s)` 后检查 `active && SINGLE_ASR && LISTENING` 则调用 `stopCaptureAndTranscribe()` 自动转写。
    - `VoiceConversationController.kt:188-189` — `finishConversationTurn()` 取消超时 Job。
    - `VoiceConversationController.kt:210-211` — `stopSingleAsr()` 取消超时 Job。
    - `VoiceConversationController.kt:230-231` — `stop()` 取消超时 Job。
    - `VoiceConversationController.kt:722-727` — `transcribeWithVosk()` 修复语言不匹配：条件从 `!isReady()` 改为 `!isReady() || getCurrentLanguage() != langCode`，移除硬编码 `"en"` 回退（初始化失败时走下方 whisper fallback / error 逻辑）。
    - `VoiceConversationController.kt:888-889` — `handleTranscriptionResult()` 错误分支 SINGLE_ASR 取消超时 Job。
    - `VoiceConversationController.kt:908-909` — `handleTranscriptionResult()` 成功分支 SINGLE_ASR 取消超时 Job。
  - **约束遵守**：`VoiceConversationController.kt` 982 行 ≤999 ✅；`SettingsManager.kt` 998 行 ≤999 ✅；`SettingsRepository.kt` 580 行 ≤999 ✅；代码与注释均英文 ✅；无新增字符串资源 ✅；无 bump 版本号 ✅。
  - **验证**：本地 git commit 成功（`ceda24ab`，3 files changed, +34/-7），**未 push**（GitHub 网络不可达，按 R0.8 待后续 push 验证 CI）。

- 2026-08-20 task id=41 ASR/语音日志清理按钮（本次会话，coding-engineer team-mate）：在设置页 ASR 诊断区添加清空日志按钮。
  - `435e8e8d` **feat(settings)**: add ASR/voice log cleanup entry。
    - `SettingsAsrDiagnosticsSection.kt:200-207` — 在现有按钮 Row 内（copy log / save to downloads 之后）新增 `TextButton`，onClick 调用 `AppLog.clear()` 清空内存日志 + `Toast.makeText(context, context.getString(R.string.asr_log_cleared), LENGTH_SHORT).show()` 显示提示。`AppLog` 已 import（L27），`context` 在 Composable 作用域可用。
    - `values/strings.xml:170-171` — 新增 `asr_clear_log`="Clear ASR Log" + `asr_log_cleared`="ASR log cleared"。
    - `values-zh/strings.xml:846-847` — 新增 `asr_clear_log`="清空 ASR 日志" + `asr_log_cleared`="ASR 日志已清空"。
  - **约束遵守**：`SettingsAsrDiagnosticsSection.kt` 210 行 ≤999 ✅；代码与注释均英文 ✅；用户可见文本 en/zh 双语 ✅；未 bump 版本号 ✅；未新增字体 ✅；i18n 仅 en/zh ✅。
  - **验证**：本地 git commit 成功（`435e8e8d`，3 files changed, +12），**未 push**（GitHub 网络不可达，按 R0.8 待后续 push 验证 CI）。

- 2026-08-19 task id=24 VoskTranscriber 流式会话自动初始化 + 未知语言代码警告（本次会话，coding-engineer team-mate）：修复 `startStreamingSession()` 在模型文件已下载但未初始化时返回 false 的 Bug #1，以及 `getLanguageByCode()` 静默回退的 Bug #2。
  - `ec99db01` **fix(vosk)**: auto-init model in startStreamingSession + warn on unknown language code。
    - `VoskTranscriber.kt:631-647` — `startStreamingSession()` 改为 `suspend fun`；在 `synchronized(streamingLock)` 块之前添加 auto-init 逻辑：当 `!isModelLoaded || model == null` 时检查 `File(getModelDirectory(languageCode), "am/final.mdl").exists()`，若存在则调用 `initialize(languageCode)`。auto-init 必须在 synchronized 块之外，因为 `initialize()` 使用 `withContext(Dispatchers.IO)` 会切换线程。KDoc 注释更新说明 auto-init 行为。
    - `VoskTranscriber.kt:101-108` — `getLanguageByCode()` 添加 `android.util.Log.w` 警告日志，当语言代码未在 `AVAILABLE_LANGUAGES` 中找到时记录回退，便于 logcat 调试。
  - **调用点分析**：`VoiceConversationController.kt:436` 的 `startStreamingSession()` 调用在 `scope.launch {}` 协程作用域内，改为 `suspend fun` 无需修改调用点。调用方已有 auto-init 逻辑（L400-410），本次修复为 defense-in-depth。
  - **约束遵守**：`VoskTranscriber.kt` 806 行 ≤999 ✅；代码与注释均英文 ✅；无新增字符串资源 ✅；无 bump 版本号 ✅。
  - **验证**：本地 git commit 成功（`ec99db01`，1 file changed, +24/-4），**已 push**，CI #32252119499 全绿验证通过（conclusion=success）。

- 2026-08-19 task id=23 ASR 设置严格引擎就绪检查 + 显著语言选择器（本次会话，coding-engineer team-mate）：修复 ASR 设置页面缺乏引擎就绪诊断、语言选择器不显著导致用户无法定位 Vosk ASR 静默失败根因的问题。
  - `d409bbd5` **fix(asr-settings)**: strict engine readiness checks + prominent language selector。
    - `SettingsAsrDiagnosticsSection.kt` — 新增 5 个参数（`asrUseRemote`/`asrRemoteBaseUrl`/`asrRemoteApiKey`/`asrRemoteModel`/`voiceLanguage`，均带默认值）；用 `buildList` 构建多行引擎就绪状态文本，按 `auto`/`vosk`/`whisper`/`system` 分支显示严格检查（auto 检查 Vosk `isReady()` + Whisper configured + System available，若都没有显示 "⚠ NO engine available!"；vosk 显示 loadedLang/downloadedLangs/modelForVoiceLang；whisper 显示 baseUrl/apiKey/model 是否 set；system 显示 `SpeechRecognizer.isRecognitionAvailable`）；新增红色（`Color(0xFFE53935)`）语言不匹配警告（所选 voice language 无已下载 Vosk 模型时）；保留原有 Vosk diagnostic text、log text、copy/save buttons 功能。硬编码英文诊断文本，无新增字符串资源。
    - `SettingsGenerationPage.kt:757-761` — `SettingsAsrDiagnosticsSection(` 调用点添加 5 个新参数（`asrUseRemote`/`asrRemoteBaseUrl`/`asrRemoteApiKey`/`asrRemoteModel`/`voiceLanguage`），所需变量在作用域中已存在。
    - `SettingsVoskModelsSection.kt` — Change B（前一个 subagent 完成）：新增 `BASE_LANGUAGE_DISPLAY_NAMES` 友好显示名映射（23 种基础语言）；将语言选择器重构为显著 `Surface` 卡片，显示当前语言友好名 + 下载状态指示器（✓/⚠）+ 下拉菜单（每项标注 ✓ 已下载）+ 不匹配警告（当前语言无已下载模型时红色提示）+ Ready/Lang 状态行；保留模型列表（下载/删除）功能。
  - **约束遵守**：每文件 ≤999 行；代码和注释均英文；无新增字符串资源；无 bump 版本号；未修改 `VoiceConversationController.kt`/`ChatViewModel.kt`；`VoskTranscriber.kt` 工作目录修改未纳入本次 commit（不属于本任务范围）。
  - **验证**：本地 git commit 成功（`d409bbd5`，3 files changed, +205/-39），**已 push**，CI #32250168436 全绿验证通过（conclusion=success）。

- 2026-08-19 task id=17 TTS barge-in + 强制 TTS 播放（本次会话，coding-engineer team-mate）：修复实时语音对话中 TTS 不播放的问题。
  - `ee98a23a` **fix(voice)**: force TTS in streaming conversation + barge-in on new reply。
    - `VoiceConversationController.kt:81-82` — 新增 `isConversationStreaming()` 方法，暴露 `isStreamingConversation` 状态。
    - `VoiceConversationController.kt:894-895` — `handleTranscriptionResult` CONVERSATION 分支添加 `TtsManager.stop()` barge-in，新回复到达时停止当前 TTS 播放。
    - `VoiceConversationController.kt:923` — `observeLlmAndTts` 条件从 `if (ttsAutoPlayOn())` 改为 `if (isStreamingConversation || ttsAutoPlayOn())`，流式会话模式强制 TTS。
    - `ChatViewModel.kt:555-556` — `onStreamCommit` 回调添加 `voiceStreaming` 变量，TTS 播放条件改为 `voiceStreaming || (settings.ttsEnabled.value && settings.ttsAutoPlay.value)`。
  - **验证**：GitHub CI 全绿（conclusion=success），编译验证通过。文件行数 VoiceConversationController.kt=955 ≤999，ChatViewModel.kt=999 ≤999。

- 2026-08-19 全量代码审查与修复（本次会话，文档维护代理）：3 个 commit 修复安全/崩溃/UI/CI 问题，CI 全绿验证通过。
  - `6723e6e7` **fix(security,crash)**: Zip Slip 路径遍历防护 + NPE 守卫 + Room DB 泄漏修复。
    - `VoskTranscriber.kt:541` — 添加 Zip Slip canonical path 验证，防止解压路径遍历攻击。
    - `SettingsProviderDetailPage.kt:507` — 添加 `copiedFilePath` null 检查，防止 NPE 崩溃。
    - `AutoBackupWorker.kt:28` — `finally` 块添加 `db.close()`，修复 Room 数据库资源泄漏。
  - `fbf6231d` **fix(ui)**: Compose recompose race — 替换 `!!` 为安全空处理。
    - `SettingsDataControlPage.kt:333,470` — `remember` 添加 key，防止条件块内状态残留（H3）。
    - `SettingsSearchPage.kt:666` — 提取 `showRenameDialog` 到 local val，防止 `remember` 失效（H4）。
    - 9 个文件 14 处 — 替换 `if(null)` 块内的 `!!` 为安全空处理（H5）。
  - `354e566e` **fix(ci)**: smart cast delegated property in `WelcomeScreen` when expression。
    - `WelcomeScreen.kt:473-481` — `selectedProvider` 是委托属性无法 smart cast，捕获到 local val `providerForDesc`。
  - **验证**：GitHub CI 全绿，编译验证通过。本次为全量代码审查后的批量修复，无新增功能，无接口契约变更。

## 10. 参考索引

- 架构文档：`ARCHITECTURE.md`，90 行，详细架构说明）。
- 版本目录：`gradle/libs.versions.toml`（AGP/Kotlin/Compose/Room 等版本统一管理）。
- 上游借鉴：`/opt/github/RustSync`（编译流水线参照：tag 触发 →产物命名 →GitHub Release 模式）。
- 关键文件速查（行数为 PowerShell 实测值，2026-08-18 同步）：
  - 应用入口：`app/src/main/java/com/lxseek/chat/MainActivity.kt`
  - Application：`app/src/main/java/com/lxseek/chat/LxChatApplication.kt`
  - DI 容器：`app/src/main/java/com/lxseek/chat/di/AppContainer.kt`
  - Provider 接口：`app/src/main/java/com/lxseek/chat/api/LlmProvider.kt`
  - HTTP 客户端：`app/src/main/java/com/lxseek/chat/api/HttpClient.kt`
  - 主题：`app/src/main/java/com/lxseek/chat/ui/theme/{Type,Theme,Color}.kt`
  - 语言选项：`app/src/main/java/com/lxseek/chat/ui/settings/SettingsLanguagePage.kt`
  - 系统提示：`app/src/main/java/com/lxseek/chat/data/DefaultSystemPrompt.kt`
  - 聊天为Composable：`app/src/main/java/com/lxseek/chat/ui/chat/ChatApp.kt`，91 行）
  - 聊天拆分文件：`ChatAppBottomBarSection.kt`，57， `ChatAppOverlays.kt`，96， `ChatAppInteractionEffects.kt`，57， `ChatAppDialogHost.kt`，46，
  - 发送区：`ui/chat/bottombar/ChatBottomBar.kt`，95， `ComposerSendButton.kt`，32，
  - 语音对话控制器：`viewmodel/VoiceConversationController.kt`，49，
  - 语音覆盖层：`ui/chat/VoiceConversationOverlay.kt`，67， `SingleAsrOverlay.kt`，36，
  - 音频采集：`speech/AudioCaptureManager.kt`，34，
  - ChatViewModel：`viewmodel/ChatViewModel.kt`，98，
  - SettingsManager：`data/SettingsManager.kt`，98，
  - UI 重设计规格：`UI_REDESIGN_SPEC.md`，01 行）
  - 架构文档：`ARCHITECTURE.md`，90 行）
  - 构建配置：`app/build.gradle.kts`
  - CI 流水线：`.github/workflows/build.yml`
  - PRoot 构建：`build-proot.sh`
  - 原生构建：`app/src/main/cpp/CMakeLists.txt`
