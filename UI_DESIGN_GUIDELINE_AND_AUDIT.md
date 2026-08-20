# LxChat 统一 UI 设计准则 + 全项目现状对照审计（任务 18 交付物）

> 调研范围：`app/src/main/java/com/lxseek/chat/ui/**`（基于当前 master 最新代码，`UI_DESIGN_GUIDELINE_AND_AUDIT.md` 为纯调研产出，**不修改任何代码**）。
> 参照基准：ChatGPT 官方 app 极简风（浅色白底黑字 / 深色近黑底近白字、低 elevation、扁平列表 + divider、统一圆角、克制间距）。
> 用途：供任务 17/19（Coder UI 统筹优化）逐项实施。
> 注意：任务 10/11/13/15 已完成的极简化和发送区单排**不计入**不符清单（它们已是正确基线）。审计聚焦「不一致 / 遗漏 / 可统一」点，每条含 `文件:行号 | 问题 | 建议`。

---

## Part A —— 统一 UI 设计准则

### A1. 间距体系（4dp 网格）
| 用途 | 值 | 说明 |
|---|---|---|
| 页面/容器外边距 | `16.dp` | 所有页面一致（当前已基本统一 ✓） |
| 组件内部紧凑间隙 | `4/8.dp` | 图标-文字、按钮内边距、消息段间隙 |
| 文本/元素常规间隙 | `12.dp` | 标题-正文、图标-行文本 |
| 分组/卡片内边距 | `16.dp` | SettingsItem 水平、卡片内容 |
| 块/组之间 | `20/24.dp` | 设置组间距统一取 **24dp**（SettingsGroupColumn 已实现） |
| 行高/触控目标 | **≥44dp**（按钮/会话项）、**≥48dp**（发送/FAB） | ChatGPT 触点标准 |

**目标**：间距值收敛到 `{4, 8, 12, 16, 20, 24}` 子集，禁止随意 5/6/10/14/18/42 等散值。

### A2. 圆角规范（唯一不一致最严重项，现状有 15+ 种值）
| 层级 | 值 | 用途 | 现状 |
|---|---|---|---|
| 徽章/小标签 | `4.dp` | ProviderBadge、code badge | 已 ✓ |
| 常规控件/行/会话项 | `8.dp` | 会话项、附件缩略图、搜索清除钮 | 已 ✓ |
| **卡片 / 内容容器 / 搜索框 / 输入框 / DropdownMenu** | **`12.dp`** | 消息段卡、DrawerSearchBar、Settings 内卡片、全项目 DropdownMenu（设置页已 12dp） | **不统一：ChatBottomBar 16dp、消息段卡 18dp、Welcome 28dp、TaskEditor 24dp** |
| 文档/代码块 | `dimens.codeBackgroundCornerSize` | Markdown（不动） | - |
| BottomSheet 顶角 | `16.dp`（ModalBottomSheet 默认） | 变量选择器等 | SegmentDetailSheet **26dp** 例外 |
| 主要 FAB/发送按钮 | `CircleShape`（全圆） | ComposerSendButton、VoiceMic | 已 ✓ |
| 胶囊 | **退出准则** | 仅保留 WelcomeScreen 主按钮（若改则一并改）；Drawer 按钮已是 FilledTonal 应改 12dp 矩形 | Drawer 42dp CircleShape ✗ |

**目标**：全局圆角收敛到 `{4, 8, 12, 16}` + CircleShape，删除 18/24/26/28/50 等零散值；`RoundedCornerShape(50)` 这类无 `.dp` 后缀的形式统一加 `.dp`。

### A3. 颜色规范
- 一律通过 `MaterialTheme.colorScheme.*` 语义取色；禁止直接 `Color(0x...)`（全屏媒体/视频/相机关闭按钮黑底白字除外）。
- 错误统一 `colorScheme.error`；**禁止 `Color(0xFFE53935)` 手写错误红**。
- 分组标题统一 `onSurfaceVariant`；正文 `onSurface`；次级 `onSurfaceVariant`；弱化 `copy(alpha 0.4f~0.6f)`。
- provider 品牌色（Anthropic/OpenAI）若保留，抽为 `ui/theme` 层常量而非散落 bottombar。
- 搜索高亮（SearchHighlighting）可保留，但抽出命名常量（已有）。

