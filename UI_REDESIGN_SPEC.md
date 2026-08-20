# UI 推倒重新设计规格（参照 ChatGPT 官方 app 极简风）

> **基线**：master HEAD = `5f3a126b`（含任务17/19 P0/P1/P2 优化 + 用户 composer bar 改动 `a6b48e45`）。
> **原则**：推倒之前所有 UI 决策（`UI_DESIGN_GUIDELINE_AND_AUDIT.md` 的 P0/P1/P2 批次决策不再生效），从 ChatGPT 官方 app 极简风出发**从零重新定义**每个页面的目标视觉规格。
> **范围**：仅改视觉（布局/间距/圆角/颜色/elevation/字体/组件规格），**不动**业务逻辑/数据流/事件处理/Composable 签名。
> **硬约束**（来自 AGENTS.md §R0/§2）：仅 en/zh 双语；无自定义字体（`FontFamily.Default`/`Monospace`）；每 Kotlin 文件 ≤ 999 行；编译验证走 GitHub CI。

---

## Part A —— 全局设计令牌（Design Tokens）

> **设计哲学**：ChatGPT 官方 app 的核心是「中性极简」——白/浅灰背景、深灰文字、最少装饰、用色块与分隔线而非阴影区分层级、品牌色仅用于强调与可操作元素。整个 UI 只有一套统一的间距/圆角/elevation/字体节奏，所有页面共享。

### A1. 间距体系（Spacing）

**6 级间距阶梯**，所有页面共用，禁止使用阶梯外的值（如 5/6/7/9/10/11/14/18dp 须收敛到最近档位）。

| Token | 值 | 用途 |
|---|---|---|
| `space0` | 0dp | 无间距（贴合） |
| `space1` | 4dp | 紧凑间距（图标内 padding、紧凑组件内元素） |
| `space2` | 8dp | 标准小间距（列表项内元素、消息行垂直 padding、组内 divider 与内容） |
| `space3` | 12dp | 标准中间距（设置项垂直 padding、组标题 padding、消息气泡内 padding） |
| `space4` | 16dp | 标准大间距（页面水平 padding、组间小间距、卡片内 padding） |
| `space5` | 20dp | 宽松间距（Drawer 顶/底 padding） |
| `space6` | 24dp | 组间间距（设置组之间、消息段卡之间、页面底 Spacer） |
| `space7` | 32dp | 页面底 Spacer、欢迎页大间距 |

**特殊值**（仅限特定场景，不纳入通用阶梯）：
- `48dp`：页面外边距（欢迎页 Continue 按钮 bottom padding）
- `44dp`：最小触点高度（IconButton、会话项）
- `56dp`：设置项含 supporting text 的最小高度
- `64dp`：设置顶栏高度（`SettingsBarHeight`）
- `72dp`：欢迎页图标尺寸
- `110dp`：聊天顶栏最小高度（含状态栏渐变区）
- `200dp`：语音对话覆盖圆圈直径

### A2. 圆角体系（Corner Radius）

**5 档圆角 + CircleShape**，禁止使用 16dp 以上的非全圆角值（28dp 等须收敛）。

| Token | 值 | 用途 |
|---|---|---|
| `corner0` | 0dp | 无圆角（全宽 divider、终端文本块） |
| `corner1` | 4dp | 小圆角（标签、badge、堆叠卡尾项、终端内嵌块） |
| `corner2` | 8dp | 中圆角（侧边栏会话项、设置卡片、任务卡） |
| `corner3` | 12dp | **标准圆角**（消息气泡、输入框、DropdownMenu、按钮、Surface 卡片、BottomSheet 顶角、composer 容器顶角） |
| `corner4` | 16dp | 大圆角（仅 BottomSheet/Dialog 浮层顶角、SegmentDetailSheet 顶角） |
| `CircleShape` | ∞ | 全圆（FAB、发送按钮、语音圆圈、状态点、头像、dot indicator） |

**禁用值**：`RoundedCornerShape(50)` / `RoundedCornerShape(100.dp)`（历史残留，统一改 `CircleShape`）；`RoundedCornerShape(18/20/24/26/28.dp)`（统一改 12dp 或 16dp）。

**堆叠卡 shape**（任务卡/沙盒包堆叠）：首项 `RoundedCornerShape(topStart=12, topEnd=12)`、尾项 `RoundedCornerShape(bottomStart=12, bottomEnd=12)`、中间项 `RoundedCornerShape(0)`、单项 `RoundedCornerShape(12)`。

### A3. 颜色体系（Colors）

**全语义化颜色**，禁止硬编码 `Color(0xFFxxxxxx)`（仅 `ProviderPalette.kt` 品牌色常量 + `SearchHighlighting.kt` 高亮色例外，须加 KDoc 注释理由）。

#### 浅色配色（Light）
| 语义色 | 用途 | 建议值（MIDNIGHT 预设） |
|---|---|---|
| `primary` | 品牌强调、可操作元素、选中态 | 蓝紫色系 |
| `onPrimary` | primary 上的文字/图标 | 白/近白 |
| `primaryContainer` | 用户消息气泡背景、selected 标签 | 浅蓝紫 |
| `onPrimaryContainer` | primaryContainer 上的文字 | 深蓝紫 |
| `secondary` | 次要强调（语音 speaking 态） | 暖灰 |
| `secondaryContainer` | Drawer 选中会话项背景、ContextCompactPill | 浅灰 |
| `onSecondaryContainer` | secondaryContainer 上的文字 | 深灰 |
| `tertiary` | 第三强调（语音 transcribing/processing 态） | 中性灰 |
| `background` | 页面背景、顶栏背景 | 白/近白 |
| `onBackground` | 背景上的主文字 | 近黑 |
| `surface` | 卡片/Drawer/Dialog 背景 | 白 |
| `onSurface` | surface 上的主文字 | 近黑 |
| `surfaceVariant` | 次要表面（占位、禁用态） | 浅灰 |
| `onSurfaceVariant` | 次要文字（supporting text、label、icon tint） | 中灰 |
| `surfaceContainerLow` | DrawerSearchBar 背景 | 浅灰 |
| `surfaceContainer` | DropdownMenu/AlertDialog/composer 容器背景 | 浅灰 |
| `surfaceContainerHigh` | PillTabSwitcher 选中态背景 | 中浅灰 |
| `surfaceContainerHighest` | 工具结果块/JSON 块背景 | 中灰 |
| `outline` | 边框、强 divider | 中灰 |
| `outlineVariant` | 弱 divider（0.5dp 设置项分隔线） | 浅灰 |
| `error` | 错误、删除、破坏性操作 | 红 |
| `onError` | error 上的文字 | 白0 |
| `errorContainer` | 错误消息气泡背景 | 浅红 |

#### 深色配色（Dark）
对应浅色各档，亮度翻转。`background`/`surface` 系列用近黑/深灰，`onSurface` 系列用近白/浅灰。

#### 品牌色（ProviderPalette，保留）
- `ProviderPalette.Anthropic = Color(0xFFD97757)`（Claude 橙）
- `ProviderPalette.OpenAI = Color(0xFF74AA9C)`（OpenAI 绿）
- 用途：ProviderBadge 仅此一处允许硬编码色。

#### 搜索高亮色（SearchHighlighting，须改用语义色或加注释）
- 当前：`0xFFFFD54F`（黄）/ `0xFFFFA000`（橙）/ `0xFF241A00`（深棕）
- 目标：改用 `MaterialTheme.colorScheme.tertiaryContainer` / `tertiary` / `onTertiaryContainer`，或保留硬编码但加 KDoc 注释「搜索高亮需跨主题固定色，故硬编码」。

#### 语音渐变背景（VoiceGradientBackground，须改用语义色）
- 当前：`0xFFFF4FD8`（粉）/ `0xFF3D8BFF`（蓝）
- 目标：改用 `primary` + `tertiary` 双色渐变，或 `secondary` + `primary`。

### A4. Elevation 体系

**3 档 elevation**，绝大多数组件 0dp，用色块/分隔线区分层级。

| 档位 | tonalElevation | shadowElevation | 用途 |
|---|---|---|---|
| 平面 | 0dp | 0dp | **默认**：卡片/气泡/顶栏/底栏/Drawer/设置项/任务卡/欢迎页 |
| 浮层 | 1-3dp | 0dp | DropdownMenu（6dp 保留）、BottomSheet 顶角、SegmentDetailSheet |
| 悬浮 | 4-8dp | 2dp | FAB（发送按钮 2dp、滚动到底部 4dp）、ShareSelectionFab、语音圆圈 |

