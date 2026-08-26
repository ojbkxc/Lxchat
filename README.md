<div align="center">
  <img src="app/src/main/assets/lxchat_transparent_large.png" alt="LxChat Logo" width="120" />

  # LxChat

  **BYOK LLM client with multi-provider access, agentic workflows, and remote device control.**

  [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-Native-blue.svg)](https://kotlinlang.org/)
  <br/>**English** | [中文](README_CN.md)

  <img src="assets/feature_graphic.png" alt="LxChat — A BYOK AI App that takes back your data sovereignty." width="100%" />
</div>

## Download

**Latest: v1.0.30**

[![F-Droid](https://img.shields.io/badge/F--Droid-Install-blue?logo=fdroid)](https://f-droid.org/packages/com.lxseek.chat/)
&nbsp;&nbsp;
[![Google Play](https://img.shields.io/badge/Google_Play-Install-blue?logo=google-play)](https://play.google.com/store/apps/details?id=com.lxseek.chat)
&nbsp;&nbsp;
[![GitHub Releases](https://img.shields.io/badge/GitHub-Releases-blue?logo=github)](https://github.com/ojbkxc/lxchat/releases)

- **F-Droid (Recommended)** — Install via [F-Droid](https://f-droid.org/), search for **LxChat**.
- **Google Play** — Install from [Google Play Store](https://play.google.com/store/apps/details?id=com.lxseek.chat).
- **GitHub Releases** — Download the latest `.apk` from the [Releases page](https://github.com/ojbkxc/lxchat/releases).
- **Build from Source** — Clone and build with Android Studio (see [Getting Started](#getting-started)).

---

**LxChat** — a BYOK Android client for AI power users. Connect to 9+ built-in providers (plus unlimited custom endpoints) with your own keys, branch conversations non-linearly, run models locally via llama.cpp, control remote machines through encrypted shell, and automate workflows on a schedule. Everything stored on-device, nothing logged elsewhere. Open source, MIT licensed.

## Based On

LxChat is a derivative project based on **[Agora](https://github.com/ojbkxc/Agora)**. It absorbs Agora's full feature set and continues its development under a new name. Capabilities carried over from Agora include:

- **Multi-provider BYOK access** — connect to 9+ LLM providers with your own keys, plus unlimited custom endpoints
- **Tree-structured branching conversations** — edit any past message and explore alternative branches without losing context
- **On-device inference** — local GGUF models via llama.cpp, fully offline
- **Agentic tool chains** — web search, code execution, image generation, memory, and RAG over chat history
- **Conch encrypted remote shell** — ECDH + AES-256-GCM end-to-end encrypted remote device control
- **Automation & scheduling** — cron-style and looping timed workflows
- **Privacy-first design** — everything stored locally, no telemetry

The internal identifiers (`agora_db`, `agora_llama`, `agora_proot`, the `Agora*` class names, etc.) have been fully renamed to `lxchat*` so the original token no longer appears anywhere in the source or the built APK.

In addition to the inherited Agora capabilities, LxChat introduces a new **IM Gateway** subsystem (iLink protocol-based WeChat auto-reply and multi-platform messaging) that is not present in the upstream project.

## Screenshots

<table>
<tr>
<td width="33%"><img src="assets/screenshot_1.jpg" alt="Chat" width="100%"/></td>
<td width="33%"><img src="assets/screenshot_2.jpg" alt="Tools" width="100%"/></td>
<td width="33%"><img src="assets/screenshot_3.jpg" alt="Settings" width="100%"/></td>
</tr>
</table>

## Why LxChat?

- **No Middlemen:** Direct API connections. No telemetry, no tracking, no corporate servers logging your conversations. Everything lives locally in a Room database.
- **Non-Linear Thought:** A tree-structured message database lets you edit any past message, regenerate responses, and explore alternative branches without losing context.
- **Agentic by Default:** Multi-round tool calling with web search, code execution, remote file operations, memory management, and semantic conversation search.
- **Remote Control:** Manage servers, edit files, and search code on remote machines via the [Conch](https://github.com/ojbkxc/conch) protocol — end-to-end encrypted with ECDH + AES-256-GCM.

## Features

### Multi-Provider Access
- **9 built-in providers:** OpenAI, Anthropic, Google Gemini, DeepSeek, Qwen (DashScope), Groq, OpenRouter, Ollama, Local (GGUF via llama.cpp)
- **Unlimited custom providers** with arbitrary base URLs and API keys
- **BYOK:** Bring your own API keys — no subscriptions, no middlemen
- **Multiple API keys per provider** with named aliases for easy rotation
- Per-provider base URL override for proxies and self-hosted endpoints

### Agentic Tools
- **Web Search** — DuckDuckGo Lite (anonymous, no key), Brave, Kagi, Serper, Tavily, and SearXNG integration
- **Code Execution** — Gemini code execution for running and testing code inline; Alpine Linux sandbox via PRoot with SAF file access
- **Image Generation** — BYOK text-to-image via OpenAI-compatible `/v1/images/generations`, rendered inline in chat
- **Remote Shell & File I/O** — Execute commands, read/write/edit/glob/grep files on remote servers via [Conch](https://github.com/ojbkxc/conch)
- **Memory** — Persistent active memory and saved memory files across conversations
- **Conversation Search** — RAG-powered semantic search over chat history

### Thinking & Reasoning
- Deep reasoning: OpenAI o1/o3, Anthropic extended thinking, Gemini thinking, DeepSeek-R1, Qwen QwQ
- Configurable thinking level (low/medium/high)
- Streaming think-tag renderer with collapsible UI and duration tracking

### On-Device Intelligence
- **Local LLM inference** via llama.cpp — run GGUF models entirely offline
- **Local embeddings** for on-device semantic search (RAG)
- **Ollama** provider for self-hosted models on your local network

### Voice & ASR Input
- **Online transcription** via OpenAI-compatible Whisper endpoints (BYOK)
- **Offline transcription** with Vosk models — no network required
- **On-device system speech recognition** as a zero-config fallback
- **Single-shot dictation** to the input box, and **multi-turn voice conversations** with live waveform visualization

### Remote Device Control (Conch Protocol)
- ECDH key exchange + AES-256-GCM encryption + HMAC-SHA256 signing
- Token bucket rate limiting and nonce-based anti-replay protection
- **Multi-device support** — configure and switch between multiple remote servers
- **MCP integration** — Conch as a Claude Desktop MCP server

### IM Gateway & Auto-Reply
- **WeChat (微信) auto-reply** via iLink protocol — scan QR to login, AI auto-replies to incoming messages
- **Multi-platform IM support** — WeChat, Telegram, Lark, DingTalk, WeCom, QQ, Discord, Slack
- **System notification auto-reply** — listen to WeChat notifications and trigger AI responses
- **Media support** — send/receive images, files, voice messages via iLink CDN
- **Message type support** — text, voice, video, location, link, card, emoji
- **Rate limiting & cooldown** — prevent triggering WeChat bans with configurable delays

### Knowledge Management
- **RAG semantic search** across all past conversations using cosine similarity
- Configurable similarity threshold and keyword/model search methods
- Selectable embedding model (remote or local), independent of chat model
- **Context window management** with real-time token counting and sliding window
- Visual context rollout indicator dims messages outside the active window

### Automation & Scheduled Tasks
- **Cron-style scheduling** — run prompts or workflows at fixed times via `CronExpression`
- **Recurring loops** — repeat tasks on an interval with `LoopManager`
- **Background execution** through `AutomationScheduler` and an alarm receiver, independent of the foreground app
- Chain tools, search, and memory into timed multi-step agentic jobs

### Data Portability
- **.lxchat Export/Import:** Conversations, memories, prompts, settings, and API keys in one portable file
- **Merge, Replace, and Skip** import strategies
- **Auto Backup** — periodic WorkManager-based backup with configurable period, categories, and retention
- **Third-Party Import:** Claude and ChatGPT export formats (.zip / .json)
- API key safety warnings for both export and import workflows

### Customization
- **System prompt templates** with three-section editor (system prompt + user prepend + user append)
- Variable substitution: `{sent_time}`, `{sent_date}`, and extensible variable system
- Per-conversation model and system prompt switching
- Per-message model selection from the chat bottom bar
- Per-conversation generation overrides (temperature, max tokens, penalties)
- **Auto title generation** with configurable model

### UI & UX
- Modern Material 3 design in Jetpack Compose with dynamic color (Material You)
- Light / Dark / System theme modes with configurable color schemes
- **Non-linear branching:** Edit any past message and branch into alternative conversation paths
- Real-time streaming with message anchoring and animated auto-scrolling
- Haptic feedback throughout the UI (long-press, selection, success/error)
- Immersive gesture-driven image and media viewer
- Markdown rendering with syntax highlighting, LaTeX math, and code blocks
- Image, video, PDF, and file attachment support with thumbnails
- iOS-style collapsing large-title in settings with shared page transition animations
- Blur effects with configurable performance toggle
- English, Chinese, and Traditional Chinese language support

## Documentation

📖 **[Browse the User Manual](https://ojbkxc.github.io/LxChat/)** — 24 pages covering installation, providers, tools, search, memory, shell, and more.

## Getting Started

### Prerequisites
- [Android Studio](https://developer.android.com/studio) (Ladybug or newer recommended)
- Android SDK 34+
- A valid API key from a supported provider

### Quick Setup

<table>
<tr>
<td width="20%"><b>① Launch</b><br/>Open LxChat on your device.</td>
<td width="20%"><b>② Settings</b><br/>Open <b>Settings</b> from the nav bar.</td>
<td width="20%"><b>③ API Key</b><br/>Select a <b>Provider</b> and add your <b>API Key</b>.</td>
<td width="20%"><b>④ Models</b><br/><b>Models</b> → "Sync from All Providers."</td>
<td width="20%"><b>⑤ Customize</b><br/>System prompts, context, search, memory.</td>
</tr>
</table>

### Running Local Models

<table>
<tr>
<td width="25%"><b>① Place</b><br/>Put a GGUF model file on your device.</td>
<td width="25%"><b>② Import</b><br/>Settings → Provider → Local → "Import GGUF Model".</td>
<td width="25%"><b>③ Configure</b><br/>Set context size, temperature, and other parameters.</td>
<td width="25%"><b>④ Select</b><br/>Choose your local model from the chat picker.</td>
</tr>
</table>

### Setting Up Remote Shell (Conch)

<table>
<tr>
<td width="33%"><b>① Deploy</b><br/>Deploy the <a href="https://github.com/ojbkxc/conch">Conch server</a> on your target machine.</td>
<td width="33%"><b>② Add Device</b><br/>Settings → Shell Devices → add URL and API key.</td>
<td width="33%"><b>③ Use</b><br/>The model auto-discovers shell devices for commands, files, and search.</td>
</tr>
</table>

## Tech Stack

- **Language:** [Kotlin](https://kotlinlang.org/)
- **UI Framework:** [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3, dynamic color)
- **Architecture:** MVVM with Kotlin Coroutines & Flow
- **Local Storage:** [Room Database](https://developer.android.com/training/data-storage/room) with tree-structured message schema & DataStore Preferences
- **Networking:** OkHttp with SSE streaming
- **Serialization:** `kotlinx.serialization`
- **Native:** llama.cpp via Android NDK (CMake) for on-device LLM inference and embeddings
- **Image Loading:** Coil
- **Markdown:** Multiplatform Markdown Renderer M3
- **Math:** JLaTeXMath-Android
- **IM Gateway:** iLink protocol (WeChat), multi-platform push/poll channels
- **Voice Recognition:** Vosk (offline ASR), OpenAI Whisper (online)
- **Media:** ExoPlayer (video playback), CameraX (in-app camera)
- **SSH:** JSch for remote device management

## Contributing

Contributions are welcome! Feel free to fork the repository, submit pull requests, or open an issue.

## Privacy

LxChat does not collect, store, or transmit any personal data. All conversations, API keys, and settings are stored locally on your device. Messages are sent directly from your device to the AI provider you configure — no intermediary servers, no telemetry, no tracking. See the full [Privacy Policy](PRIVACY.md).

## License

This project is open-source under the [MIT License](LICENSE).
