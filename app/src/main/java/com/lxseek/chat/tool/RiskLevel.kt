package com.lxseek.chat.tool

/**
 * Five-tier risk classification for agent tools, inspired by Marcel SSH.
 *
 * - [ReadOnly]: read-only operations (ls, cat, grep, search, list).
 * - [LowRisk]: minor local mutations (create/edit memory, stop a loop, generate an image).
 * - [Moderate]: operations with side effects on external systems (shell commands, create tasks).
 * - [HighRisk]: destructive or hard-to-reverse operations (file write/edit, delete, stop jobs).
 * - [Destructive]: catastrophic irrecoverable operations (mkfs, shred, dd to a block device).
 */
enum class RiskLevel {
    ReadOnly,
    LowRisk,
    Moderate,
    HighRisk,
    Destructive;

    /** True for [HighRisk] and [Destructive]. */
    fun isDestructive(): Boolean = this == HighRisk || this == Destructive

    /** True for anything that mutates state (i.e. not [ReadOnly]). */
    fun isWritable(): Boolean = this != ReadOnly
}
