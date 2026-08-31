package com.lxseek.chat.membership

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 统一套餐目录（重构 R2：收敛三处价格硬编码）。
 *
 * 二元制会员体系下，套餐**不再是不同等级**，而是"付费账户"的不同买法
 * （月付 / 季付 / 半年付 / 年付 / 永久买断）——全部解锁同一档付费能力
 * （[MembershipTier.Premium]），区别只在时长与单价。
 *
 * 此前同样的价格散落在三处（设置页 PLANS 列表、MainActivity 的金额→档位
 * 映射、续费按钮金额），改价时容易漏改导致回调金额校验误判。现在全部
 * 引用本目录。
 *
 * 字段与 `/api/create_payment` 的 `plan_id` 约定一致（服务器按 plan_id 定价，
 * `amount` 仅为回退旧逻辑的参考值）。
 */
object PlanCatalog {

    /** 单个付费套餐定义。 */
    data class Plan(
        /** 套餐 ID（传给服务端 /api/create_payment 的 plan_id）。 */
        val id: String,
        /** 显示名称。 */
        val name: String,
        /** 原价（划掉显示，如 "¥9.9"）。 */
        val originalPrice: String,
        /** 促销价（醒目显示，如 "¥0.99"）。 */
        val price: String,
        /** 月均价对比文案（如 "¥0.99/月"）。 */
        val perMonth: String,
        /** 支付金额（元，字符串，不含 ¥ 符号，传支付网关）。 */
        val amount: String,
        /** 套餐天数（仅用于本地展示；永久套餐为 36500 天哨兵值）。 */
        val durationDays: Int,
    )

    val monthly: Plan = Plan(
        id = "monthly",
        name = "月度",
        originalPrice = "¥9.9",
        price = "¥0.99",
        perMonth = "¥0.99/月",
        amount = "0.99",
        durationDays = 30,
    )

    val quarterly: Plan = Plan(
        id = "quarterly",
        name = "季度",
        originalPrice = "¥19.9",
        price = "¥1.99",
        perMonth = "¥0.66/月",
        amount = "1.99",
        durationDays = 90,
    )

    val halfYear: Plan = Plan(
        id = "half_year",
        name = "半年",
        originalPrice = "¥49.9",
        price = "¥4.99",
        perMonth = "¥0.83/月",
        amount = "4.99",
        durationDays = 180,
    )

    val yearly: Plan = Plan(
        id = "yearly",
        name = "年度",
        originalPrice = "¥88",
        price = "¥8.8",
        perMonth = "¥0.73/月",
        amount = "8.8",
        durationDays = 365,
    )

    val lifetime: Plan = Plan(
        id = "lifetime",
        name = "永久",
        originalPrice = "¥198",
        price = "¥19.8",
        perMonth = "一次买断",
        amount = "19.8",
        durationDays = 36500,
    )

    /** 全部套餐（展示顺序）。 */
    val plans: List<Plan> = listOf(monthly, quarterly, halfYear, yearly, lifetime)

    /** 默认选中的套餐 ID（设置页初始态）。 */
    const val DEFAULT_PLAN_ID = "monthly"

    /** 按 ID 查套餐；未知 ID 回退月度。 */
    fun byId(id: String): Plan = plans.firstOrNull { it.id == id } ?: monthly

    /**
     * 金额比较（安全修复 H3a）：两个以"元"为单位的金额字符串是否相等，
     * 用 [BigDecimal] 按分（两位小数）比较，避免 "0.3" vs "0.30"、
     * "8.8" vs "8.80" 这类格式差异造成误判。
     *
     * 解析失败的输入一律视为不相等（保守：拒绝）。
     */
    fun amountsMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val ba = runCatching { BigDecimal(a.trim()) }.getOrNull() ?: return false
        val bb = runCatching { BigDecimal(b.trim()) }.getOrNull() ?: return false
        return ba.setScale(2, RoundingMode.HALF_UP) == bb.setScale(2, RoundingMode.HALF_UP)
    }
}