### A4. Elevation 规范
- **常规 UI：`tonalElevation = 0.dp`、`shadowElevation = 0.dp`**（ChatGPT 扁平）；现状大部已做到。
- 仅**浮层**保留 tonalElevation：`DropdownMenu=6.dp`、`Dialog/ModalBottomSheet` 视 Material 默认、`SegmentDetailSheet→0 + 无 shadow`（列为不符）、`VideoSliceDialog/PdfPageSelectDialog` 3dp（浮层合理，保留）。
- **禁止**在可见卡片/容器上用 `tonalElevation ≥ 1.dp`：残留点在 WelcomeScreen(2.dp)、Tasks 页(1.dp)、SystemPromptEditor(2.dp)、SettingsSandboxPage(2.dp)。
- **禁止** `surfaceColorAtElevation()` 取色（ChatBottomBar composerOcclusion 残留）：改 `surfaceContainer` 或 `surfaceContainerLow`。

### A5. 字体规范
- **Settings 体系**：`MaterialTheme.typography`（几何比例，bodyLarge 16sp / bodyMedium 13sp / titleMedium 16sp），已固化。
- **聊天体系**：`ChatType`（15sp userBody / 16sp body 锚点），已固化。
- **目标**：两套并存可接受，但标题目大小应收敛——`ChatType.conversationsTitle 25sp`（L188）对侧栏标签过大 → 建议 16–17sp；`detailTitle 22sp`、`brandTitle 20sp` 合理保留。
- 尺寸单位一律 `sp`，行距跟随当前定义，不新增裸 `TextStyle`。

### A6. 组件规格
| 组件 | 规格 |
|---|---|
| 顶栏 IconButton | **44dp** 触点（现有），但**内部图标统一 24dp**（当前 24/26/30 混用） |
| 次级 IconButton（消息操作行） | 32dp 触点（现状 ✓），图标 16–18dp 一致 |
| 发送区工具按钮 | **28dp** 触点 + 图标 16dp（任务15 已统一 ✓） |
| 发送 FAB / 语音对话入口 | **48dp** CircleShape（已 ✓） |
| 单次 ASR 麦克风 | 40dp + 图标 22dp（VoiceMicButton ✓，保留） |
| 搜索框 | 高 44dp、圆角 12dp（DrawerSearchBar ✓） |
| 列表行 padding | 垂直 12dp（单行）/16dp（双行）、水平 16dp（SettingsItem ✓）；根页行 14dp → 建议统一 12/16 |
| Divider | `0.5dp`、`outlineVariant`、`padding(horizontal=16)`（已 ✓） |
| 分组间距 | `24dp`（SettingsGroupColumn ✓）；根页手写 20dp 处 → 统一 24dp |

### A7. 交互规范（参考前几轮基线）
- DropdownMenu 统一 `tonalElevation=6.dp + containerColor=surfaceContainer + shape=12dp`（设置页已一致性，ChatBottomBar 需对齐 12dp）。
- 全屏媒体/相机/视频覆盖用黑底白字（保留）。

---

## Part B —— 现状不符清单（文件:行号 | 问题 | 建议）

### 优先级说明
- **[高]** 强烈建议修复：明显不一致/违反 ChatGPT 极简风/影响观感。
- **[中]** 建议修复：局部不一致，工作量小。
- **[低]** 可选：细节优化，量力而行。

### B1. 聊天主界面 / 顶栏 / 底栏

