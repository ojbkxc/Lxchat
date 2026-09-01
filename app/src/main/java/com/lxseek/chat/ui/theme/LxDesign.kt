package com.lxseek.chat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * LxChat 独立设计令牌 —— 工作台（Workbench）风格。
 *
 * 设计立场与 Agora 的 ChatGPT 式圆角卡片风刻意错开：
 *  - 标准圆角 16dp（Agora 为 12dp），控件收窄到 10dp，形成更"软件感"的几何语言；
 *  - 分区采用全宽 + 首栏强调条（2dp），而非圆角卡片容器，减少一层 Surface 嵌套；
 *  - 间距阶梯在 20dp 处留出"组间"档位，用留白分区代替描边分区。
 *
 * 全部字段为编译期常量，重组零分配。
 */
object LxDesign {
    // ---------- 间距阶梯（区别 Agora 的 4/8/12/16/24/32） ----------
    val spaceXXS: Dp = 2.dp
    val spaceXS: Dp = 4.dp
    val spaceS: Dp = 8.dp
    val spaceM: Dp = 12.dp
    val spaceL: Dp = 16.dp
    val spaceXL: Dp = 20.dp   // 组间标准档：分区之间用留白，不用线
    val spaceXXL: Dp = 28.dp  // 大分区间距

    // ---------- 圆角体系（标准 16dp，区别 Agora 的 12dp） ----------
    val cornerXS: Dp = 8.dp    // 小控件：徽标、开关容器
    val cornerS: Dp = 10.dp     // 输入框、按钮
    val cornerM: Dp = 16.dp    // 标准卡片 / 气泡
    val cornerL: Dp = 20.dp   // 抽屉、大浮层
    val cornerXL: Dp = 28.dp   // 弹窗、全屏圆角

    // ---------- 结构尺寸 ----------
    val topBarHeight: Dp = 60.dp
    val minTouchTarget: Dp = 44.dp   // 最小无障碍触点
    val listRowHeight: Dp = 52.dp
    val dividerThickness: Dp = 0.5.dp  // 区内分隔线
    val accentBarThickness: Dp = 2.dp  // 分区首栏强调条 / 顶栏底线

    // ---------- 预制形状（静态复用，避免重组时重建） ----------
    val shapeS = RoundedCornerShape(cornerS)
    val shapeM = RoundedCornerShape(cornerM)
    val shapeL = RoundedCornerShape(cornerL)
    val shapeXL = RoundedCornerShape(cornerXL)

    /** 消息气泡：用户侧右上角收窄形成"尾迹"，其余 16dp —— 视觉基因的对称破缺。 */
    val shapeBubbleUser = RoundedCornerShape(
        topStart = cornerM, topEnd = cornerM,
        bottomStart = cornerM, bottomEnd = cornerXS,
    )
    val shapeBubbleModel = RoundedCornerShape(cornerM)

    /** 抽屉：仅右侧两角 20dp，左侧贴合屏幕缘。 */
    val shapeDrawer = RoundedCornerShape(topEnd = cornerL, bottomEnd = cornerL)
}