**规则**：
- 所有 `Surface`/`Card` 默认 `tonalElevation = 0.dp, shadowElevation = 0.dp`，除非是浮层/悬浮组件。
- `DropdownMenu` 统一 `tonalElevation = 6.dp`（Material 3 浮层标准值）。
- `ModalBottomSheet`/`SegmentDetailSheet` 顶角 `tonalElevation = 1.dp, shadowElevation = 0.dp`（用 `surfaceContainer` 色块区分，不靠阴影）。
- FAB `shadowElevation = 2.dp`（发送按钮可发时）/ `0.dp`（禁用时）。
- 禁用 `surfaceColorAtElevation(N.dp)`（已基本清除，统一用 `surfaceContainer` 系列语义色）。

### A5. 字体体系（Typography）

**双体系保留**（用户偏好 PREFERENCE_1）：设置页用 Material `Typography`，聊天区用 `ChatType` 体系。两者共享 `chatFontFamily`（由 `Theme.kt` 根据 fontPreference 设置，默认 `FontFamily.Default`）。

#### A5.1 Material Typography（设置页/通用）
几何级数 1.2 比例，锚点 body=16sp。**保留现有 Type.kt 定义**（已对齐 ChatGPT 极简风）。

| Tier | fontSize | fontWeight | lineHeight | 用途 |
|---|---|---|---|---|
| displayLarge | 57sp | Normal | 66sp | （极少用） |
| displayMedium | 48sp | Normal | 55sp | （极少用） |
| displaySmall | 40sp | Normal | 46sp | （极少用） |
| headlineLarge | 33sp | Normal | 41sp | （极少用） |
| headlineMedium | 28sp | Normal | 35sp | 欢迎页标题 |
| headlineSmall | 23sp | Normal | 29sp | （少用） |
| titleLarge | 19sp | Normal | 25sp | （少用） |
| titleMedium | 16sp | Medium | 21sp | 设置顶栏标题、任务卡标题 |
| titleSmall | 13sp | Medium | 17sp | 设置组小标题 |
| bodyLarge | 16sp | Normal | 23sp | 设置项 headline、欢迎页描述 |
| bodyMedium | 13sp | Normal | 19sp | 设置项 supporting text |
| bodySmall | 11sp | Normal | 16sp | （少用） |
| labelLarge | 13sp | Medium | 18sp | 设置组标题、按钮文字 |
| labelMedium | 11sp | Medium | 15sp | 状态标签 |
| labelSmall | 11sp | Medium | 15sp | 微标签 |

#### A5.2 ChatType（聊天区）
1.15 比例，锚点 body=15sp（Outfit 高 x-height，15sp 读感≈16sp Roboto）。**保留现有 Type.kt 定义**。

| Tier | fontSize | fontWeight | lineHeight | 用途 |
|---|---|---|---|---|
| brandTitle | 20sp | Bold | 26sp | 新聊天态品牌名 |
| sheetTitle | 19sp | Bold | 25sp | BottomSheet 标题 |
| conversationTitle | 15sp | Bold | 20sp | 顶栏会话标题（带 token 副标题） |
| conversationTitleSolo | 17sp | Bold | 22sp | 顶栏会话标题（无副标题） |
| input | 16sp | Normal | 23sp | composer 输入框 |
| body | 16sp | Normal | 24sp | AI 消息正文 |
| userBody | 15sp | Normal | 22sp | 用户消息正文 |
| thoughtBody | 13sp | Normal | 19sp | 思考块正文 |
| thoughtTitle | 13sp | Medium | 19sp | 思考块标题 |
| errorBody | 13sp | Medium | 18sp | 错误消息 |
| meta | 12sp | Medium | 17sp | 状态标签 |
| metaNormal | 12sp | Normal | 17sp | 状态标签（非粗） |
| micro | 11sp | Medium | 15sp | token 计数、badge |
| code | 14sp | Normal(Mono) | 20sp | 代码块 |
| detailTitle | 22sp | Bold | 28sp | SegmentDetailSheet 标题 |
| ratingTitle | 28sp | Bold | 35sp | 评分卡标题 |
| conversationsTitle | 16sp | Bold | 22sp | Drawer "Conversations" 标题 |
| drawerButton | 14sp | Medium | 20sp | Drawer 按钮 |
| drawerSearch | 16sp | Normal | 23sp | Drawer 搜索框 |
| mdH1-H6 | 22/19/17/16/15/15sp | Bold/SemiBold/Medium | — | AI markdown 标题 |
| thH1-H6 | 18/16/15/14/13/13sp | Bold/SemiBold/Medium/Normal | — | 思考块 markdown 标题 |

### A6. 组件规格（Component Specs）

| 组件 | 规格 |
|---|---|
| **最小触点** | 44×44dp（IconButton、会话项、Drawer 按钮） |
| **图标尺寸** | 16dp（紧凑工具栏）/ 18dp（DropdownMenuItem leading）/ 20dp（Drawer 按钮、搜索图标）/ 24dp（标准 IconButton 内、顶栏、FAB 内） |
| **按钮高度** | 40dp（Drawer Tasks/NewChat，紧凑）/ 42dp（Drawer 主按钮）/ 48dp（FAB、发送按钮、语音圆圈）/ 52dp（ShareSelectionFab）/ 56dp（设置项含 supporting） |
| **输入框** | OutlinedTextField/TextField 圆角 12dp，contentPadding (16,12,16,16)dp |
| **DropdownMenu** | 圆角 12dp，containerColor `surfaceContainer`，tonalElevation 6dp |
| **AlertDialog** | containerColor `surfaceContainer`，标题 Bold，确认按钮 error 色用于破坏性操作 |
| **HorizontalDivider** | thickness 0.5dp，color `outlineVariant`，horizontal padding 16dp（设置项间） |
| **消息气泡** | 用户 `primaryContainer`/`onPrimaryContainer` 右对齐，AI `Color.Transparent`/`onSurface` 左对齐，错误 `errorContainer`/`onErrorContainer` 居中，圆角 12dp，widthIn max 300dp（用户） |
| **消息行** | padding vertical 6dp，fillMaxWidth |
| **段卡（思考/工具）** | Surface 圆角 12dp tonal 0dp，header padding 10dp，图标 16dp，标题 `thoughtTitle` Bold primary.copy(alpha=0.7f) |
| **会话项** | Surface height 44dp 圆角 8dp，selected `secondaryContainer`，未选 `Color.Transparent`，padding vertical 2dp |
| **设置项** | Row padding (16, 12/16, 16, 12/16)dp，leading icon 24dp `primary`，headline `bodyLarge` Medium `onSurface`，supporting `bodyMedium` `onSurfaceVariant`，trailing 16dp 后 |
| **设置组** | title `labelLarge` `onSurfaceVariant` padding (16, 8)dp，items 间 0.5dp divider，组间 24dp |
| **FAB** | CircleShape，48dp（发送），40dp（滚动到底部），elevation 0/2dp |
| **BottomSheet** | 顶角 16dp，drag handle 36×5dp 圆角 3dp `onSurfaceVariant.copy(alpha=0.3f)`，标题 `detailTitle` padding (24, 12)dp |

---

## Part B —— 逐页目标设计规格

### B1. 聊天主界面

#### B1.1 顶栏（ChatTopBar）
**目标**：ChatGPT 风格扁平顶栏，单行布局，无装饰背景，胶囊形按钮容器。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| 顶栏高度 | `defaultMinSize(minHeight = 110.dp)`（含状态栏渐变区） | `ChatTopBar.kt:113` | 110dp | 保留 |
| 顶栏背景 | `Brush.verticalGradient(0→background.alpha(0.98), 0.6→background.alpha(0.80), 1→Transparent)` | `ChatTopBar.kt:114-120` | 渐变 | 保留（滚动渐隐效果） |
| 内容区 | `statusBarsPadding().padding(12, 8, 12, 8).height(52.dp)` | `ChatTopBar.kt:165-167` | 52dp | 保留 |
| 标题胶囊 | `ChatTopBarCapsule` `RoundedCornerShape(16.dp)` `Color.Transparent` tonal 0dp shadow 0dp | `ChatTopBar.kt:416-425` | 16dp 透明 | 保留 |
| 菜单/返回按钮 | `IconButton.size(44.dp)` + `Icon.size(24.dp)` | `ChatTopBar.kt:276-291, 336-350` | 44dp/24dp | 保留 |
| 品牌标题 | `ChatType.brandTitle`（20sp Bold） | `ChatTopBar.kt:296` | brandTitle | 保留 |
| 会话标题 | `ChatType.conversationTitle`（带 token）/ `conversationTitleSolo`（无 token） | `ChatTopBar.kt:307` | — | 保留 |
| token 副标题 | `ChatType.micro` `onSurfaceVariant.copy(alpha=0.7f)` | `ChatTopBar.kt:313-316` | — | 保留 |
| MoreVert 菜单 | `DropdownMenu` 圆角 12dp `surfaceContainer` tonal 6dp | `ChatTopBar.kt:352-357` | 12dp/6dp | 保留 |
| 搜索态 | `BasicTextField` `bodyLarge` `onSurface`，返回 44dp，上/下箭头 38dp（**须改 44dp 触点**） | `ChatTopBar.kt:232-252` | 38dp | **改**：箭头 IconButton 38→44dp |
| ShareSelectionTopBar | `Surface` `surface` tonal 0dp，`height(56.dp)`，返回 IconButton，标题 `titleMedium`，全选 `TextButton` | `ChatTopBar.kt:434-465` | 56dp | 保留 |