| 位置 | 问题 | 建议改法 | 优先级 |
|---|---|---|---|
| `ui/chat/message/MessageItemTimeline.kt:349` | CompactSegmentBlock Surface 圆角 `18.dp`，与消息气泡 12dp、段卡规格不符（任务13 只改了 tonal，未改圆角） | → `12.dp`（与气泡/设置卡一致） | 高 |
| `ui/chat/message/MessageItemTimeline.kt:362` | 同段卡 header clip `RoundedCornerShape(18.dp)` | 同步 12.dp | 高 |
| `ui/chat/message/MessageItemTimeline.kt:468,510` | 段内 thought/tool 子块 clip `18.dp` | 同步 12.dp | 高 |
| `ui/chat/message/MessageItemTimeline.kt:734,739` | TimelineInfoSegmentCard Surface + clip `18.dp` | 同步 12.dp | 高 |
| `ui/chat/bottombar/ChatBottomBar.kt:367,568,637` | 3 处 DropdownMenu `shape=RoundedCornerShape(16.dp)`，全项目其余 DropdownMenu 均 12dp（设置页/顶栏/侧栏） | → `12.dp` | 高 |
| `ui/chat/bottombar/ChatBottomBar.kt:176` | `composerOcclusionColor = surfaceColorAtElevation(2.dp)`，是全项目唯一仍用 elevation 取色的地方（不符合 A4） | → `MaterialTheme.colorScheme.surfaceContainer`（ChatGPT 输入容器同色） | 中 |
| `ui/chat/bottombar/ChatBottomBar.kt:177–180` | composerOcclusionShape 顶角 `24.dp`，与底栏外框 24dp 一致但与其他卡片 12dp 不一致 | 如底栏外框保留 24dp 则内部保留；若统一则双改 12dp（见下行） | 低 |
| `ui/chat/bottombar/ChatBottomBar.kt:78` | `CHAT_BOTTOM_BAR_OUTER_RADIUS = 24.dp` 底栏外框圆角 24dp | ChatGPT 底栏与页面同色无大圆角；建议改为 `12.dp`（与 Drawer 12dp 呼应） | 中 |
| `ui/chat/ChatTopBar.kt:416` | `ChatTopBarCapsule` 的 shape `RoundedCornerShape(16)` **无 `.dp` 后缀**（语法=无单位，单位为默认 px，属笔误） | → `RoundedCornerShape(16.dp)` | 高 |
| `ui/chat/ChatTopBar.kt:113` | 顶栏 `defaultMinSize(minHeight = 140.dp)`，内容行 52dp。140dp 整体高度远超 ChatGPT（单行顶栏 ~52–64dp） | 评估压缩至 ~90–110dp，或保持（v1.0.38 曾刻意 180→140，属渐进收敛；可在 19 中再降一档） | 中 |
| `ui/chat/ChatTopBar.kt:337` | new-chat `Add` 图标 30dp，同栏菜单/返回图标 26dp、搜索区 24dp —— 同顶栏图标尺寸 24/26/30 三套 | 统一 **24dp**（ChatGPT 顶栏图标皆为 24dp） | 中 |
| `ui/chat/ChatTopBar.kt:289,349` | 返回/菜单、MoreVert 图标均为 26dp | 统一 24dp | 中 |
| `ui/chat/ChatTopBar.kt:235,246` | 搜索上下箭头 IconButton 38dp（触点），同顶栏其它 44dp | 统一 44dp（图标 24dp） | 低 |
| `ui/chat/ChatDrawerContent.kt:208,239` | Tasks / New Chat 两按钮 `shape = CircleShape`，高 42dp —— 胶囊按钮与 ChatGPT 侧栏矩形按钮风格不符；且与 DrawerSearchBar(12dp)、会话项(8dp) 不统一 | → `RoundedCornerShape(12.dp)`，保留 42–44dp 高 | 高 |
| `ui/chat/ChatDrawerContent.kt:193` | `ChatType.conversationsTitle`（25sp Bold）侧栏标题过大（见 Type.kt:188） | 建议 16–17sp（`titleMedium` 或新 17sp 常量） | 中 |
| `ui/chat/ChatDrawerContent.kt:299–301` | 会话项 Surface height 44dp + padding vertical 2dp + clip 8dp（触点为 44dp 高含 2dp 内缩） | 已符合 A6 ✓（若统一可去 padding 改 44dp 直渲） | 低 |
| `ui/chat/ChatApp.kt` | 新聊天空态 AnimatedBlobBackground 彩色动画背景（符合品牌但非 ChatGPT 极简） | 评估降低 alpha 或静置为渐变（迭代项） | 低 |
| `ui/chat/message/MessageItem.kt:373` | `RoundedCornerShape(100.dp)` 时间戳/状态胶囊（若为消息时间胶囊属合理保留） | 确认用途；如非胶囊则改 12dp | 低 |
| `ui/chat/message/UserMessageBubble.kt:251` | `RoundedCornerShape(100)`（错误提示块？） | 确认用途；建议 8dp 内容块 | 低 |

