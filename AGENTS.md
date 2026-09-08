# AGENTS.md — LxChat

> 给 AI 编码代理的仓库指南。规则分三级:**Never(禁止)/ Ask first(先问)/ 默认自主**。每条硬规则都对应一个真实踩过的坑,不要试图绕过。

## 0. 项目速览(新会话必读)

**LxChat** 是 Android 端 BYOK LLM 客户端(Kotlin + Compose,单 arm64-v8a APK),派生自 Agora:
多 Provider 接入(9+ 内置 + 自定义端点)、树状分支对话、llama.cpp 本地推理、Conch 加密远程
Shell、定时自动化、婴儿哭声监护(YAMNet)、IM 网关(iLink 微信自动回复)。

- **仓库**:https://github.com/ojbkxc/lxchat
- **包名**:`com.lxseek.chat`;模块:`:core:util`(纯常量)← `:core:model`(领域模型)← `:app`(宿主)
- **app 内主要包**:`agent` `api` `im` `localmodels` `mcp` `baby` `automation` `speech` `ui` `viewmodel` `data` `runtime` `sandbox` `membership` 等,完整清单见 `app/src/main/java/com/lxseek/chat/`
- **设计文档**:`docs/development/`(agentic loop、context compact、行数策略等,动手前先查)

## 1. Never(硬性禁止,违反即返工)

1. **禁止本地编译**。不运行 `gradlew build` / `assembleDebug` / `assembleRelease` 等任何本地
   Gradle 构建命令。本机编译结果不可信,唯一编译验证是 GitHub CI。
2. **只能改 `main` 分支**,提交直接推 `main`。不开 feature 分支,不走 PR。
3. **禁止用 PowerShell 原生命令**(`Set-Content`/`Out-File`/重定向)写含非 ASCII 的文件——
   编码页不匹配会导致中文乱码。写文件用 Python(显式 `encoding='utf-8'`)或编辑工具。
4. **所有源码保持 UTF-8(无 BOM)**;读含中文的文件必须显式指定编码
   (PowerShell 加 `-Encoding UTF8`,Python 加 `encoding='utf-8'`)。
5. **禁止重新引入 "agora" 标识**。内部标识已全量改名 `lxchat*`(数据库 `lxchat_db`、
   so 库 `liblxchat_llama.so` 等),新代码/注释/资源里不得再出现 agora 字样。
6. **禁止手动改写行数 baseline**:`config/kotlin-source-size-baseline.txt` 由人为决策维护,
   代理不得为让 CI 通过而往里加条目或调高上限。
7. **禁止 `git add -A` / `git add .`**:只 add 自己本次改动的文件(可能存在并行会话的未提交改动)。
8. **禁止提交 secrets**:签名 keystore、支付密钥走 CI secrets(见 build.yml 的
   `KEYSTORE_BASE64`/`LXCHAT_HMAC_SECRET` 注入),不入库。

## 2. 代码规范

- **单文件 ≤ 1499 物理行**(硬门禁 `verifyKotlinFileSize`,CI 与 Android `preBuild` 都会跑);
  内部分解目标 700-800 行。超限 CI 直接红,不允许 baseline 例外。
- **每个手写 Kotlin 文件保持在 cap 内增长**;要加功能到已超 700 行的文件时,优先新建
  模块/文件而不是继续膨胀。
- match 尽量穷尽,避免 wildcard 分支;不留一次性引用的私有 helper。
- 注释只写"为什么",不叙述"做什么";中文注释与代码混排时保持 UTF-8。
- 提交信息:conventional 前缀 + 中文祈使句,与现有历史一致
  (`fix(chat): 切换模型后发送仍命中内置默认模型`、`perf(chat): ...`、`ci: ...`)。

## 3. 构建变体与 CI

### Flavor 矩阵(dist × store,变体名如 `fdroidOnline`)

| | online(运行时下载 YAMNet 模型) | full(内置 YAMNet,APK +16MB) |
|---|---|---|
| **fdroid** | fdroidOnline(CI 日常验证用) | fdroidFull |
| **play** | playOnline | playFull(发布打包 online+full 双 APK) |

