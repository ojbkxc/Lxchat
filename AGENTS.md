# AGENTS.md — 强制规则

以下规则为硬性约束，任何改动都必须遵守：

## 1. 分支与编译验证

- **只能在 `main` 分支上改动**：任何修改都必须基于 `main` 分支进行，不得在功能分支或其他分支上开发/提交。提交只能直接推送到 `main` 分支，由 CI 编译验证。
- **禁止本地编译**（不要运行 `gradlew build` / `gradlew assembleDebug` 等任何本地构建命令）。
- 所有编译验证只能通过 **提交到 GitHub 后由 CI 编译** 完成。本地只做代码修改，提交推送后等 CI 结果。

## 2. 版本号规则

- 发布正式版本时，版本号只能在上一个正式版本的基础上**最后一位 +1**。
  - 例：上一个正式版本是 `v1.0.1`，则下一个正式版本必须是 `v1.0.2`（不能跳号、不能改中间位）。

## 3. 文件写入与编码规则

- **禁止用 PowerShell 原生命令写入含非 ASCII 内容的文件**：PowerShell 的 `Set-Content` / `Out-File` / 重定向等原生命令容易因编码页不匹配导致 UTF-8 中文写入乱码。
- **写文件必须通过 Python 脚本或 `apply_patch` 工具**：Python 脚本需显式 `encoding='utf-8'` 读写；`apply_patch` 是首选方式。
- **所有源码文件必须保持 UTF-8 编码**（无 BOM）。禁止将文件转为 GBK 或其他编码。
- **读取含中文的文件时必须指定编码**：PowerShell 用 `Get-Content -Encoding UTF8` 或先 `chcp 65001`，Python 用 `encoding='utf-8'`。