**涉及文件**：`ui/chat/ChatTopBar.kt`（466 行）
**改动点**：搜索态上/下箭头 IconButton 38dp→44dp（L235, L246），满足最小触点。

#### B1.2 消息列表（MessageList）
**目标**：ChatGPT 风格垂直列表，消息行 padding vertical 6dp，contentPadding 8dp。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| contentPadding | `PaddingValues(8.dp)` | `MessageList.kt:98` | 8dp | 保留 |
| 消息行 | `Row.fillMaxWidth().padding(vertical = 6.dp)` | `MessageItem.kt:188` | 6dp | 保留 |
| 对齐 | USER→End / MODEL→Start / ERROR→CenterHorizontally | `MessageItem.kt:131-135` | — | 保留 |
| 背景 | USER→`primaryContainer` / MODEL→`Transparent` / ERROR→`errorContainer` | `MessageItem.kt:137-141` | — | 保留 |
| 文字色 | USER→`onPrimaryContainer` / MODEL→`onSurface` / ERROR→`onErrorContainer` | `MessageItem.kt:143-147` | — | 保留 |
| 气泡圆角 | `RoundedCornerShape(12.dp)`（三类统一） | `MessageItem.kt:149-153` | 12dp | 保留 |
| 长按多选 | `pointerInput.detectTapGestures(onLongPress)` | `MessageItem.kt:214-216` | — | 保留 |
| Checkbox（多选） | `padding(top = 2, end = 4)` | `MessageItem.kt:205-209` | — | 保留 |

**涉及文件**：`ui/chat/MessageList.kt`（922 行）、`ui/chat/message/MessageItem.kt`（453 行）
**改动点**：无（已对齐）。

#### B1.3 用户消息气泡（UserMessageBubble）
**目标**：右对齐 `primaryContainer` 浅色气泡，12dp 圆角，widthIn max 300dp，无 elevation。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| Surface | `shape = 12.dp`, `color = primaryContainer`, 无 elevation | `UserMessageBubble.kt:83-101` | — | 保留 |
| widthIn | `max = 300.dp` | `UserMessageBubble.kt:87` | 300dp | 保留 |
| 编辑态 | `TextField` 透明容器 + `Row` 取消/保存 | `UserMessageBubble.kt:102-120` | — | 保留 |
| 操作行 | ContentCopy/Edit/MoreVert IconButton，DropdownMenu 12dp tonal 6dp | `UserMessageBubble.kt:283-284` | 12dp/6dp | 保留 |
| PDF 警告 | `error` 语义色（已修复） | `UserMessageBubble.kt:226` | error | 保留 |

**涉及文件**：`ui/chat/message/UserMessageBubble.kt`（305 行）
**改动点**：无。

#### B1.4 AI 消息内容（AssistantMessageContent）
**目标**：左对齐无气泡纯文本，`onSurface` 文字色，操作按钮 32dp 紧凑。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| 容器 | 无 Surface 包裹，直接 Column | `AssistantMessageContent.kt` | — | 保留 |
| 文字色 | `onSurface` | `MessageItem.kt:145` | — | 保留 |
| 终端操作 | Refresh/Fork/Share IconButton 32dp，图标 16-19dp | `AssistantMessageContent.kt:597-633` | 32dp | 保留 |
| MoreVert 菜单 | `DropdownMenu` 12dp `surfaceContainer` tonal 6dp | `AssistantMessageContent.kt:651-654` | 12dp/6dp | 保留 |
| Branch selector | `RoundedCornerShape(100)` 胶囊 | `AssistantMessageContent.kt:698` | 100 | **改**：`CircleShape`（语义等价但清晰） |
| TTS 喇叭 | IconButton + VolumeUp/Pause | `AssistantMessageContent.kt` | — | 保留 |

**涉及文件**：`ui/chat/message/AssistantMessageContent.kt`（744 行）
**改动点**：L698 `RoundedCornerShape(100)` → `CircleShape`。

#### B1.5 段卡/时间线（MessageItemTimeline）
**目标**：扁平段卡，12dp 圆角，tonal 0dp，header 10dp padding，16dp 图标。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| 段卡 Surface | `RoundedCornerShape(12.dp)` tonal 0dp | `MessageItemTimeline.kt:347-349` | 12dp/0dp | 保留 |
| 卡片 padding | `top = 8 + topExtra, bottom = mergedBottom + bottomExtra` | `MessageItemTimeline.kt:352` | — | 保留 |
| Header Row | `clip(12.dp).clickable.padding(10.dp)` | `MessageItemTimeline.kt:358-371` | 10dp | 保留 |
| 折叠图标 | `Crossfade.size(16.dp)` tint `primary.copy(alpha=0.7f)` | `MessageItemTimeline.kt:373-403` | 16dp | 保留 |
| 标题 | `thoughtTitle` Bold `primary.copy(alpha=0.7f)` | `MessageItemTimeline.kt:415-422` | — | 保留 |
| 展开箭头 | `size(16.dp)` `onSurfaceVariant.copy(alpha=0.5f)` | `MessageItemTimeline.kt:424-434` | 16dp | 保留 |
| 第二处段卡 | 同上 12dp tonal 0dp | `MessageItemTimeline.kt:733` | — | 保留 |

**涉及文件**：`ui/chat/message/MessageItemTimeline.kt`（845 行）
**改动点**：无。

#### B1.6 段详情底部弹窗（SegmentDetailSheet）
**目标**：BottomSheet 风格，顶角 16dp，drag handle 36×5dp，标题 `detailTitle`。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| Surface | `RoundedCornerShape(topStart=16, topEnd=16)` shadow 0dp `surfaceContainer` | `SegmentDetailSheet.kt:320-324` | 16dp/0dp | 保留 |
| Drag handle | `width(36).height(5).clip(3.dp).background(onSurfaceVariant.alpha(0.3))` | `SegmentDetailSheet.kt:356-365` | 36×5/3dp | 保留 |
| Handle 容器 | `height(28.dp)` | `SegmentDetailSheet.kt:357` | 28dp | 保留 |
| 标题 | `ChatType.detailTitle` padding (24, 12) | `SegmentDetailSheet.kt:369-375` | — | 保留 |
| Divider | `outlineVariant.copy(alpha=0.3f)` padding horizontal 24 | `SegmentDetailSheet.kt:377-379` | — | 保留 |

**涉及文件**：`ui/chat/message/SegmentDetailSheet.kt`（570 行）
**改动点**：无。

#### B1.7 侧边栏（ChatDrawerContent）
**目标**：ChatGPT 风格简洁列表，矩形会话项 8dp 圆角，selected `secondaryContainer`，Drawer 圆角 12dp。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| ModalDrawerSheet | `drawerShape = RoundedCornerShape(topEnd=12, bottomEnd=12)` `surface` tonal 0dp | `ChatDrawerContent.kt:140-143` | 12dp/0dp | 保留 |
| Drawer padding | `horizontal 16, vertical 20` | `ChatDrawerContent.kt:190` | 16/20 | 保留 |
| "Conversations" 标题 | `ChatType.conversationsTitle`（16sp Bold） | `ChatDrawerContent.kt:193` | — | 保留 |
| 标题后 Spacer | `height(12.dp)` | `ChatDrawerContent.kt:194` | 12dp | 保留 |
| DrawerSearchBar | 见 B5 | `ChatDrawerContent.kt:198` | — | 保留 |
| 搜索后 Spacer | `height(12.dp)` | `ChatDrawerContent.kt:199` | 12dp | 保留 |
| Tasks 按钮 | `FilledTonalButton.fillMaxWidth().height(42.dp).RoundedCornerShape(12.dp)`，图标 20dp | `ChatDrawerContent.kt:202-214` | 42dp/12dp | 保留 |
| Tasks 后 Spacer | `height(10.dp)` | `ChatDrawerContent.kt:216` | 10dp | **改**：10→12dp（收敛到阶梯） |
| NewChat 按钮 | `Button.fillMaxWidth().height(42.dp).RoundedCornerShape(12.dp)`，图标 20dp，禁用态 `onSurface.alpha(0.12/0.38)` | `ChatDrawerContent.kt:229-250` | 42dp/12dp | 保留 |
| NewChat 后 Spacer | `height(16.dp)` | `ChatDrawerContent.kt:252` | 16dp | 保留 |
| 会话项 Surface | `fillMaxWidth().height(44.dp).padding(vertical=2).clip(8.dp)`，selected `secondaryContainer` / 未选 `Transparent` | `ChatDrawerContent.kt:296-331` | 44dp/8dp | 保留 |
| 会话项指示器 | `CircleShape`（生成中/未读点） | `ChatDrawerContent.kt:380` | CircleShape | 保留 |
| 会话项菜单 | `DropdownMenu` tonal 6dp | `ChatDrawerContent.kt:393` | 6dp | 保留 |
| Settings 按钮 | 见 ChatApp（底部固定） | — | — | — |