### B2. 设置页

| 位置 | 问题 | 建议改法 | 优先级 |
|---|---|---|---|
| `ui/settings/SettingsScreen.kt:52` | SettingsGroupColumn 组间距 24dp ✓ | 无需改（作为基准） | - |
| `ui/settings/SettingsScreen.kt:340,387` | 根页同构（settingsGroups 渲染）行 `padding(horizontal=16, vertical=14)` + 组间 `Spacer(height=20.dp)`：组间距 20dp 与子页 SettingsGroupColumn 24dp 不一致、行垂直 14dp 与 SettingsItem 12/16dp 不一致 | 组间 20.dp → `24.dp`；行 vertical 14.dp → 12/16.dp 对齐子页 | 中 |
| `ui/settings/SettingsScreen.kt:331` | 根页分组标题 `padding(16,12)` vs SettingsGroup 内标题 `padding(16,8)`（L77） | 统一为 `(16,8)` 或 `(16,12)` 二选一 | 低 |
| `ui/settings/SystemPromptEditorPage.kt:311` | 预览卡 `tonalElevation = 2.dp`（可见卡，违反 A4） | → `0.dp` | 中 |
| `ui/settings/SystemPromptEditorPage.kt:472` | 预制变量卡 `tonalElevation = 2.dp` | → `0.dp` | 中 |
| `ui/settings/SystemPromptEditorPage.kt:406` | DropdownMenu tonalElevation 6dp | 保留（浮层合理）✓ | - |
| `ui/settings/SettingsSandboxPage.kt:507` | 某可见卡 `tonalElevation = 2.dp` | → `0.dp` | 中 |
| `ui/settings/SettingsSandboxPage.kt:459` | 终端框圆角 `topStart16/bottomStart16/topEnd28/bottomEnd28` 混合 28dp | → 统一 12dp 或 16dp | 低 |
| `ui/settings/SettingsSandboxPage.kt:596–599` | backendPackages 堆叠形状 24dp/5dp（仿旧卡片 stack） | 设置页已扁平化；若为徽章堆叠可改 12dp/4dp | 低 |
| `ui/settings/SettingsGenerationPage.kt:704,713,722` | 三处 `RoundedCornerShape(16.dp)`（ASR 引擎选择/状态卡？） | 统一 12dp | 中 |
| `ui/settings/SettingsAboutPage.kt:216` | Rating 卡 `RoundedCornerShape(16.dp)` + tonal 0（任务13 已 28→16） | 统一 12dp（可选） | 低 |
| `ui/settings/SettingsShellPage.kt:321–427` | 大量输入框 `RoundedCornerShape(16.dp)`，其余设置页输入框/变量卡多 12dp/16dp 混用 | 全局输入框统一 **12dp**（ChatGPT） | 中 |
| `ui/settings/dataControl/SettingsDataControlPage.kt:814` | `RoundedCornerShape(50)`（导出/主按钮胶囊） | 改 12dp 矩形或 CircleShape 若为 FAB | 低 |
| `ui/settings/SettingsProviderDetailPage.kt:169,339,412` | DropdownMenu shape 12dp ✓ + tonal 6dp ✓ | 作为设置页基准 ✓ | - |
| `ui/settings/SettingsPromptsPage.kt:240` / `SettingsTranscriptionPage.kt:163` / `SettingsSearchPage.kt:151,196,294` / `SettingsMemoryPage.kt:186` / `SettingsMcpPage.kt:233` / `SettingsAppearancePage.kt:199,301,362,496,541,596` | 全部 DropdownMenu 12dp + tonalElevation 6dp ✓ | 一致，无需改 ✓ | - |