- PRoot 沙箱二进制仅 fdroid flavor 携带(`app/src/fdroid/jniLibs/`)。
- `full` 变体的 YAMNet 模型从 `src/full/assets` 拉取(task `downloadBundledYamnet`)。
- 本地 llama 推理引擎 `lxchat_llama-arm64-v8a.so` 托管在独立仓库
  **ojbkxc/lxchat-runtime**,App 按需下载;不随 APK 分发。

### CI 行为(push 到 main 自动触发 `ci.yml`)

1. `build-logic test` + `verifyKotlinFileSize`(行数门禁)。
2. `assembleFdroidOnlineDebug` —— **失败时 CI 会把聚合好的错误报告提交到 `ci-logs` 分支**:
   - 状态:`git fetch origin ci-logs && git show origin/ci-logs:ci-logs/build-status.txt`
   - 详情:`git show origin/ci-logs:ci-logs/build-failure.txt`(已提取 `What went wrong`/
     `Unresolved reference` 等错误标记,先读这个再翻原始日志)。
3. `assembleFdroidOnlineRelease`(验证 ArtProfile/BaselineProfile 任务在 AGP 变更后仍可用)。
4. **完成态定义:CI 绿才算完。** push 后盯 CI,红了修根因再推,不许留红 CI 结束回合。
   CI 查询用 GitHub API(`curl -s https://api.github.com/repos/ojbkxc/lxchat/actions/runs?head_sha=<SHA>`,
   本机无 `gh` CLI)。

### 发布(唯一入口 = tag)

```
git tag vX.Y.Z && git push origin vX.Y.Z   # 触发 build.yml
```

- 版本号规则:**下一个正式版本 = 上一个正式版本最后一位 +1**(v1.0.5 → v1.0.6,
  不跳号、不动中间位)。版本由 tag 注入(`appVersionName`),本地 fallback 是 1.0.0。
- build.yml 产出:fdroid/play × online/full 四个 release APK + GitHub Release +
  `lxchat_llama-arm64-v8a.so` 上传到 lxchat-runtime 同 tag Release。

## 4. 自动迭代闭环

无人值守迭代时严格走以下循环,禁止跳步:

```
理解任务 → 就绪判定 → 手术式实现 → push main → 盯 CI(绿=完成)
                ↑                                    │
                └────── 修根因,不带病迭代 ←── CI 红 ──┘
```

1. **就绪判定**:需求含糊时先产出方案说明并询问,不猜。多方案存在时列 tradeoff,不沉默选择。
2. **手术式修改**:只改必须改的;不"顺手"重构、不改无关格式、不清理预先存在的死代码。
3. **push 前自检**:`git status` 确认只包含自己的改动 → diff 逐条对照第 1/2 节规则 → 提交
   信息符合第 2 节格式。行数超限的文件在 push 前必须分解,不要指望 CI 报错后再拆。
4. **push 方式**:用用户提供的 PAT 拼 URL 一次性推送(凭据见用户私有记录,不入库);
   禁止交互式凭据管理器(无人值守会永久挂起)。
5. **CI 反馈处理**:失败先读 `ci-logs` 分支的 `build-failure.txt`(见第 3 节),修根因;
   同一修复不许盲试超过 2 次,仍红则停下报告。不跑 `--rerun-failed` 式的重复 push。
6. **不许宣称被阻塞**:某操作不可用必须真实尝试并引用实际报错;没试过就说 "not attempted",
   不许说 "we can't"。
7. **结束卫生**:回合结束时不留未提交改动;最终答复带 CI 结果或相关 commit SHA。

## 5. Ask first(先问再做)

- 升级 AGP / Gradle / Kotlin / Compose 版本(历史上有 proguard、Lint 任务依赖、flavor 命名
  等连环坑)。
- 新增第三方依赖,或改 `build-logic` 约定插件。
- 动 `server/`、`market/`(skills 市场结构)、`fastlane/` 元数据(有独立校验 workflow)。
- 重命名公共 API、改 Room 数据库 schema(`app/schemas/` 需同步导出)。
- 任何删除超过 100 行的批量清理。

## 6. 参考文件

- 行数策略全文:`docs/development/kotlin-source-size-policy.md`
- agentic loop / 生成契约:`docs/development/agentic-loop-and-generation-requirements.md`、
  `app/src/main/java/com/lxseek/chat/agent/GenerationContracts.kt`
- README(功能与发布渠道):`README.md` / `README_CN.md`