**涉及文件**：`ui/chat/ChatDrawerContent.kt`（~450 行）
**改动点**：L216 Spacer 10→12dp（收敛间距阶梯）。

#### B1.8 发送区（ChatBottomBar + ComposerSendButton）
**目标**：ChatGPT 风格单排紧凑输入栏，圆角 12dp 容器，48dp 圆形发送按钮。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| 外层 Box | `padding(4, 6, 4, 10)` | `ChatBottomBar.kt:238` | 4/6/4/10 | 保留 |
| composer 容器 | `clip(topStart=12, topEnd=12).background(surfaceContainer)` | `ChatBottomBar.kt:174-177, 258-260` | 12dp | 保留 |
| AttachmentPreviewRow | 顶部显示选中附件 | `ChatBottomBar.kt:264-271` | — | 保留 |
| TextField | `fillMaxWidth`，placeholder `ChatType.input` `onSurfaceVariant.alpha(0.6)`，contentPadding (16, 12, 16, 16)，透明容器/指示器，cursor `primary`，`lineLimits MultiLine(1, 6)` | `ChatBottomBar.kt:273-303` | — | 保留 |
| 工具行 Row | `fillMaxWidth.padding(4, 2)` `CenterVertically` | `ChatBottomBar.kt:304-310` | 4/2 | 保留 |
| Add 按钮 | `IconButton.size(28.dp)` 图标 16dp `onSurfaceVariant` | `ChatBottomBar.kt:317-329` | 28/16dp | 保留 |
| Add 菜单 | `ExposedDropdownMenu` `surfaceContainer` 圆角 12dp，MenuItem 图标 18dp | `ChatBottomBar.kt:331-420` | 12dp/18dp | 保留 |
| 模型 FilterChip | `menuAnchor`，label `maxLines=1 Ellipsis` | `ChatBottomBar.kt:439-451` | — | 保留 |
| 模型菜单 | `surfaceContainer` `shapes.medium`，搜索框 12dp，MenuItem label | `ChatBottomBar.kt:452-514` | — | 保留 |
| Context 按钮 | `IconButton.size(28.dp)` `CircularProgressIndicator.size(20) strokeWidth 2.5` | `ChatBottomBar.kt:521-549` | 28/20dp | 保留 |
| Context 菜单 | `surfaceContainer` 12dp，标题 `titleSmall`，进度环 36dp strokeWidth 4 | `ChatBottomBar.kt:550-598` | 12dp | 保留 |
| Tools 按钮 | `IconButton.size(28.dp)` MoreVert 16dp `onSurfaceVariant` | `ChatBottomBar.kt:605-616` | 28/16dp | 保留 |
| Tools 菜单 | `surfaceContainer` 12dp，Select Model 入口 + Divider + 工具开关 | `ChatBottomBar.kt:619-648` | 12dp | 保留 |
| 麦克风（独立） | Mic/Stop IconButton 紧贴发送 | `ChatBottomBar.kt`（下排） | — | 保留（用户 a6b48e45 改动） |
| 发送按钮 | `ComposerSendButton` 48dp `CircleShape` FAB | `ComposerSendButton.kt:181-188` | 48dp | 保留 |
| 发送按钮颜色 | IDLE→`surfaceVariant`/`onSurfaceVariant`，SEND→`primary`/`onPrimary`，STOP→`primary`/`onPrimary` | `ComposerSendButton.kt:131-148` | — | 保留 |
| 发送按钮 elevation | `defaultElevation = if (canSend) 2.dp else 0.dp` | `ComposerSendButton.kt:183-188` | 0/2dp | 保留 |
| 发送按钮图标 | 24dp，Crossfade：STOPPING/PENDING→`CircularProgressIndicator`，STOP→Stop，SEND→ArrowUpward，IDLE→GraphicEq | `ComposerSendButton.kt:190-225` | 24dp | 保留 |

**涉及文件**：`ui/chat/bottombar/ChatBottomBar.kt`（990 行）、`ComposerSendButton.kt`（227 行）、`ChatBottomBarComponents.kt`、`ChatComposerState.kt`、`AttachmentPreviewRow.kt`、`ComposerStatusColumn.kt`、`LoopControlBar.kt`、`QueuedMessagesBanner.kt`、`InternalCameraCaptureDialog.kt`
**改动点**：无（用户 a6b48e45 已重排为单排，保留）。

#### B1.9 语音对话覆盖（VoiceConversationOverlay）
**目标**：全屏深色覆盖，中央 200dp 圆圈 + 160dp orb，顶部退出按钮。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| 容器 | `fillMaxSize.padding(horizontal=24)` `Center` | `VoiceConversationOverlay.kt:121-125` | 24dp | 保留 |
| 退出按钮 | `IconButton.align(TopEnd).padding(top=16).size(44.dp)`，Icon 24dp `onSurface` | `VoiceConversationOverlay.kt:127-140` | 44/24dp | 保留 |
| 圆圈容器 | `size(200.dp)` `Center` | `VoiceConversationOverlay.kt:146-148` | 200dp | 保留 |
| 脉冲环 | `size(200).scale(ringScale).alpha(ringAlpha)` `CircleShape` `stateColor` | `VoiceConversationOverlay.kt:150-163` | — | 保留 |
| Orb | `size(160).scale(orbScale)` `CircleShape` `stateColor` tonal 6dp | `VoiceConversationOverlay.kt:165-172` | 160dp/6dp | 保留 |
| Orb 内容 | LISTENING/TRANSCRIBING→`VoiceWaveformIndicator` scale 2.2 white，其他→Icon 56dp white | `VoiceConversationOverlay.kt:177-191` | — | 保留 |
| 状态文字 | `titleMedium` `stateColor` `Medium` | `VoiceConversationOverlay.kt:196-200` | — | 保留 |
| partial transcript | 显示在状态文字下 | `VoiceConversationOverlay.kt:201+` | — | 保留 |
| 状态色 | LISTENING→`error`，TRANSCRIBING/PROCESSING→`tertiary`，SPEAKING→`secondary` | `VoiceConversationOverlay.kt:71-77` | — | 保留 |

**涉及文件**：`ui/chat/VoiceConversationOverlay.kt`（218 行）、`VoiceConversationStatusOverlay.kt`、`VoiceWaveformIndicator.kt`、`VoiceGradientBackground.kt`
**改动点**：`VoiceGradientBackground.kt:20-21` 硬编码色 `0xFFFF4FD8`/`0xFF3D8BFF` → `MaterialTheme.colorScheme.primary`/`tertiary`（语义化）。

#### B1.10 滚动到底部按钮（ChatAppScrollToBottomFab）
**目标**：40dp 圆形 FAB，悬浮于底部栏上方 8dp。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| 容器 | `size(48.dp)` `Center` | `ChatAppOverlays.kt:73` | 48dp | 保留 |
| FAB | `size(40.dp)` `CircleShape` `surfaceColorAtElevation(4.dp)` `onSurface` elevation 0/4dp | `ChatAppOverlays.kt:74` | 40dp | **改**：`surfaceColorAtElevation(4.dp)` → `surfaceContainer`（语义色替换 elevation 合成色） |
| 图标 | `KeyboardArrowDown` 24dp | `ChatAppOverlays.kt:75` | 24dp | 保留 |
| 位置 | `padding(bottom = bottomBarHeight + 8.dp)` | `ChatAppOverlays.kt:71` | +8dp | 保留 |

**涉及文件**：`ui/chat/ChatAppOverlays.kt`（196 行）
**改动点**：L74 `surfaceColorAtElevation(4.dp)` → `surfaceContainer`。

#### B1.11 多选/分享面板（ShareSelectionFab）
**目标**：底部悬浮操作条，52dp 高，16dp 圆角，tonal 2dp shadow 2dp。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| Surface | `height(52.dp)` `RoundedCornerShape(16)` `surface` `onSurface` tonal 2dp shadow 2dp | `ShareSelectionFab.kt:40-47` | 52dp/16/2dp | 保留 |
| 按钮行 | `Row` + Spacer 4dp + IconButton×5 + Spacer 4dp | `ShareSelectionFab.kt:48-84` | — | 保留 |
| 按钮 | Close/ContentCopy/Description/Image/SaveAlt/Check IconButton | `ShareSelectionFab.kt:50-82` | — | 保留 |