### B3. 任务（Tasks）页

| 位置 | 问题 | 建议改法 | 优先级 |
|---|---|---|---|
| `ui/tasks/TasksScreen.kt:189,294,404` | 空态卡 / TaskCard / NewAutomationRow `tonalElevation = 1.dp` 可见卡阴影（违反 A4，与其他扁平页不一致） | → `0.dp`（同时补齐 `shape`→12dp 或维持 stackedShape 24/5） | 中 |
| `ui/tasks/TasksScreen.kt:434–437` | `stackedShape` 24dp/5dp/5dp 堆叠圆角（旧卡片体系遗留，任务页仍卡片化） | 任务列表可保留 stack 形态，但圆角收敛：外缘 **12dp**、相接 **4dp**、间隙 2dp | 中 |
| `ui/tasks/TasksScreen.kt:375` | TaskCard 内 DropdownMenu shape 12dp ✓ | 无需改 | - |
| `ui/tasks/TaskEditorPage.kt:276` | Empty executions 卡 `RoundedCornerShape(24.dp)` + tonal 1dp | → 12dp + tonal 0dp | 中 |
| `ui/tasks/TaskEditorPage.kt:278` | tonalElevation 1dp | → 0dp | 中 |
| `ui/tasks/TaskEditorPage.kt:369` | OutlinedTextField `shape=16.dp` | 统一 12dp | 低 |
| `ui/tasks/TaskEditorSupportingComponents.kt:98,296,379` | 三处按钮 `RoundedCornerShape(28.dp)` 大圆角（Run/编辑类主按钮） | → 12dp 或 CircleShape 视语义；倾向 12dp | 中 |
| `ui/tasks/TaskEditorSupportingComponents.kt:500` | ExecutionRow `tonalElevation = 1.dp` | → 0dp | 中 |
| `ui/tasks/TaskEditorSupportingComponents.kt:562` | DropdownMenu 12dp + tonal 6dp ✓ | 无需改 | - |

### B4. WelcomeScreen（onboarding）—— **残留重灾区**（任务10 只改了视频页，配置页仍旧 Apple 卡片风）

| 位置 | 问题 | 建议改法 | 优先级 |
|---|---|---|---|
| `ui/onboarding/WelcomeScreen.kt:575` | Provider 配置页卡 `Surface RoundedCornerShape(28.dp)` + `tonalElevation=2.dp`（大圆角 + 阴影卡片） | 卡片化处理：圆角 16dp + tonal 0dp，或直接去掉卡片改扁平行 | 高 |
| `ui/onboarding/WelcomeScreen.kt:596` | Provider 选择行 `clip(RoundedCornerShape(28.dp))` | → 12dp | 高 |
| `ui/onboarding/WelcomeScreen.kt:626,726,782` | 3 处配置/导入卡 `RoundedCornerShape(28.dp) + tonalElevation=2.dp` | 同上 | 高 |
| `ui/onboarding/WelcomeScreen.kt:554` | 主按钮 `shape=RoundedCornerShape(50)`（胶囊） | → 12dp 矩形（ChatGPT 主按钮为圆角矩形） | 高 |
| `ui/onboarding/WelcomeScreen.kt:641` | OutlinedButton（导入 GGUF）`RoundedCornerShape(50)` | → 12dp | 高 |
| `ui/onboarding/WelcomeScreen.kt:661,680,692,717` | 3 个 OutlinedTextField `singleLine=true, shape=RoundedCornerShape(50)`（API Key / 模型输入等） | → 12dp（与设置页输入框统一） | 高 |
| `ui/onboarding/WelcomeScreen.kt:764` | Model 选择行 `clip(RoundedCornerShape(28.dp))` | → 12dp | 高 |
| `ui/onboarding/WelcomeScreen.kt:791` | AutoBackup 行 `clip(RoundedCornerShape(24.dp))` | → 12dp | 高 |
| `ui/onboarding/WelcomeScreen.kt:577` | `tonalElevation=2.dp`（视频页图标卡？） | → 0dp | 中 |

