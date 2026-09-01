package com.lxseek.chat.plugin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 契约快照（A2，借鉴 openclaw api-baseline 思想）：锁定 [Plugin] 公共面 ——
 * 接口方法、[PluginManifest] 字段、[PluginCategory] 值集。
 *
 * 任何让快照变化的改动都是对第三方插件生态的破坏性变更：必须同步迁移全部内置插件
 * （McpPlugin / NativeToolsPlugin / BuiltinSkillsPlugin 等）并在同一提交内更新此快照
 * （见 plugin/AGENTS.md「契约变更纪律」）。CI 上快照不匹配即编译红，防止契约被无意破坏。
 */
class PluginContractSnapshotTest {

    /** [Plugin] 接口的实例方法签名集（含 Kotlin val 编译出的 getter；滤除合成/静态方法）。 */
    @Test
    fun pluginInterface_publicMethods_areFrozen() {
        val surface = Plugin::class.java.declaredMethods
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .map { method ->
                "${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})" +
                    ":${method.returnType.simpleName}"
            }
            .sorted()
        assertEquals(
            listOf(
                "getManifest():PluginManifest",
                "onDisable():void",
                "onEnable(PluginContext):void",
                "settingsSchema():PluginSettingsSchema",
                "skills():List",
                "toolProviders(PluginContext):List",
            ),
            surface,
        )
    }

    /** [PluginManifest] 主构造字段名与顺序（新增字段必须是有默认值的尾部字段）。 */
    @Test
    fun pluginManifest_fields_areFrozen() {
        val fields = PluginManifest::class.java.constructors
            .maxByOrNull { it.parameterCount }
            ?.parameters
            ?.map { it.name }
            .orEmpty()
        assertEquals(
            listOf(
                "id", "name", "version", "category", "description", "author",
                "requiresMembership", "builtIn", "preferenceKey",
            ),
            fields,
        )
    }

    /** [PluginCategory] 枚举值集：序列化到 DataStore，删除/重命名即数据迁移事件。 */
    @Test
    fun pluginCategory_values_areFrozen() {
        assertEquals(
            listOf("Integrated", "Mcp", "External"),
            PluginCategory.entries.map { it.name },
        )
    }
}