**涉及文件**：`ui/chat/ShareSelectionFab.kt`（86 行）
**改动点**：无。

#### B1.12 聊天弹窗（ChatDialogs / AdvancedSettingsDialog / VideoSliceDialog / PdfPageSelectDialog / ChatManualCompactDialog）
**目标**：统一 AlertDialog `surfaceContainer` 容器，标题 Bold，破坏性确认 `error` 色。

| 元素 | 目标规格 | 涉及文件 | 改动 |
|---|---|---|---|
| AlertDialog | `containerColor = surfaceContainer`，标题 `FontWeight.Bold`，确认 `error` 色用于删除 | `MessageDialogs.kt:56, 104`、`ChatDialogs.kt`、`ChatManualCompactDialog.kt`、`AdvancedSettingsDialog.kt` | 保留 |
| VideoSliceDialog | `tonalElevation = 3.dp`，按钮 `RoundedCornerShape(50)` | `VideoSliceDialog.kt:118, 159, 217, 223, 239` | **改**：`RoundedCornerShape(50)` → `CircleShape`（L159/217/223/239） |
| PdfPageSelectDialog | `tonalElevation = 3.dp`，按钮 `RoundedCornerShape(50)` | `PdfPageSelectDialog.kt:52, 201, 207` | **改**：`RoundedCornerShape(50)` → `CircleShape`（L201/207） |
| FullScreenMediaViewer | 按钮 `RoundedCornerShape(50)` + `shadow(8.dp)` `CircleShape` | `FullScreenMediaViewer.kt:133, 205, 219, 240, 332, 347, 386` | **改**：`RoundedCornerShape(50)` → `CircleShape`（L205/207/240/332/334） |
| TextFileViewer | 按钮 `RoundedCornerShape(50)` + `shadow(8.dp)` | `TextFileViewer.kt:171, 173, 189` | **改**：`RoundedCornerShape(50)` → `CircleShape`（L171/173） |
| InternalCameraCaptureDialog | `CircleShape` 已用 | `InternalCameraCaptureDialog.kt:165, 188, 192` | 保留 |

**涉及文件**：`ui/chat/message/MessageDialogs.kt`（118 行）、`ui/chat/ChatDialogs.kt`、`ChatManualCompactDialog.kt`、`AdvancedSettingsDialog.kt`、`VideoSliceDialog.kt`、`PdfPageSelectDialog.kt`、`FullScreenMediaViewer.kt`、`TextFileViewer.kt`、`bottombar/InternalCameraCaptureDialog.kt`
**改动点**：`RoundedCornerShape(50)` → `CircleShape` 共约 10 处。

#### B1.13 其他聊天组件
| 文件 | 目标 | 改动 |
|---|---|---|
| `ChatApp.kt`（757 行） | 主编排，无直接视觉 | 无 |
| `ChatAppBottomBarSection.kt` | 底部栏区 Surface tonal 0dp shadow 0dp | 保留 |
| `ChatAppDialogHost.kt` | Dialog 宿主 | 无 |
| `ChatAppInteractionEffects.kt` | 滚动按钮可见性计算 | 无 |
| `ChatComposerDraftEffect.kt` | 草稿持久化 | 无 |
| `ChatScrollCoordinator.kt` / `ChatScrollTargetResolver.kt` / `RobustLazyListScroll.kt` / `AbsoluteBottomScroll.kt` | 滚动协调 | 无 |
| `ConversationInteractionState.kt` / `ConversationSearch.kt` | 交互状态 | 无 |
| `MessageListLayoutModel.kt` | 布局模型 | 无 |
| `StreamingTailIndicator.kt` | 流式尾部 `CircleShape` | 保留 |
| `ZoomableImageItem.kt` | 可缩放图片 | 无 |
| `AttachmentThumbnail.kt` | 附件缩略图，PDF 背景 `error.copy(alpha=0.15)` | 保留 |
| `ImageActions.kt` | 图片操作 | 无 |
| `VideoPlayer.kt` | 视频播放器 `CircleShape` 按钮 | 保留 |
| `TextFileViewer.kt` | 文本查看器 | `RoundedCornerShape(50)` → `CircleShape` |
| `MessageItemSegments.kt` / `MessageItemMarkdown.kt` / `MessageItemJson.kt` / `MessageItemToolLabels.kt` | 消息子组件 | 无 |
| `MessageBubbleAssets.kt` | Markdown 资源 | 无 |
| `ToolResultContent.kt` / `ToolPresentation.kt` | 工具结果块 `surfaceContainerHighest` `CircleShape` | 保留 |
| `SearchHighlighting.kt` | 搜索高亮硬编码色 | **改**：加 KDoc 注释或改 `tertiaryContainer`/`tertiary`/`onTertiaryContainer` |
| `LiteralHtmlMarkdown.kt` / `IncrementalStreamingMarkdown.kt` / `StreamingJsonDocument.kt` / `StreamingToolArgumentHints.kt` | Markdown/JSON 渲染 | 无 |
| `GenerationLifecycleMotion.kt` | 生成生命周期动画 | 无 |
| `ContextCompactPill`（MessageItem.kt:367） | `RoundedCornerShape(100.dp)` 胶囊 `secondaryContainer` | **改**：`RoundedCornerShape(100.dp)` → `CircleShape`（L373） |

**涉及文件**：见上表
**改动点**：`ContextCompactPill` L373 `RoundedCornerShape(100.dp)` → `CircleShape`；`SearchHighlighting.kt` 高亮色语义化或加注释。

---

### B2. 设置页

#### B2.1 SettingsScaffold（顶栏/脚手架）
**目标**：ChatGPT 风格扁平顶栏，返回箭头+居中标题，64dp 高，无装饰。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| SettingsBarHeight | 64dp | `SettingsScaffold.kt:47` | 64dp | 保留 |
| TopBar 容器 | `fillMaxWidth.height(statusBarTop + 64).background(background)` | `SettingsScaffold.kt:61-66` | — | 保留 |
| 返回按钮 | `IconButton.padding(start=4, top=statusBarTop).size(64)` | `SettingsScaffold.kt:67-77` | 64dp | 保留 |
| 标题 | `titleMedium` `SemiBold` `onBackground` `align(Center)` `maxLines=1 Ellipsis` | `SettingsScaffold.kt:78-86` | — | 保留 |
| actions | `Row.align(TopEnd).padding(end=4, top=statusBarTop)` | `SettingsScaffold.kt:87-94` | — | 保留 |
| 内容区 | `Column.fillMaxSize.navigationBarsPadding.imePadding.verticalScroll.clearFocusOnTap.padding(horizontal=16)` | `SettingsScaffold.kt:120-128` | 16dp | 保留 |
| 顶栏 Spacer | `height(statusBarTop + 64)` | `SettingsScaffold.kt:129` | — | 保留 |
| 底部 Spacer | `height(32.dp)` | `SettingsScaffold.kt:131, 185` | 32dp | 保留 |
| FAB 容器 | `align(BottomCenter).fillMaxWidth.navigationBarsPadding.padding(bottom=16)` `Center` | `SettingsScaffold.kt:139-148` | 16dp | 保留 |
| LazyScaffold | 同上但 `LazyColumn` + `contentPadding(top=statusBarTop+64)` | `SettingsScaffold.kt:174-186` | — | 保留 |

**涉及文件**：`ui/settings/SettingsScaffold.kt`（204 行）
**改动点**：无。

#### B2.2 根页（SettingsScreen）
**目标**：ChatGPT 风格分组列表，扁平行+divider，组标题 `labelLarge` `onSurfaceVariant`。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| SettingsGroupColumn | `Arrangement.spacedBy(24.dp)` | `SettingsScreen.kt:50-62` | 24dp | 保留 |
| SettingsGroup | title `labelLarge` `onSurfaceVariant` `padding(16, 8)`，items 间 0.5dp `outlineVariant` divider `padding(horizontal=16)` | `SettingsScreen.kt:64-92` | — | 保留 |
| SettingsItem | `padding(16, 12/16, 16, 12/16)`，leading 24dp `primary`，headline `bodyLarge` Medium `onSurface`，supporting `bodyMedium` `onSurfaceVariant`，trailing 16dp 后 | `SettingsScreen.kt:115-167` | — | 保留 |
| SettingsAddItem | `heightIn(min=56).clickable.padding(horizontal=16)` `Center`，Add icon 18dp `primary` | `SettingsScreen.kt:175-210` | — | 保留 |
| 根页组 | 7 组（services/responses/multimodal/tools/network/memory-data/appearance-language/about） | `SettingsScreen.kt:225-266` | — | 保留 |
| 根页行 | `Row.clickable.padding(16, 12).fillMaxWidth`，icon 24dp `primary`，title `bodyLarge` Medium，desc `bodyMedium` `onSurfaceVariant`，箭头 `KeyboardArrowRight` `onSurfaceVariant.alpha(0.5)` | `SettingsScreen.kt:336-376` | — | 保留 |
| 行间 divider | 0.5dp `outlineVariant` `padding(horizontal=16)` | `SettingsScreen.kt:377-383` | — | 保留 |
| 组间 Spacer | 24dp | `SettingsScreen.kt:386-388` | 24dp | 保留 |