> **结论**：WelcomeScreen 全部 `RoundedCornerShape(50/28/24)` + `tonalElevation=2.dp` 都与准则冲突，是整个项目视觉最不统一的一页。任务19 应整体重写为扁平 12–16dp 圆角、0 elevation、主按钮 12dp 的风格。

### B5. 语音 / 媒体覆盖层（浮层类，多数**保留**）

| 位置 | 问题 | 建议改法 | 优先级 |
|---|---|---|---|
| `ui/chat/VoiceConversationOverlay.kt:171` | 中央大圆圈 `tonalElevation=6.dp`（任务13 唯一"保留浮层 6dp"处） | 覆盖层内大色块 → `0.dp`（浮层本身无阴影需求） | 低 |
| `ui/chat/VoiceConversationStatusOverlay.kt:94–95` | `tonalElevation=3.dp + shadowElevation=2.dp`（悬浮状态条） | 覆盖在消息上的浮动条保留 elevation 合理；可透视收敛 shadow→0 保留 tonal 3 | 低 |
| `ui/chat/message/SegmentDetailSheet.kt:322–323` | Sheet 顶角 `26.dp` + `shadowElevation=4.dp` | 顶角 → 16dp（对齐 ModalBottomSheet）；shadow → 0（ChatGPT 底部抽屉无可见阴影） | 高 |
| `ui/chat/message/SegmentDetailSheet.kt:363` | drag handle `RoundedCornerShape(3.dp)` 微圆角 | 保留 ✓（小元素合理） | - |
| `ui/chat/VideoSliceDialog.kt:118` | `tonalElevation=3.dp`（Dialog 浮层） | 保留 ✓ | - |
| `ui/chat/PdfPageSelectDialog.kt:52` | `tonalElevation=3.dp`（Dialog 浮层） | 保留 ✓ | - |
| `ui/chat/ShareSelectionFab.kt:45–46` | `tonalElevation=2.dp + shadowElevation=2.dp`（多选 FAB） | FAB 保留 2dp 合理 ✓ | - |

### B6. 硬编码颜色（违反 A3）

| 位置 | 问题 | 建议改法 | 优先级 |
|---|---|---|---|
| `ui/chat/message/UserMessageBubble.kt:226` | 附件警告文本 `Color(0xFFE53935)` | → `MaterialTheme.colorScheme.error` | 高 |
| `ui/chat/AttachmentThumbnail.kt:87,90` | PDF 缩略图背景/文字 `Color(0xFFE53935)` 手写错误红 | → `colorScheme.error`（或 `errorContainer`），调用处 Composable 直接读取 | 高 |
| `ui/chat/bottombar/ChatBottomBarComponents.kt:38–39` | ProviderBadge 硬编码 Anthropic `0xFFD97757` / OpenAI `0xFF74AA9C` 品牌色 | 抽取为 `ui/theme` 常量（如 `ProviderPalette.kt`）或保留但集中定义；与主色风格需评估一致性 | 中 |
| `ui/chat/message/SearchHighlighting.kt:21–23` | 搜索高亮 3 色常量（已命名） | 保留 ✓（可加注释说明无法用主题色） | - |

### B7. 字体 / 字号

