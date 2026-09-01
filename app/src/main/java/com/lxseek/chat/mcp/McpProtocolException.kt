package com.lxseek.chat.mcp

/**
 * MCP JSON-RPC 协议层错误：服务器对某个请求返回了明确的 error 响应。
 *
 * 与连接失败（IOException 家族）不同——协议错误代表服务器已应答、连接本身健康，
 * 原样重试同样的请求通常无意义，不应触发客户端的断连标记（markError）与
 * 重连退避（scheduleRetry）。因此本类故意不继承 [java.io.IOException]，
 * 以便上层 catch 逻辑把两者区分开。
 */
internal class McpProtocolException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)