**涉及文件**：`ui/settings/SettingsScreen.kt`（396 行）
**改动点**：无。

#### B2.3 各子页（通用规格）
**目标**：所有子页用 `CollapsingSettingsScaffold`/`CollapsingSettingsLazyScaffold`，输入框 12dp 圆角，DropdownMenu 12dp tonal 6dp，AlertDialog `surfaceContainer`。

| 元素 | 目标规格 | 适用文件 | 改动 |
|---|---|---|---|
| OutlinedTextField | `RoundedCornerShape(12.dp)` | 所有子页 | 保留（已统一） |
| DropdownMenu | `surfaceContainer` tonal 6dp `RoundedCornerShape(12.dp)` | 所有子页 | 保留 |
| AlertDialog | `containerColor = surfaceContainer`，标题 Bold，破坏性 `error` | 所有子页 | 保留 |
| Surface 卡片 | `RoundedCornerShape(12.dp)` tonal 0dp | 所有子页 | 保留 |
| HorizontalDivider | 0.5dp `outlineVariant` | 组内 | 保留 |
| Switch | Material 3 默认 | 所有子页 | 保留 |
| Slider | Material 3 默认 | VAD 参数等 | 保留 |
| DocumentationFab | `CircleShape` FAB | 各页底部 | 保留 |

**涉及文件**（34 个）：
- `SettingsGenerationPage.kt`（生成设置：TTS/ASR/VAD/导出）
- `SettingsShellPage.kt`（Shell 配置：输入框 12dp，`Surface(4.dp)` 协议标签 L274，`Surface(8.dp)` 终端 L462）
- `SettingsModelsPage.kt`（模型列表：`FullRounded=24.dp`/`MidRounded=5.dp`/`FlatShape=0.dp` 常量 L51-55 **须清理若未用**，搜索框 12dp，Dialog 12dp）
- `SettingsProviderDetailPage.kt`（Provider 详情：输入框 12dp ×18，DropdownMenu 12dp ×3，AlertDialog `surfaceContainer` ×4）
- `SettingsMemoryPage.kt`（记忆管理：Dialog 输入框 12dp ×6）
- `SettingsSearchPage.kt`（搜索设置：Dialog 12dp ×4，DropdownMenu 12dp tonal 6dp ×3）
- `SettingsSandboxPage.kt`（沙盒：`Surface(8.dp)` L212/505，`Surface(3.dp)` L613，堆叠 12/4，终端卡 tonal 0dp）
- `SettingsAboutPage.kt`（关于：Rating 卡 12dp tonal 0dp）
- `SettingsAppearancePage.kt`（外观：主题/字体/动效 DropdownMenu 12dp tonal 6dp ×6，色板 `CircleShape` 预览）
- `SettingsLanguagePage.kt`（语言）
- `SettingsPromptsPage.kt`（提示词：ModalBottomSheet 顶角 16dp，DropdownMenu 12dp）
- `SettingsProviderPage.kt`（Provider 列表：`Surface(4.dp)` 协议标签 L142，输入框 12dp ×2）
- `SettingsWebSearchPage.kt`（Web 搜索：输入框 12dp ×2）
- `SettingsTranscriptionPage.kt`（转录：DropdownMenu 12dp tonal 6dp）
- `SettingsTitleGenPage.kt`（标题生成）
- `SettingsImageGenPage.kt`（图像生成：宽高输入框 12dp ×2）
- `SettingsMcpPage.kt`（MCP：DropdownMenu 12dp，`CircleShape` 状态点 L590）
- `SettingsAutomationPage.kt`（自动化）
- `SettingsContextPage.kt`（上下文）
- `SettingsProxyPage.kt`（代理：输入框 12dp）
- `SettingsAnimations.kt`（动画）
- `SystemPromptEditorPage.kt`（系统提示编辑：标题/预览/模板卡 12dp tonal 0dp，ModalBottomSheet 顶角 16dp tonal 6dp）
- `EmbeddingDialogs.kt`（嵌入 Dialog ×7：12dp）
- `RatingForm.kt`（评分表单：`RoundedCornerShape(50)` L197 **须改 `CircleShape`**，输入框 12dp ×3）
- `PromptSettingControls.kt`（提示控制：Dialog 12dp）
- `PillTabSwitcher.kt`（标签切换：`surfaceContainerHigh` 选中态）
- `DocumentationFab.kt`（文档 FAB `CircleShape`）
- `AnimatedActionFab.kt`（动画 FAB `RoundedCornerShape(50)` L82 **须改 `CircleShape`**）
- `SettingsModelsPresentation.kt`（模型展示：CardSurface tonal 0dp）
- `SettingsVoskModelsSection.kt`（Vosk 模型）
- `datacontrol/SettingsDataControlPage.kt`（数据控制：`RoundedCornerShape(50)` L814 **须改 `CircleShape`**）
- `datacontrol/SettingsAutoBackupSection.kt`（自动备份：DropdownMenu 12dp ×2）

**改动点**：
- `SettingsModelsPage.kt:51-55` 清理 `FullRounded=24.dp`/`MidRounded=5.dp`/`FlatShape=0.dp` 常量（若未使用）。
- `RatingForm.kt:197` `RoundedCornerShape(50)` → `CircleShape`。
- `AnimatedActionFab.kt:82` `RoundedCornerShape(50)` → `CircleShape`。
- `datacontrol/SettingsDataControlPage.kt:814` `RoundedCornerShape(50)` → `CircleShape`。

---

### B3. 发送区（ui/chat/bottombar/）

已在 **B1.8** 覆盖。补充：

| 文件 | 目标 | 改动 |
|---|---|---|
| `ChatBottomBarComponents.kt` | ProviderBadge 用 `ProviderPalette` 品牌色 | 保留 |
| `ChatComposerState.kt` | 状态管理，无视觉 | 无 |
| `AttachmentPreviewRow.kt` | 附件预览行，删除按钮 `CircleShape` 黑底 | 保留 |
| `ComposerStatusColumn.kt` | 排队消息横幅 | 无 |
| `LoopControlBar.kt` | 循环控制栏 | 无 |
| `QueuedMessagesBanner.kt` | 排队消息 banner | 无 |
| `InternalCameraCaptureDialog.kt` | 相机捕获 `CircleShape` | 保留 |

**涉及文件**：9 个
**改动点**：无。

---

### B4. 弹窗/Dialog/BottomSheet

已在 **B1.12** 覆盖聊天弹窗。补充设置/通用弹窗：

| 文件 | 目标 | 改动 |
|---|---|---|
| `ui/motion/MotionAwareModalBottomSheet.kt` | BottomSheet 包装，顶角 16dp | 保留 |
| `ui/components/DialogWindowEdgeToEdge.kt` | Dialog 边到边 | 无 |
| `ui/settings/EmbeddingDialogs.kt` | 嵌入 Dialog 12dp | 保留 |
| `ui/settings/RatingForm.kt` | 评分表单 | `RoundedCornerShape(50)` → `CircleShape` |
| `ui/settings/AnimatedActionFab.kt` | FAB | `RoundedCornerShape(50)` → `CircleShape` |
| `ui/settings/datacontrol/SettingsDataControlPage.kt` | 数据控制 | `RoundedCornerShape(50)` → `CircleShape` |
| `ui/common/ThinkingControlPanel.kt` | 思考控制面板（ModalBottomSheet） | 保留 |
| `ui/common/OpenAiServiceTierControlPanel.kt` | OpenAI 服务层面板 | 保留 |

**统一规格**：
- `AlertDialog`：`containerColor = surfaceContainer`，标题 `FontWeight.Bold`，破坏性确认 `ButtonDefaults.textButtonColors(contentColor = error)`。
- `ModalBottomSheet`：顶角 16dp，drag handle 36×5dp 圆角 3dp `onSurfaceVariant.alpha(0.3)`。
- `DropdownMenu`：`surfaceContainer` tonal 6dp 圆角 12dp。

---

