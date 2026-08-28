# LxChat Android UI 重新规划文档

> 生成时间：2026-08-29  
> 生成者：UIRePlanner（顶级思维重新规划）  
> 范围：`app/src/main/java/com/lxseek/chat/ui/` 全量 UI 文件  
> 目标：审美系统化 + 人体工程学最优化

---

## 目录

1. [当前状态分析](#1-当前状态分析)
2. [问题清单（按优先级排序）](#2-问题清单按优先级排序)
3. [顶级思维重新规划——审美系统](#3-顶级思维重新规划审美系统)
4. [顶级思维重新规划——人体工程学系统](#4-顶级思维重新规划人体工程学系统)
5. [改进规划（按页面分组）](#5-改进规划按页面分组)
6. [实施顺序建议](#6-实施顺序建议)

---

## 1. 当前状态分析

### 1.1 设计系统现状

#### 1.1.1 颜色系统（`ui/theme/Color.kt`）

| 维度 | 现状 | 评价 |
|------|------|------|
| 预设数量 | 9 种（MINIMAL/MIDNIGHT/NORDIC/FOREST/SUNSET/ROSE/LAVENDER/SLATE/OCEAN）+ AMOLED | 丰富但分散 |
| 默认方案 | MINIMAL（仿 ChatGPT/DeepSeek） | 中性克制，符合主流审美 |
| 主色 | `#4D6BFE`（靛蓝） | 单一品牌色，统一 |
| AMOLED | 纯黑 `#000000` + 雾靛蓝 `#8E97C9` | OLED 省电，但对比度待验证 |
| 动态色彩 | Android 12+ Material You | 良好 |
| 容器层级 | surfaceContainerLowest/Low/High/Highest 五级 | 完整 |

**关键发现**：
- MINIMAL 方案明确"反 AI 蓝紫同质化"，定位清晰
- AMOLED 方案 `outline = 0xFF8A8A90` 在纯黑背景上对比度约 4.5:1，刚好达 WCAG AA，但 `outlineVariant = 0xFF3A3A40` 仅约 2:1，**不达标**
- `secondary`/`tertiary` 在 MINIMAL 中被刻意压低为中性灰，符合"色彩即中性脚手架"理念

#### 1.1.2 字体系统（`ui/theme/Type.kt`）

| 层级 | 比例 | 锚点 | 评价 |
|------|------|------|------|
| Typography（设置） | 1.2 几何级数（minor third） | body=16sp | 适合大标题 |
| ChatType（聊天） | 1.15 几何级数（major second） | body=15sp | 适合密集阅读 |
| 字重 | Normal/Medium/SemiBold/Bold 四档 | — | 完整 |
| 字族 | Outfit（默认）+ Mono | — | Outfit 大 x-height，15sp 读感≈16sp Roboto |
| 用户缩放 | `chatFontScale` 运行时乘子 | — | 良好 |

**ChatType 五层语义**：
```
Title  : 20(brand) / 19(sheet) / 17(solo) / 15(conv)
Input  : 16
Body   : 16 / 15(user) / 13(thought)
Meta   : 12 / 11(micro)
Code   : 14 / 13 / 12(thought)
```

**关键发现**：
- 字体层级清晰，但 `body=16sp` 与 `userBody=15sp` 仅差 1sp，**视觉区分度不足**
- `labelMedium` 与 `labelSmall` 完全相同（11sp/15sp），**冗余**
- Markdown 标题 h5/h6 完全相同（15sp/22sp），**冗余**

#### 1.1.3 主题系统（`ui/theme/Theme.kt`）

- `ThemeMode`：LIGHT / DARK / AMOLED / FOLLOW_DEVICE 四种
- AMOLED 优先级最高，覆盖动态色彩和预设
- 字体偏好：system / custom（自定义 TTF/OTF）
- `chatFontFamily` 和 `chatFontScale` 为顶层 var，由 Theme.kt 注入

---

### 1.2 设置页面现状

#### 1.2.1 信息架构（`SettingsScreen.kt`）

**9 个分组，30+ 子页面**：

| 分组 | 条目数 | 条目 |
|------|--------|------|
| 服务 | 3 | 服务商、模型、模型广场 |
| 响应 | 5 | 提示词、生成、上下文、路由、标题生成 |
| 多模态 | 1 | 转录 |
| 工具 | 3 | MCP、市场、会员 |
| 高级 | 8 | 搜索、Shell、自动化、设备控制、运行时状态、IM 网关、通知回复、定时任务 |
| 网络 | 1 | 代理 |
| 记忆与数据 | 3 | 记忆、数据控制、日志 |
| 外观与语言 | 2 | 外观、语言 |
| 洞察 | 1 | 统计 |
| 关于 | 1 | 关于 |

**关键发现**：
- **"高级"分组 8 个条目，远超 7±2 认知负荷极限**（P0）
- **"多模态"/"网络"/"洞察"/"关于"各 1 个条目，分组冗余**（P1）
- 服务商/模型/模型广场分散在"服务"组，但模型广场本质是市场，应归"工具"
- 高频操作（外观、语言）被埋在第 8 组

#### 1.2.2 脚手架（`SettingsScaffold.kt`）

| 元素 | 规格 | 评价 |
|------|------|------|
| 顶栏高度 | 64dp + 状态栏 | 合理 |
| 顶栏标题 | titleMedium + SemiBold + 居中 | ChatGPT 风格，良好 |
| 返回按钮 | IconButton（默认 48dp） | 达标 |
| 内容水平内边距 | 16dp | 合理 |
| 底部留白 | 32dp | 合理 |
| FAB 位置 | BottomCenter + 16dp bottom | 合理 |

#### 1.2.3 条目组件（`SettingsScreen.kt`）

| 元素 | 规格 | 评价 |
|------|------|------|
| SettingsItem 垂直内边距 | 12dp（无副标题）/ 16dp（有副标题） | 偏紧凑 |
| SettingsItem 水平内边距 | 16dp start / 16dp end | 合理 |
| leading icon | 24dp | 合理 |
| trailing arrow | 默认 IconButton（48dp） | 达标 |
| 分隔线 | 0.5dp outlineVariant | 极细，可能不可见 |
| 分组标题 | labelLarge + onSurfaceVariant | 良好 |
| 分组间距 | 24dp | 良好 |

**关键发现**：
- **0.5dp 分隔线在 AMOLED 上几乎不可见**（P1）
- **SettingsAddItem 56dp 高度**，但内部 icon 18dp + 8dp 间距，触摸区达标但视觉偏小

#### 1.2.4 子页面模式

通过 `CollapsingSettingsScaffold` + `SettingsGroupColumn` + `SettingsGroup` 三层嵌套构建。子页面普遍：
- 使用 `SettingsItem` 标准条目
- Switch 放 trailingContent
- DropdownMenu 放 trailingContent（如主题模式选择）
- 部分页面有二级路由（如 Provider → ProviderDetail，MCP → Editor）

---

### 1.3 聊天页面现状

#### 1.3.1 顶栏（`ChatTopBar.kt`）

| 元素 | 规格 | 评价 |
|------|------|------|
| 总高度 | 110dp 默认 + 渐变背景 | **偏高，占用垂直空间**（P1） |
| 布局 | 双胶囊（标题 + 操作） | ChatGPT 风格 |
| 胶囊圆角 | 16dp | 与底栏 12dp 不统一（P1） |
| 胶囊背景 | Color.Transparent | 无视觉边界，依赖 IconButton 间距 |
| IconButton | 44dp | **未达 48dp 最小触摸目标**（P0） |
| 标题胶囊宽度 | widthIn(max=260dp) | 窄屏可能挤压（P1） |
| 标题字号 | brandTitle 20sp / conversationTitle 15sp / solo 17sp | 层级清晰 |
| token 副标题 | micro 11sp + alpha 0.7 | 良好 |
| 渐变背景 | 0.98→0.80→Transparent | 良好，避免硬边 |
| Token 进度条 | 2dp 高度，三色阶（primary/orange/error） | 良好 |

**关键发现**：
- **44dp IconButton 触摸目标不达标**（P0）
- **110dp 默认高度在小屏占用过多**（P1）
- 双胶囊之间用 `Spacer(weight=1f)` 推开，标题胶囊 260dp 上限在 <360dp 屏幕会挤压操作胶囊

#### 1.3.2 底栏（`ChatBottomBar.kt` + 子文件）

| 元素 | 规格 | 评价 |
|------|------|------|
| 容器圆角 | 12dp topStart/topEnd | 与顶栏 16dp 不统一（P1） |
| 容器背景 | surfaceContainer | 良好 |
| 外边距 | 4dp horizontal, 6dp top, 10dp bottom | 合理 |
| 文本输入 | 1-6 行，16sp，16dp 水平内边距 | 良好 |
| 附件菜单按钮 | **28dp IconButton** | **远低于 48dp 触摸目标**（P0） |
| 模型选择 | FilterChip widthIn(max=180dp) | 合理 |
| 上下文按钮 | **28dp IconButton** | **远低于 48dp**（P0） |
| 语音按钮 | 44dp IconButton | **未达 48dp**（P0） |
| 发送按钮 | 48dp FAB CircleShape | 达标但偏小（P1，ChatGPT 用 56dp） |
| 工具栏行 | 4dp horizontal, 2dp vertical | 偏紧凑 |

**关键发现**：
- **附件/上下文/语音三个按钮均不达 48dp 触摸目标**（P0，最高优先级）
- 发送 FAB 48dp 达标但视觉权重不足，作为主操作应更大
- 工具栏行 2dp 垂直内边距过紧，按钮间无呼吸感

#### 1.3.3 消息列表（`MessageList.kt`）

| 元素 | 规格 | 评价 |
|------|------|------|
| contentPadding | 8dp | 偏紧凑 |
| 消息项垂直内边距 | 6dp | 偏紧凑（P2） |
| 用户气泡 | primaryContainer, 12dp 圆角, 300dp 最大宽度, 12dp 内边距 | 300dp 在平板过窄（P1） |
| 模型气泡 | Transparent, 12dp 圆角 | 良好 |
| 错误气泡 | errorContainer, 12dp 圆角 | 良好 |
| 用户气泡内边距 | 12dp | 合理 |
| 分支切换器 | 24dp IconButton + 16dp icon | **远低于 48dp**（P0） |
| 操作按钮 | 32dp IconButton + 16/18dp icon | **低于 48dp**（P0） |
| 操作按钮 tint | onSurfaceVariant alpha 0.6 | 良好，弱化次要操作 |

**关键发现**：
- **分支切换器 24dp 是全应用最小触摸目标**（P0）
- **操作按钮 32dp 不达标**（P0）
- 用户气泡 300dp 最大宽度在横屏/平板浪费空间
- 消息项 6dp 垂直内边距使长对话缺乏呼吸感

#### 1.3.4 抽屉（`ChatDrawerContent.kt`）

| 元素 | 规格 | 评价 |
|------|------|------|
| 抽屉宽度 | 自适应（computeDrawerDimensions） | 良好 |
| 抽屉圆角 | 12dp topEnd/bottomEnd | 良好 |
| 内边距 | 16dp horizontal, 20dp vertical | 合理 |
| 标题 | conversationsTitle 16sp Bold | 良好 |
| 搜索框 | DrawerSearchBar | 良好 |
| 三个按钮 | 42dp 高度, 12dp 圆角 | **未达 48dp**（P0） |
| 按钮顺序 | 全局搜索 → 任务 → 新聊天 | **新聊天应最显眼**（P1） |
| 对话项高度 | 44dp | **未达 48dp**（P0） |
| 对话项圆角 | 8dp | **与卡片 12dp 不统一**（P1） |
| 对话项内边距 | 2dp vertical | 偏紧 |

**关键发现**：
- **抽屉三个按钮 42dp 不达 48dp**（P0）
- **对话项 44dp 不达 48dp**（P0）
- **新聊天按钮顺序错误**：作为最高频操作应在最前或最显眼位置
- 8dp 圆角与全局 12dp 圆角系统不统一

---

### 1.4 引导页面现状

#### 1.4.1 WelcomeScreen（`WelcomeScreen.kt`）

| 元素 | 规格 | 评价 |
|------|------|------|
| 页面数 | 8 页 HorizontalPager | 偏多 |
| 页面索引 | 0/2/3/5/6 跳跃定义 | **索引不连续，易出错**（P1） |
| 退出动画 | fadeOut 600ms | 良好 |
| GGUF 导入 | 文件选择 + 魔数校验 | 良好 |

#### 1.4.2 WelcomeSteps（`WelcomeSteps.kt`）

| 元素 | 规格 | 评价 |
|------|------|------|
| ProviderPage | heightIn(max=340dp) | **固定值不响应屏幕**（P1） |
| 卡片圆角 | 12dp | 统一 |
| 卡片背景 | surfaceContainer | 统一 |
| 滚动条 | 自定义绘制 4dp 宽 | 良好 |
| RadioButton | 默认 | 良好 |
| OutlinedTextField | 12dp 圆角 | 统一 |
| icon size | 36dp（provider）/ 32dp（custom）/ 20dp（list） | **不一致**（P1） |

**关键发现**：
- **页面索引跳跃定义（PAGE_WELCOME=0, PAGE_PROVIDER=2, PAGE_API_KEY=3...）**，中间 1/4 缺失，逻辑混乱
- ProviderPage 340dp 固定高度在小屏可能溢出，在大屏浪费
- icon 尺寸 36/32/20 三种不统一

---

## 2. 问题清单（按优先级排序）

### P0 — 必须做（触摸目标 + 认知负荷 + 对比度）

| # | 问题 | 文件 | 当前值 | 目标值 | 影响 |
|---|------|------|--------|--------|------|
| P0-1 | 附件菜单按钮触摸目标 | ComposerAttachmentBar.kt | 28dp | 48dp | 误触率高 |
| P0-2 | 上下文按钮触摸目标 | ComposerToolBar.kt | 28dp | 48dp | 误触率高 |
| P0-3 | 语音按钮触摸目标 | ComposerVoiceInput.kt | 44dp | 48dp | 误触率高 |
| P0-4 | 顶栏 IconButton 触摸目标 | ChatTopBar.kt | 44dp | 48dp | 误触率高 |
| P0-5 | 抽屉按钮触摸目标 | ChatDrawerContent.kt | 42dp | 48dp | 误触率高 |
| P0-6 | 抽屉对话项触摸目标 | ChatDrawerContent.kt | 44dp | 48dp | 误触率高 |
| P0-7 | 消息操作按钮触摸目标 | UserMessageBubble.kt | 32dp | 48dp | 误触率高 |
| P0-8 | 分支切换器触摸目标 | UserMessageBubble.kt | 24dp | 48dp | 误触率高 |
| P0-9 | "高级"分组 8 条目超认知负荷 | SettingsScreen.kt | 8 | ≤5 | 找不到设置 |
| P0-10 | AMOLED outlineVariant 对比度 | Color.kt | 0xFF3A3A40 | ≥4.5:1 | 不可见分隔线 |
| P0-11 | 0.5dp 分隔线在 AMOLED 不可见 | SettingsScreen.kt | 0.5dp | 1dp | 分组边界丢失 |

### P1 — 应该做（视觉一致性 + 信息架构 + 响应式）

| # | 问题 | 文件 | 当前值 | 目标值 | 影响 |
|---|------|------|--------|--------|------|
| P1-1 | 顶栏胶囊圆角 ≠ 底栏圆角 | ChatTopBar.kt / ChatBottomBar.kt | 16dp / 12dp | 统一 12dp | 视觉不统一 |
| P1-2 | 抽屉对话项圆角 ≠ 全局 | ChatDrawerContent.kt | 8dp | 12dp | 视觉不统一 |
| P1-3 | 顶栏 110dp 偏高 | ChatTopBar.kt | 110dp | 80dp | 空间浪费 |
| P1-4 | 发送 FAB 偏小 | ComposerSendButton.kt | 48dp | 56dp | 主操作权重不足 |
| P1-5 | 用户气泡 300dp 最大宽度 | UserMessageBubble.kt | 300dp | 响应式 | 平板浪费 |
| P1-6 | 新聊天按钮顺序 | ChatDrawerContent.kt | 第 3 位 | 第 1 位 | 高频操作埋没 |
| P1-7 | 引导页面索引跳跃 | WelcomeScreen.kt | 0/2/3/5/6 | 0-7 连续 | 维护风险 |
| P1-8 | labelMedium == labelSmall | Type.kt | 相同 | 区分 | 冗余 |
| P1-9 | mdH5 == mdH6 | Type.kt | 相同 | 区分 | 冗余 |
| P1-10 | body == userBody+1 | Type.kt | 16/15 | 16/14 | 区分度不足 |
| P1-11 | 引导页 icon 尺寸不统一 | WelcomeSteps.kt | 36/32/20 | 32/20 | 视觉不统一 |
| P1-12 | ProviderPage 固定 340dp | WelcomeSteps.kt | 340dp | weight/响应式 | 适配差 |
| P1-13 | 设置分组冗余（4 个单条目组） | SettingsScreen.kt | 9 组 | 6 组 | 认知负荷 |
| P1-14 | 高频设置（外观/语言）位置靠后 | SettingsScreen.kt | 第 8 组 | 第 2 组 | 可达性差 |

### P2 — 可以做（细节优化 + 体验提升）

| # | 问题 | 文件 | 改进 |
|---|------|------|------|
| P2-1 | 消息项垂直内边距偏紧 | MessageList.kt | 6dp → 8dp |
| P2-2 | contentPadding 偏紧 | MessageList.kt | 8dp → 12dp |
| P2-3 | 工具栏行垂直内边距 | ChatBottomBar.kt | 2dp → 4dp |
| P2-4 | 设置搜索无快捷入口 | SettingsScreen.kt | 加最近/常用 |
| P2-5 | 抽屉对话项无时间戳 | ChatDrawerContent.kt | 加相对时间 |
| P2-6 | 引导页无进度指示 | WelcomeScreen.kt | 加步骤点 |
| P2-7 | 主题预设无自定义种子 | Color.kt | 加颜色选择器 |
| P2-8 | 消息气泡可考虑非对称圆角 | MessageItem.kt | 用户右下角尖 |

---

## 3. 顶级思维重新规划——审美系统

### 3.1 色彩系统规范

#### 3.1.1 语义化色彩分层

```
┌─ Brand（品牌层）
│  primary         #4D6BFE  品牌主色，仅用于：FAB、Switch 选中、链接、强调
│  onPrimary       #FFFFFF  主色上的文字
│  primaryContainer #EDEFFF 品牌容器，用于：用户消息气泡、选中态
│
├─ Surface（表面层，由暗到亮）
│  background      #FFFFFF  页面背景
│  surface         #FFFFFF  卡片背景
│  surfaceContainerLowest → Highest  五级容器，用于分层
│
├─ Content（内容层）
│  onSurface       #1C1C1E  主要文字
│  onSurfaceVariant #6B6B6F  次要文字、icon、label
│
├─ Divider（分隔层）
│  outline         #E5E5EA  强分隔（卡片边框）
│  outlineVariant  #EEEEF1  弱分隔（条目间）
│
└─ State（状态层）
   error           #DC2626  错误
   warning         #FF9800  警告（token 80%）
   success         #16A34A  成功
```

#### 3.1.2 色彩使用铁律

1. **primary 仅用于"用户主动操作"**：发送按钮、选中态、链接、进度条。**禁止**用于装饰
2. **primaryContainer 仅用于"用户产出内容"**：用户消息气泡、选中 chip
3. **surface 系列用于"承载内容的容器"**：卡片、底栏、抽屉
4. **onSurfaceVariant 用于"辅助信息"**：副标题、icon、时间戳、placeholder
5. **error 仅用于"破坏性操作或错误状态"**：删除按钮、错误气泡、token 95%
6. **每屏 primary 出现不超过 3 处**：避免视觉噪音

#### 3.1.3 AMOLED 对比度修复

```kotlin
// Color.kt — amoledDarkColorScheme()
outline = Color(0xFF8A8A90),         // 保留，4.5:1 达标
outlineVariant = Color(0xFF5A5A60),  // 修复：0xFF3A3A40 → 0xFF5A5A60，达 3:1
```

### 3.2 字体层级规范

#### 3.2.1 设置页面（Typography，1.2 比例）

| 用途 | Style | 字号 | 字重 | 行高 |
|------|-------|------|------|------|
| 大标题（弃用） | displayLarge | 57 | Normal | 66 |
| 页面标题 | titleMedium | 16 | SemiBold | 21 |
| 分组标题 | labelLarge | 13 | Medium | 18 |
| 条目主文字 | bodyLarge | 16 | Medium | 23 |
| 条目副文字 | bodyMedium | 13 | Normal | 19 |
| 开关标签 | labelMedium | 11 | Medium | 15 |

#### 3.2.2 聊天页面（ChatType，1.15 比例）

| 用途 | Style | 字号 | 字重 | 行高 |
|------|-------|------|------|------|
| 品牌词 | brandTitle | 20 | Bold | 26 |
| Sheet 标题 | sheetTitle | 19 | Bold | 25 |
| 详情标题 | detailTitle | 22 | Bold | 28 |
| 评分标题 | ratingTitle | 28 | Bold | 35 |
| 独立会话标题 | conversationTitleSolo | 17 | Bold | 22 |
| 会话标题 | conversationTitle | 15 | Bold | 20 |
| 输入文字 | input | 16 | Normal | 23 |
| 助手正文 | body | 16 | Normal | 24 |
| 用户正文 | userBody | **14** | Normal | 20 | ← 修复：15→14，拉开与 body 差距
| 思考正文 | thoughtBody | 13 | Normal | 19 |
| 元信息 | meta | 12 | Medium | 17 |
| 微信息 | micro | 11 | Medium | 15 |

#### 3.2.3 字体使用铁律

1. **聊天页面所有 Text 必须用 ChatType**，禁止直接用 Typography
2. **设置页面所有 Text 必须用 MaterialTheme.typography**
3. **字号差 ≥ 2sp 才有视觉区分度**：相邻层级不能仅差 1sp
4. **字重 + 颜色共同承载层级**：如 12sp Bold + primary > 13sp Normal + onSurfaceVariant

### 3.3 间距系统规范

#### 3.3.1 间距令牌

```
xs   = 4dp    // 紧凑间距（icon 与文字间）
sm   = 8dp    // 默认小间距（条目内元素间）
md   = 12dp   // 默认中间距（卡片内边距）
lg   = 16dp   // 默认大间距（页面水平内边距、条目间）
xl   = 24dp   // 分组间距
xxl  = 32dp   // 页面底部留白
```

#### 3.3.2 间距使用铁律

1. **页面水平内边距 = 16dp**（lg）
2. **卡片内边距 = 12dp**（md）
3. **条目间垂直间距 = 8dp**（sm）或无间距 + 分隔线
4. **分组间垂直间距 = 24dp**（xl）
5. **icon 与文字间距 = 8dp**（sm）或 12dp（md）
6. **禁止使用 6dp/10dp 等非令牌值**（现有 6dp 顶栏外边距、10dp 底栏外边距需对齐）

### 3.4 圆角系统规范

#### 3.4.1 圆角令牌

```
small  = 4dp    // chip、小标签
medium = 8dp    // 次要卡片、抽屉对话项
large  = 12dp   // 主卡片、消息气泡、底栏、抽屉
xlarge = 16dp   // 顶栏胶囊、FAB
full   = 100dp  // 胶囊形（分支切换器）
```

#### 3.4.2 圆角使用铁律

1. **顶栏胶囊 = 16dp**（xlarge）— 与 FAB 呼应
2. **底栏容器 = 12dp**（large）— 与卡片一致
3. **消息气泡 = 12dp**（large）— 与卡片一致
4. **抽屉对话项 = 12dp**（large）— 修复 8dp 不统一
5. **设置条目分隔线无圆角**
6. **FAB = CircleShape**（全圆）
7. **禁止混用 8dp 和 12dp 于同层级元素**

### 3.5 图标系统规范

#### 3.5.1 图标令牌

```
small  = 16dp   // 行内 icon（trailing arrow、分支切换）
medium = 20dp   // 列表 icon（抽屉按钮、引导页列表）
large  = 24dp   // 主 icon（设置条目 leading、顶栏）
xlarge = 32dp   // 强调 icon（引导页 provider 头）
```

#### 3.5.2 图标使用铁律

1. **设置条目 leading icon = 24dp** + tint primary
2. **设置条目 trailing icon = 18dp** + tint onSurfaceVariant
3. **顶栏 IconButton 内 icon = 24dp**
4. **底栏 IconButton 内 icon = 24dp**
5. **抽屉按钮 icon = 20dp**
6. **引导页 provider 头 icon = 32dp**（统一，修复 36/32 混用）
7. **所有可点击 icon 必须在 ≥48dp 容器内**

---

## 4. 顶级思维重新规划——人体工程学系统

### 4.1 拇指可达区域规划

#### 4.1.1 屏幕分区（以 6 寸屏 ~ 360×640dp 为例）

```
┌─────────────────────┐
│  状态栏 + 顶栏       │  不可达区（<10% 操作）
│  (~150dp)           │
├─────────────────────┤
│                     │
│  消息列表            │  可达但需伸展（~30% 操作）
│  (~400dp)           │  ← 滚动、长按、点击消息
│                     │
├─────────────────────┤
│  底栏                │  拇指自然区（>60% 操作）
│  (~120dp)           │  ← 输入、发送、附件、模型、工具
└─────────────────────┘
```

#### 4.1.2 操作频率排序与放置

| 频率 | 操作 | 当前位置 | 规划位置 | 理由 |
|------|------|----------|----------|------|
| 极高 | 发送/停止 | 底栏右 FAB | 底栏右 FAB 56dp | ✓ 已在拇指区 |
| 极高 | 文本输入 | 底栏中部 | 底栏中部 | ✓ 已在拇指区 |
| 高 | 新聊天 | 抽屉第 3 按钮 | **抽屉第 1 按钮** | 高频操作前置 |
| 高 | 模型选择 | 底栏工具栏 | 底栏工具栏左 | ✓ 已在拇指区 |
| 高 | 附件 | 底栏工具栏 | 底栏工具栏左 | ✓ 已在拇指区 |
| 中 | 思考/工具 | 底栏工具菜单 | 底栏工具菜单 | ✓ 已在拇指区 |
| 中 | 设置 | 抽屉底部 | 抽屉底部固定 | ✓ |
| 中 | 搜索 | 顶栏菜单 | 顶栏直接按钮 | 减少点击层级 |
| 低 | 分叉/分享 | 顶栏菜单 | 顶栏菜单 | ✓ |
| 低 | 外观/语言 | 设置第 8 组 | **设置第 2 组** | 高频设置前置 |

### 4.2 触摸目标规范

#### 4.2.1 铁律：所有可点击元素 ≥ 48dp

| 元素 | 当前 | 目标 | 修复方式 |
|------|------|------|----------|
| 顶栏 IconButton | 44dp | 48dp | `Modifier.size(48.dp)` |
| 底栏附件按钮 | 28dp | 48dp | `Modifier.size(48.dp)`，icon 保持 24dp |
| 底栏上下文按钮 | 28dp | 48dp | 同上 |
| 底栏语音按钮 | 44dp | 48dp | `Modifier.size(48.dp)` |
| 底栏发送 FAB | 48dp | 56dp | `Modifier.size(56.dp)`，主操作放大 |
| 抽屉按钮 | 42dp | 48dp | `Modifier.height(48.dp)` |
| 抽屉对话项 | 44dp | 48dp | `Modifier.height(48.dp)` |
| 消息操作按钮 | 32dp | 48dp | `Modifier.size(48.dp)`，icon 保持 16dp |
| 分支切换器 | 24dp | 48dp | `Modifier.size(48.dp)`，icon 保持 16dp |
| 设置条目 | ~48dp | 48dp | ✓ 已达标 |
| 设置返回按钮 | 48dp | 48dp | ✓ 已达标 |

#### 4.2.2 视觉反馈规范

1. **所有可点击元素必须有 ripple**（Compose 默认 clickable 即有）
2. **所有状态切换必须有动画**：
   - 颜色切换：`animateColorAsState(tween(300))`
   - 大小切换：`animateDpAsState(tween(300))`
   - 透明度切换：`Crossfade(tween(250))`
3. **所有操作必须有触觉反馈**：
   - 选择/切换：`haptics.selection()`
   - 确认/发送：`haptics.confirm()`
   - 破坏性确认：`haptics.destructiveConfirmed()`

### 4.3 认知负荷规划

#### 4.3.1 设置页面分组重构

**当前 9 组 → 规划 6 组**：

| 规划分组 | 条目数 | 条目 | 理由 |
|----------|--------|------|------|
| **外观与语言** | 2 | 外观、语言 | 高频，前置 |
| **模型与服务** | 4 | 服务商、模型、模型广场、生成 | 核心功能 |
| **提示与上下文** | 4 | 提示词、上下文、路由、标题生成 | 响应控制 |
| **工具与集成** | 5 | MCP、市场、搜索、Shell、自动化 | 工具类 |
| **数据与记忆** | 4 | 记忆、数据控制、转录、日志 | 数据类 |
| **关于** | 3 | 关于、统计、代理 | 低频，后置 |

**移入二级页面**（从主列表移除，减少认知负荷）：
- 设备控制 → 工具与集成二级
- 运行时状态 → 关于二级
- IM 网关 → 工具与集成二级
- 通知回复 → 工具与集成二级
- 定时任务 → 工具与集成二级
- 会员 → 关于二级

#### 4.3.2 每屏条目数铁律

1. **设置主列表每组 ≤ 5 条目**
2. **设置主列表总组数 ≤ 6**
3. **底栏工具栏直接可见按钮 ≤ 4**（附件、模型、上下文、发送）
4. **顶栏直接可见按钮 ≤ 3**（菜单、新聊天、更多）
5. **抽屉直接可见按钮 ≤ 3**（新聊天、搜索、任务）

### 4.4 操作频率排序规划

#### 4.4.1 设置页面排序

按用户使用频率排序（基于常见 LLM 聊天应用数据）：

```
1. 外观        ← 每个用户必调
2. 语言        ← 国际用户必调
3. 服务商      ← 首次配置必用
4. 模型        ← 高频切换
5. 提示词      ← 中频调优
6. 生成        ← 中频调优
7. 上下文      ← 中频调优
8. 模型广场    ← 低频探索
9. MCP         ← 高级用户
10. 市场       ← 低频
...（其余按频率降序）
```

#### 4.4.2 底栏工具栏排序

按使用频率从左到右：

```
[附件] [模型] [上下文] ... [语音] [发送]
  高     高     中           中     极高
```

当前顺序已合理，仅需修复触摸目标。

---

## 5. 改进规划（按页面分组）

### 5.1 主题系统改进

| 文件 | 改动 | 优先级 | 预期效果 |
|------|------|--------|----------|
| `Color.kt` | AMOLED `outlineVariant` 0xFF3A3A40 → 0xFF5A5A60 | P0 | 分隔线可见 |
| `Type.kt` | `userBody` 15sp → 14sp | P1 | 与 body 拉开区分 |
| `Type.kt` | `labelSmall` 11sp → 10sp | P1 | 与 labelMedium 区分 |
| `Type.kt` | `mdH6` 15sp → 14sp | P1 | 与 mdH5 区分 |
| `Color.kt` | 新增 `success` 语义色 | P2 | 状态完整性 |

### 5.2 设置页面改进

#### 5.2.1 主列表（`SettingsScreen.kt`）

| 改动 | 优先级 | 预期效果 |
|------|--------|----------|
| 重构 `settingsGroups` 为 6 组 | P0 | 认知负荷降低 |
| "外观与语言"移到第 1 组 | P0 | 高频设置前置 |
| "高级"组拆分到"工具与集成"二级 | P0 | 每组 ≤5 条目 |
| 分隔线 0.5dp → 1dp | P0 | AMOLED 可见 |
| 分隔线颜色用 `outline` 而非 `outlineVariant` | P0 | 对比度达标 |

#### 5.2.2 脚手架（`SettingsScaffold.kt`）

| 改动 | 优先级 | 预期效果 |
|------|--------|----------|
| 顶栏高度 64dp 保持 | — | 已合理 |
| 内容水平内边距 16dp 保持 | — | 已合理 |

#### 5.2.3 条目组件（`SettingsScreen.kt`）

| 改动 | 优先级 | 预期效果 |
|------|--------|----------|
| `SettingsItem` 垂直内边距 12/16dp → 14/18dp | P2 | 呼吸感 |
| `SettingsAddItem` 保持 56dp | — | 已达标 |

### 5.3 聊天页面改进

#### 5.3.1 顶栏（`ChatTopBar.kt`）

| 改动 | 优先级 | 预期效果 |
|------|--------|----------|
| `defaultMinSize(minHeight = 110.dp)` → 80.dp | P1 | 释放垂直空间 |
| 胶囊圆角 16dp → 12dp | P1 | 与底栏统一 |
| IconButton 44dp → 48dp | P0 | 触摸目标达标 |
| 标题胶囊 widthIn(max=260dp) → max=220dp | P1 | 窄屏不挤压 |
| 搜索从菜单提升为直接按钮 | P2 | 减少点击层级 |

#### 5.3.2 底栏（`ChatBottomBar.kt` + 子文件）

| 文件 | 改动 | 优先级 | 预期效果 |
|------|------|--------|----------|
| `ChatBottomBar.kt` | 容器圆角 12dp 保持 | — | 已合理 |
| `ChatBottomBar.kt` | 工具栏行 vertical 2dp → 4dp | P2 | 呼吸感 |
| `ComposerAttachmentBar.kt` | IconButton 28dp → 48dp | P0 | 触摸目标达标 |
| `ComposerToolBar.kt` | 上下文 IconButton 28dp → 48dp | P0 | 触摸目标达标 |
| `ComposerVoiceInput.kt` | IconButton 44dp → 48dp | P0 | 触摸目标达标 |
| `ComposerSendButton.kt` | FAB 48dp → 56dp | P1 | 主操作权重 |
| `ComposerSendButton.kt` | FAB icon 24dp → 28dp | P1 | 与 56dp 容器匹配 |
| `ComposerTextInput.kt` | 行数 1-6 → 1-8 | P2 | 长输入体验 |

#### 5.3.3 消息列表（`MessageList.kt` + `MessageItem.kt`）

| 文件 | 改动 | 优先级 | 预期效果 |
|------|------|--------|----------|
| `MessageList.kt` | contentPadding 8dp → 12dp | P2 | 呼吸感 |
| `MessageItem.kt` | 消息项 vertical 6dp → 8dp | P2 | 呼吸感 |
| `MessageItem.kt` | 用户气泡 widthIn(max=300dp) → 响应式 | P1 | 平板适配 |
| `UserMessageBubble.kt` | 操作按钮 32dp → 48dp | P0 | 触摸目标达标 |
| `UserMessageBubble.kt` | 分支切换 24dp → 48dp | P0 | 触摸目标达标 |
| `UserMessageBubble.kt` | 用户气泡圆角 12dp 保持 | — | 已统一 |

#### 5.3.4 抽屉（`ChatDrawerContent.kt`）

| 改动 | 优先级 | 预期效果 |
|------|--------|----------|
| 三个按钮 42dp → 48dp | P0 | 触摸目标达标 |
| 对话项 44dp → 48dp | P0 | 触摸目标达标 |
| 对话项圆角 8dp → 12dp | P1 | 全局统一 |
| 新聊天按钮移到第 1 位 | P1 | 高频前置 |
| 新聊天按钮用 `Button`（primary），其余用 `FilledTonalButton` | P1 | 视觉层级 |

### 5.4 引导页面改进

| 文件 | 改动 | 优先级 | 预期效果 |
|------|------|--------|----------|
| `WelcomeScreen.kt` | 页面索引 0/2/3/5/6 → 0-7 连续 | P1 | 维护性 |
| `WelcomeScreen.kt` | 加进度指示点 | P2 | 进度感知 |
| `WelcomeSteps.kt` | ProviderPage 340dp → weight 响应式 | P1 | 适配 |
| `WelcomeSteps.kt`` | icon 36/32/20 → 32/20 统一 | P1 | 视觉统一 |

---

## 6. 实施顺序建议

### 阶段 1：P0 触摸目标修复（1-2 天）

**目标**：所有可点击元素 ≥ 48dp

1. `ComposerAttachmentBar.kt`：IconButton 28dp → 48dp
2. `ComposerToolBar.kt`：上下文 IconButton 28dp → 48dp
3. `ComposerVoiceInput.kt`：IconButton 44dp → 48dp
4. `ChatTopBar.kt`：所有 IconButton 44dp → 48dp
5. `ChatDrawerContent.kt`：按钮 42dp → 48dp，对话项 44dp → 48dp
6. `UserMessageBubble.kt`：操作按钮 32dp → 48dp，分支切换 24dp → 48dp
7. `Color.kt`：AMOLED outlineVariant 对比度修复
8. `SettingsScreen.kt`：分隔线 0.5dp → 1dp，颜色用 outline

### 阶段 2：P0 认知负荷修复（1 天）

**目标**：设置主列表 ≤ 6 组，每组 ≤ 5 条目

1. `SettingsScreen.kt`：重构 `settingsGroups` 为 6 组
2. "外观与语言"移到第 1 组
3. "高级"组拆分，部分移入二级页面

### 阶段 3：P1 视觉一致性（1-2 天）

1. `ChatTopBar.kt`：胶囊圆角 16dp → 12dp，高度 110dp → 80dp
2. `ChatDrawerContent.kt`：对话项圆角 8dp → 12dp，按钮顺序调整
3. `ComposerSendButton.kt`：FAB 48dp → 56dp
4. `Type.kt`：userBody/labelSmall/mdH6 字号区分
5. `UserMessageBubble.kt`：用户气泡响应式宽度
6. `WelcomeScreen.kt`：页面索引连续化
7. `WelcomeSteps.kt`：icon 尺寸统一，ProviderPage 响应式

### 阶段 4：P2 细节优化（按需）

1. 消息列表内边距调整
2. 设置搜索快捷入口
3. 抽屉对话项时间戳
4. 引导页进度指示
5. 主题自定义种子色

---

## 附录 A：触摸目标审计表

| 元素 | 文件 | 当前 | 目标 | 状态 |
|------|------|------|------|------|
| 设置返回按钮 | SettingsScaffold.kt | 48dp | 48dp | ✅ |
| 设置条目 | SettingsScreen.kt | ~48dp | 48dp | ✅ |
| 设置搜索清除 | SettingsScreen.kt | 48dp | 48dp | ✅ |
| 顶栏菜单按钮 | ChatTopBar.kt | 44dp | 48dp | ❌ P0 |
| 顶栏新聊天 | ChatTopBar.kt | 44dp | 48dp | ❌ P0 |
| 顶栏更多 | ChatTopBar.kt | 44dp | 48dp | ❌ P0 |
| 顶栏搜索上下 | ChatTopBar.kt | 44dp | 48dp | ❌ P0 |
| 底栏附件 | ComposerAttachmentBar.kt | 28dp | 48dp | ❌ P0 |
| 底栏模型 chip | ComposerToolBar.kt | ~48dp | 48dp | ✅ |
| 底栏上下文 | ComposerToolBar.kt | 28dp | 48dp | ❌ P0 |
| 底栏工具 | ComposerToolBar.kt | ~48dp | 48dp | ✅ |
| 底栏语音 | ComposerVoiceInput.kt | 44dp | 48dp | ❌ P0 |
| 底栏发送 | ComposerSendButton.kt | 48dp | 56dp | ⚠️ P1 |
| 抽屉新聊天 | ChatDrawerContent.kt | 42dp | 48dp | ❌ P0 |
| 抽屉搜索 | ChatDrawerContent.kt | 42dp | 48dp | ❌ P0 |
| 抽屉任务 | ChatDrawerContent.kt | 42dp | 48dp | ❌ P0 |
| 抽屉对话项 | ChatDrawerContent.kt | 44dp | 48dp | ❌ P0 |
| 消息复制 | UserMessageBubble.kt | 32dp | 48dp | ❌ P0 |
| 消息编辑 | UserMessageBubble.kt | 32dp | 48dp | ❌ P0 |
| 消息更多 | UserMessageBubble.kt | 32dp | 48dp | ❌ P0 |
| 分支切换 | UserMessageBubble.kt | 24dp | 48dp | ❌ P0 |

## 附录 B：圆角审计表

| 元素 | 文件 | 当前 | 目标 | 状态 |
|------|------|------|------|------|
| 顶栏胶囊 | ChatTopBar.kt | 16dp | 12dp | ❌ P1 |
| 底栏容器 | ChatBottomBar.kt | 12dp | 12dp | ✅ |
| 消息气泡 | MessageItem.kt | 12dp | 12dp | ✅ |
| 抽屉对话项 | ChatDrawerContent.kt | 8dp | 12dp | ❌ P1 |
| 抽屉按钮 | ChatDrawerContent.kt | 12dp | 12dp | ✅ |
| 设置卡片 | SettingsAboutPage.kt | 12dp | 12dp | ✅ |
| 引导卡片 | WelcomeSteps.kt | 12dp | 12dp | ✅ |
| FAB | ComposerSendButton.kt | Circle | Circle | ✅ |
| 分支切换器 | UserMessageBubble.kt | 100dp | 100dp | ✅ |

## 附录 C：字号审计表

| 用途 | Style | 当前 | 目标 | 状态 |
|------|-------|------|------|------|
| body | ChatType.body | 16sp | 16sp | ✅ |
| userBody | ChatType.userBody | 15sp | 14sp | ❌ P1 |
| thoughtBody | ChatType.thoughtBody | 13sp | 13sp | ✅ |
| labelMedium | Typography.labelMedium | 11sp | 11sp | ✅ |
| labelSmall | Typography.labelSmall | 11sp | 10sp | ❌ P1 |
| mdH5 | ChatType.mdH5 | 15sp | 15sp | ✅ |
| mdH6 | ChatType.mdH6 | 15sp | 14sp | ❌ P1 |

---

**文档结束**。本规划基于 2026-08-29 的代码状态生成，所有改动项均标注了文件名、当前值、目标值和优先级，可直接作为实施工单拆解。