package com.lxseek.chat.sandbox

class PlaySandboxManagerFactory : SandboxManagerFactory {
    override fun create(): SandboxManager = PlaySandboxManager()
    override fun isAvailable(): Boolean = false
}