### B5. 搜索栏（DrawerSearchBar）

**目标**：44dp 高，12dp 圆角，`surfaceContainerLow` 背景，tonal 0dp。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| Surface | `fillMaxWidth.height(44.dp).RoundedCornerShape(12.dp).surfaceContainerLow` tonal 0dp | `DrawerSearchBar.kt:37` | 44dp/12dp | 保留 |
| Row | `fillMaxSize.padding(horizontal=14).CenterVertically` | `DrawerSearchBar.kt:38` | 14dp | 保留 |
| 搜索图标 | `Search` 20dp `onSurfaceVariant` | `DrawerSearchBar.kt:39` | 20dp | 保留 |
| Spacer | `width(10.dp)` | `DrawerSearchBar.kt:40` | 10dp | **改**：10→8dp（收敛阶梯） |
| BasicTextField | `ChatType.drawerSearch` `onSurface`，placeholder `onSurfaceVariant.alpha(0.6)` | `DrawerSearchBar.kt:42-43` | — | 保留 |
| 清除按钮 | `IconButton.size(28.dp)` Close 18dp（query 非空时显示） | `DrawerSearchBar.kt:45` | 28/18dp | 保留 |

**涉及文件**：`ui/chat/search/DrawerSearchBar.kt`（48 行）、`DrawerSearchState.kt`、`ChatSearchResultItem.kt`
**改动点**：L40 Spacer 10→8dp。

---

### B6. 任务页（ui/tasks/）

#### B6.1 TasksScreen
**目标**：ChatGPT 风格任务列表，stacked 卡 12/4 圆角，tonal 0dp。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| 脚手架 | `CollapsingSettingsLazyScaffold` | `TasksScreen.kt:177-181` | — | 保留 |
| 空态卡 | `Surface.stackedShape(0,2).surface` tonal 0dp，`SettingsItem` `heightIn(min=64)` | `TasksScreen.kt:185-213` | — | 保留 |
| TaskCard | `Surface.onClick.stackedShape.surface` tonal 0dp，`padding(18, 14, 6, 14)` | `TasksScreen.kt:289-297` | — | 保留 |
| TaskCard 标题 | `titleMedium` `SemiBold` `onSurface` `maxLines=1 Ellipsis` | `TasksScreen.kt:301-308` | — | 保留 |
| TaskCard prompt | `bodyMedium` `onSurfaceVariant` `maxLines=2` | `TasksScreen.kt:311-317` | — | 保留 |
| schedule 文字 | `labelMedium` `onSurfaceVariant` | `TasksScreen.kt:333-339` | — | 保留 |
| last-run 文字 | `labelMedium` `primary`（running）/ `onSurfaceVariant` | `TasksScreen.kt:341-352` | — | 保留 |
| Switch | Material 3 默认 | `TasksScreen.kt:362-366` | — | 保留 |
| MoreVert 菜单 | `DropdownMenu` 12dp | `TasksScreen.kt:375` | 12dp | 保留 |
| stackedShape | 首 12/topEnd 12，尾 12/bottomEnd 12，中 4，单 12 | `TasksScreen.kt:434-437` | 12/4 | 保留 |
| 删除确认 | `AlertDialog` `surfaceContainer` 标题 Bold 确认 `error` | `TasksScreen.kt:244-261` | — | 保留 |
| NewAutomationRow | `stackedShape` `surface` tonal 0dp | `TasksScreen.kt:233-237` | — | 保留 |

**涉及文件**：`ui/tasks/TasksScreen.kt`（453 行）
**改动点**：无。

#### B6.2 TaskEditorPage / TaskEditorSupportingComponents
| 元素 | 目标 | 涉及文件:行号 | 改动 |
|---|---|---|---|
| 脚手架 | `CollapsingSettingsScaffold` | `TaskEditorPage.kt` | 保留 |
| 输入框 | 12dp | `TaskEditorPage.kt:276, 369, 518` | 保留 |
| 空态卡 | 12dp tonal 0dp | `TaskEditorPage.kt:278` | 保留 |
| 执行卡 | 28dp tonal 0dp | `TaskEditorSupportingComponents.kt:98, 296, 379` | **改**：28→16dp（收敛圆角体系，>16 仅 BottomSheet 顶角允许） |
| DropdownMenu | 16dp | `TaskEditorSupportingComponents.kt:122` | **改**：16→12dp（统一 DropdownMenu 12dp） |
| FAB | `CircleShape` | `TaskEditorSupportingComponents.kt:163` | 保留 |
| 浮层 | tonal 6dp | `TaskEditorSupportingComponents.kt:562` | 保留 |

**涉及文件**：`ui/tasks/TaskEditorPage.kt`、`TaskEditorSupportingComponents.kt`、`TaskHistoryPreviewState.kt`
**改动点**：执行卡 28→16dp ×3（L98/296/379）；DropdownMenu 16→12dp（L122）。

---

### B7. 欢迎页（ui/onboarding/WelcomeScreen）

**目标**：ChatGPT 风格静态图标页 + HorizontalPager，72dp 图标，TypewriterText 标题/描述，12dp 圆角按钮。

| 元素 | 目标规格 | 涉及文件:行号 | 当前 | 改动 |
|---|---|---|---|---|
| Skip 按钮 | `TextButton` `padding(top=48, end=16)` `CenterEnd` | `WelcomeScreen.kt:365-371` | — | 保留 |
| HorizontalPager | `weight(1)` `beyondViewportPageCount=1` | `WelcomeScreen.kt:373` | — | 保留 |
| 页面缩放 | `graphicsLayer` scaleX/Y = 1 - absOffset*0.12, alpha = 1 - absOffset*0.4 | `WelcomeScreen.kt:375-385` | — | 保留 |
| 图标页 | `Icon.size(72.dp).primary` `Center` | `WelcomeScreen.kt:447-456` | 72dp | 保留 |
| Provider/ApiKey/Model/AutoBackup 页 | 配置卡 `padding(horizontal=24/36)` | `WelcomeScreen.kt:396-444` | — | 保留 |
| 标题 | `TypewriterText` `headlineMedium` Bold `onSurface` `TEXT_GRADIENT` | `WelcomeScreen.kt:484-497` | — | 保留 |
| 描述 | `TypewriterText` `bodyLarge` `onSurfaceVariant` | `WelcomeScreen.kt:499-512` | — | 保留 |
| Dot indicator | `CircleShape` 选中 10dp `primary` / 未选 8dp `outlineVariant` | `WelcomeScreen.kt:521-527` | — | 保留 |
| Continue 按钮 | `Button` `padding(horizontal=32, bottom=48).navigationBarsPadding` | `WelcomeScreen.kt:531-539` | — | 保留 |
| 配置卡 Surface | `RoundedCornerShape(12.dp)` `surfaceContainer` tonal 0dp | `WelcomeScreen.kt:576, 626, 726, 782` | 12dp/0dp | 保留 |
| GGUF 错误 Dialog | `AlertDialog` `surfaceContainer` 标题 Bold | `WelcomeScreen.kt:350-356` | — | 保留 |

**涉及文件**：`ui/onboarding/WelcomeScreen.kt`（804 行）
**改动点**：无。

---

### B8. 主题（ui/theme/）

| 文件 | 目标 | 改动 |
|---|---|---|
| `Type.kt` | 双体系保留（Material Typography + ChatType），已对齐 ChatGPT 极简风 | 无 |
| `Theme.kt` | `LxChatTheme` 保留 dynamicColor + 预设色板 + 自定义字体路径 | 无 |
| `Color.kt` | 8 预设色板 + 4 scheme style 保留 | 无 |
| `ProviderPalette.kt` | Anthropic/OpenAI 品牌色保留，加 KDoc 注释「品牌色，硬编码例外」 | 无（已有） |

**涉及文件**：4 个
**改动点**：无。

---

### B9. 通用组件（ui/components/ + ui/common/ + ui/motion/）

| 文件 | 目标 | 改动 |
|---|---|---|
| `ui/components/TypewriterText.kt` | 打字机动画 | 无 |
| `ui/components/ProviderIcons.kt` | Provider 图标 | 无 |
| `ui/components/FocusModifiers.kt` | `clearFocusOnTap` 焦点修饰 | 无 |
| `ui/components/DialogWindowEdgeToEdge.kt` | Dialog 边到边 | 无 |
| `ui/components/CustomEndpointProtocolSelector.kt` | 协议选择器 | 无 |
| `ui/components/AnimatedBlobBackground.kt` | 动态背景 | 无 |
| `ui/common/ThinkingControlPanel.kt` | 思考控制 ModalBottomSheet | 无 |
| `ui/common/OpenAiServiceTierControlPanel.kt` | OpenAI 服务层 ModalBottomSheet | 无 |
| `ui/common/LxChatHaptics.kt` | 触觉反馈 | 无 |
| `ui/motion/MotionAwareModalBottomSheet.kt` | BottomSheet 包装 顶角 16dp | 无 |
| `ui/motion/MotionAwareProgressIndicators.kt` | 动画感知进度 | 无 |
| `ui/motion/MotionAwareMaterialState.kt` | 动画感知状态 | 无 |
| `ui/motion/LxChatMotionPolicy.kt` | 动效策略 | 无 |