| 位置 | 问题 | 建议改法 | 优先级 |
|---|---|---|---|
| `ui/theme/Type.kt:188` | `conversationsTitle 25sp Bold`（Drawer 标题），比 ChatTopBar 的品牌 20sp / 标题 15-17sp 大得多 | → 16–17sp（`conversationTitleSolo` 17sp 可复用） | 中 |
| `ui/theme/Type.kt:151` | `brandTitle 20sp` 与 `conversationTitle 15sp` 差 5sp，视觉跳动 | 保留（有注释解释）或 brand 收敛 18sp | 低 |
| `ui/settings/SettingsScaffold.kt:80–81` | TopBar 标题 titleMedium 16sp SemiBold（设置页基准） | 作为基准 ✓，无需改 | - |
| `ui/theme/Type.kt` 全局 | Material Typography + ChatType 双体系并存 | 保留双体系（各自场景合理）；不合并 | - |

### B8. 遗留小项（捉漏）

| 位置 | 问题 | 建议改法 | 优先级 |
|---|---|---|---|
| `ui/chat/search/DrawerSearchBar.kt:37` | 高 44dp + 圆角 12dp + tonal 0 + surfaceContainerLow ✓ | 已是基准 ✓ | - |
| `ui/settings/SettingsModelsPresentation.kt:42` | tonal 0 ✓ | 无需改 | - |
| `ui/chat/ChatAppBottomBarSection.kt:149–150` | tonal 0 + shadow 0 ✓ | 无需改 | - |
| `ui/chat/message/MessageItem.kt:150–155` | 气泡 12dp 统一 ✓（任务10/11） | 无需改 | - |
| `ui/chat/message/UserMessageBubble.kt:284–285` | 附件的 DropdownMenu 12dp + tonal 6dp ✓ | 无需改 | - |
| `ui/chat/bottombar/ComposerSendButton.kt:185–192` | 48dp FAB，MIC 态 0dp / 其他 2dp elevation ✓ | 无需改 | - |
| `ui/chat/bottombar/VoiceMicButton.kt:47,61` | 40dp 触点 + 22dp 图标（单次 ASR） | 保留 ✓ | - |
| `ui/settings/SettingsShellPage.kt:274` | `RoundedCornerShape(4.dp)` 小标签 | 保留 ✓ | - |
| `ui/chat/message/MessageBubbleAssets.kt:401,793` | 代码块圆角走 dimens ✓ | 无需改 | - |

---

## Part C —— 给任务19 Coder 的实施建议顺序

1. **P0（高优先级，改观最大）**：WelcomeScreen 全部卡片/胶囊/圆角/elevation 重写（B4）；MessageItemTimeline 18dp→12dp（B1）；ChatBottomBar DropdownMenu 16→12dp（B1:367/568/637）；ChatTopBar shape 补 `.dp`（B1:416）；Drawer 按钮改矩形（B1:208/239）；SegmentDetailSheet 26dp→16dp + shadow→0（B5:322–323）。
2. **P1（中）**：色板去硬编码 error 红（B6）；`surfaceColorAtElevation` 改 surfaceContainer（B1:176）；系统内可见卡 tonalElevation→0（B3/B4 列出的 1dp/2dp 点）；输入框圆角统一 12dp；ChatType.conversationsTitle 收敛；ChatBottomBar 外框半径 24→12dp 评估。
3. **P2（低/可选）**：顶栏高度再压缩、顶栏图标 24dp 统一、Tasks stackedShape 圆角收敛、Settings 根页行 padding/组间距对齐。
4. 每批改动后 `git commit && git push`，CI 全绿验收（`verifyKotlinFileSize` 每文件 ≤999 行，勿扩大 ChatBottomBar 等接近上限文件），最后回写 AGENTS.md（§4/§6/§9）。
5. 若需动 28 个 SettingsScaffold 调用点签名，保持 `CollapsingSettingsScaffold/LazyScaffold` 兼容（任务13 已如此做过一次，避免回归）。