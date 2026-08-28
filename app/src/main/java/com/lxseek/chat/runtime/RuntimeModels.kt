package com.lxseek.chat.runtime

import kotlinx.serialization.Serializable

/**
 * 运行时引擎类型。每种类型对应一种可下载的原生运行时二进制（Node / Python / ffmpeg）。
 *
 * 引擎的稳定 id（如 [RuntimeEngineType.nodeInkos]）同时作为市场 RUNTIME 条目的 plugin id，
 * 供 `market_install` / `runtime_start` 等工具按 id 解析。
 */
enum class RuntimeEngineType(val id: String) {
    NODE("node"),
    PYTHON("python"),
    FFMPEG("ffmpeg");

    companion object {
        /** @actalk/inkos 网文创作引擎（依赖 node:sqlite，要求 node >= 22.5）。 */
        const val NODE_INKOS = "runtime-node-inkos"

        /** webnovel-writer 网文创作引擎（GPL-3.0，依赖 runtime-python，要求 python >= 3.10）。 */
        const val PYTHON_WEB_NOVEL = "runtime-python-webnovel"

        private val BY_ID = entries.associateBy { it.id }

        fun fromId(id: String): RuntimeEngineType? = BY_ID[id]
    }
}

/**
 * 统一的多版本约束模型：引擎 manifest 声明「提供版本清单 versions + 最低版本 min_version」，
 * 技能/工具 manifest 通过 [runtimeRequirements] 声明若干条 `runtime >= minVersion` 约束。
 */
@Serializable
data class RuntimeManifest(
    val id: String,
    val type: String,
    val version: String,
    /** 入口相对路径（相对引擎安装根目录），如 "bin/node"、"inkos/run.js"。 */
    val entry: String? = null,
    /** 启动命令模板；`{root}` 会被替换为引擎安装根目录绝对路径。 */
    val startCommand: List<String> = emptyList(),
    /** 二进制来源与合规说明（如 nodejs-mobile 构建、来源仓库与版本）。 */
    val binarySource: String? = null,
    /**
     * 依赖的另一个已登记运行时引擎 id（如 "runtime-python"）。非空时：
     * 本引擎包不含其二进制，启动前自动确保依赖引擎已安装（按需下载），并可用 `{depRoot}`
     * 在 [startCommand] 中引用依赖引擎的安装根目录（如在其下取 python 可执行文件）。
     */
    val requiresEngine: String? = null,
    /** 许可证标识（如 AGPL-3.0）。 */
    val license: String? = null,
    /** 许可证/源码链接。 */
    val sourceUrl: String? = null,
    /** 引擎提供的全部可用版本。 */
    val versions: List<String> = emptyList(),
    /** 引擎自身支持的最低版本。 */
    val minVersion: String? = null,
    /** 引擎对其它运行时的版本要求（如 inkos 要求 node >= 22.5）。 */
    val runtimeRequirements: List<RuntimeRequirement> = emptyList(),
)

/** 运行时的最小版本约束声明（技能/工具使用它约束运行时版本）。 */
@Serializable
data class RuntimeRequirement(
    val runtime: String,
    val minVersion: String,
)

/** 单个已安装运行时引擎的状态快照（供工具与设置页展示）。 */
data class RuntimeStatus(
    val engineId: String,
    val installed: Boolean,
    val installedVersion: String? = null,
    /** 已落盘的全部可用版本（多版本并存）。 */
    val installedVersions: List<String> = emptyList(),
    val running: Boolean = false,
    val downloadState: String? = null,
    val message: String? = null,
)

/** 下载/安装过程状态。 */
enum class DownloadState {
    IDLE,
    DOWNLOADING,
    INSTALLED,
    FAILED,
}