**涉及文件**：13 个
**改动点**：无。

---

## Part C —— 实施分批顺序

> **总原则**：每批独立可交付，CI 全绿后才进下一批。每批先改令牌/共享组件，再改页面。所有改动**不动** Composable 签名/业务逻辑/事件处理。

### 批次 1（P0 — 全局令牌 + 主题 + 硬编码色清理）

**目标**：奠定全局基础，零业务影响。

| 改动 | 文件:行号 | 当前 → 目标 |
|---|---|---|
| 语音渐变背景语义化 | `VoiceGradientBackground.kt:20-21` | `Color(0xFFFF4FD8)`/`Color(0xFF3D8BFF)` → `MaterialTheme.colorScheme.primary`/`tertiary` |
| 搜索高亮色语义化或加注释 | `SearchHighlighting.kt:21-23` | 硬编码黄/橙/棕 → `tertiaryContainer`/`tertiary`/`onTertiaryContainer` 或加 KDoc 注释 |
| `RoundedCornerShape(50)` → `CircleShape` | `RatingForm.kt:197`、`AnimatedActionFab.kt:82`、`datacontrol/SettingsDataControlPage.kt:814` | `RoundedCornerShape(50)` → `CircleShape` |
| `RoundedCornerShape(100.dp)` → `CircleShape` | `MessageItem.kt:373`（ContextCompactPill） | `RoundedCornerShape(100.dp)` → `CircleShape` |
| `surfaceColorAtElevation(4.dp)` → `surfaceContainer` | `ChatAppOverlays.kt:74` | `surfaceColorAtElevation(4.dp)` → `surfaceContainer` |
| 清理未用常量 | `SettingsModelsPage.kt:51-55` | `FullRounded=24.dp`/`MidRounded=5.dp`/`FlatShape=0.dp` 若未用则删除 |

**预估**：6 文件，~15 行改动。
**风险**：极低（纯常量替换，无逻辑改动）。
**验证**：CI 编译 + 视觉回归（语音覆盖/搜索高亮/ContextCompactPill/FAB）。

### 批次 2（P1 — 聊天主界面收尾）

**目标**：聊天主界面剩余 `RoundedCornerShape(50)` → `CircleShape` + 触点修正 + 间距收敛。

| 改动 | 文件:行号 | 当前 → 目标 |
|---|---|---|
| 搜索态上/下箭头触点 | `ChatTopBar.kt:235, 246` | IconButton 38dp → 44dp |
| Drawer Tasks 后 Spacer | `ChatDrawerContent.kt:216` | 10dp → 12dp |
| DrawerSearchBar Spacer | `DrawerSearchBar.kt:40` | 10dp → 8dp |
| Branch selector 胶囊 | `AssistantMessageContent.kt:698` | `RoundedCornerShape(100)` → `CircleShape` |
| Dialog 按钮圆形 | `VideoSliceDialog.kt:159, 217, 223, 239`、`PdfPageSelectDialog.kt:201, 207`、`FullScreenMediaViewer.kt:205, 207, 240, 332, 334`、`TextFileViewer.kt:171, 173` | `RoundedCornerShape(50)` → `CircleShape` |

**预估**：9 文件，~15 行改动。
**风险**：低（触点增大可能影响搜索态布局，须验证不溢出）。
**验证**：CI 编译 + 搜索态/多选/全屏媒体/Dialog 视觉回归。

### 批次 3（P2 — 任务页圆角收敛）

**目标**：任务页执行卡 28→16dp，DropdownMenu 16→12dp。

| 改动 | 文件:行号 | 当前 → 目标 |
|---|---|---|
| 执行卡圆角 | `TaskEditorSupportingComponents.kt:98, 296, 379` | `RoundedCornerShape(28.dp)` → `RoundedCornerShape(16.dp)` |
| DropdownMenu 圆角 | `TaskEditorSupportingComponents.kt:122` | `RoundedCornerShape(16.dp)` → `RoundedCornerShape(12.dp)` |

**预估**：1 文件，4 行改动。
**风险**：低（纯视觉）。
**验证**：CI 编译 + 任务编辑页视觉回归。

### 批次 4（P3 — 其余验证 + 文档）

**目标**：全量回归验证，确认无遗漏。

| 动作 | 范围 |
|---|---|
| 全量 grep | 确认无残留 `RoundedCornerShape(50)`/`RoundedCornerShape(100.dp)`/`surfaceColorAtElevation`/硬编码色（除 ProviderPalette/SearchHighlighting 注释例外） |
| 全量 grep | 确认无残留 `tonalElevation` 非法值（仅 0/1/2/3/6dp 允许） |
| 全量 grep | 确认无残留 `RoundedCornerShape(18/20/24/26/28.dp)`（仅 16dp BottomSheet 顶角允许） |
| 视觉回归 | 聊天/侧边栏/设置/任务/欢迎/语音/弹窗全量截图对比 |
| 回写 AGENTS.md | §9 变更日志追加本次重设计条目 |

**预估**：0 代码改动，纯验证。
**风险**：无。
**验证**：CI 全绿 + 全量视觉回归。

---

## 附录 —— 涉及文件总清单

### 改动文件（批 1-3）
1. `ui/chat/VoiceGradientBackground.kt`
2. `ui/chat/message/SearchHighlighting.kt`
3. `ui/settings/RatingForm.kt`
4. `ui/settings/AnimatedActionFab.kt`
5. `ui/settings/datacontrol/SettingsDataControlPage.kt`
6. `ui/chat/message/MessageItem.kt`
7. `ui/chat/ChatAppOverlays.kt`
8. `ui/settings/SettingsModelsPage.kt`
9. `ui/chat/ChatTopBar.kt`
10. `ui/chat/ChatDrawerContent.kt`
11. `ui/chat/search/DrawerSearchBar.kt`
12. `ui/chat/message/AssistantMessageContent.kt`
13. `ui/chat/VideoSliceDialog.kt`
14. `ui/chat/PdfPageSelectDialog.kt`
15. `ui/chat/FullScreenMediaViewer.kt`
16. `ui/chat/TextFileViewer.kt`
17. `ui/tasks/TaskEditorSupportingComponents.kt`

**合计**：17 文件，~35 行改动。

### 已对齐文件（无须改动，共 ~98 文件）
- `ui/theme/`：4 文件
- `ui/chat/`：~30 文件（ChatApp/MessageList/UserMessageBubble/MessageItemTimeline/SegmentDetailSheet/ChatBottomBar/ComposerSendButton 等）
- `ui/chat/bottombar/`：9 文件
- `ui/chat/message/`：~18 文件
- `ui/chat/search/`：2 文件（DrawerSearchState/ChatSearchResultItem）
- `ui/settings/`：~28 文件
- `ui/tasks/`：3 文件（TasksScreen/TaskEditorPage/TaskHistoryPreviewState）
- `ui/onboarding/`：1 文件
- `ui/components/`：6 文件
- `ui/common/`：3 文件
- `ui/motion/`：4 文件

### 全量 UI 文件总数
**~115 文件**（ui/theme 4 + ui/chat 32 + ui/chat/message 19 + ui/chat/bottombar 9 + ui/chat/search 3 + ui/settings 30 + ui/tasks 4 + ui/onboarding 1 + ui/components 6 + ui/common 3 + ui/motion 4）。

---

## 总结

本规格**推倒**之前 P0/P1/P2 批次的所有 UI 决策，从 ChatGPT 官方 app 极简风出发**从零重新定义**每个页面的目标视觉规格。经全量审计（115 文件），当前 master HEAD `5f3a126b` 已**绝大部分对齐**目标规格（得益于前序任务 10/11/13/15/16/17/19 的渐进收敛），剩余改动仅 **17 文件 ~35 行**，分 3 批实施：

- **批 1（P0）**：全局令牌 + 硬编码色清理 + `RoundedCornerShape(50/100.dp)` → `CircleShape`（6 文件）
- **批 2（P1）**：聊天主界面触点 + 间距 + Dialog 按钮圆形化（9 文件）
- **批 3（P2）**：任务页圆角收敛（1 文件）
- **批 4（P3）**：全量验证 + 文档

**交互不动**：所有改动仅涉及视觉参数（圆角/颜色/elevation/间距/触点），不改 Composable 签名/业务逻辑/数据流/事件处理。