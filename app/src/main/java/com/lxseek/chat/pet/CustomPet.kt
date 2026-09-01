package com.lxseek.chat.pet

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 自定义桌面宠物数据模型，移植自 cc-haha 的 companion 系统。
 *
 * 与内置的 [PetCharacter]（静态 spritesheet 角色）不同，[CustomPet] 是用户通过
 * 「添加宠物」流程孵化出的一只独一无二的宠物：由 seed 决定外观（稀有度/物种/眼睛/帽子）
 * 并附带可被注入到 LLM 系统提示词的人设描述。
 *
 * 持久化在 DataStore 的 `pets_library_json` 中，单只宠物通过 [id] 唯一标识。
 */
@Serializable
data class CustomPet(
    val id: String = UUID.randomUUID().toString(),
    /** 锁定外观的种子；同一 seed 永远孵化出同样的宠物。 */
    val seed: String,
    /** 显示名。 */
    val name: String,
    /** 稀有度，决定 stats 下限与外观闪亮概率。 */
    val rarity: PetRarity,
    /** 物种，映射到内置 spritesheet 用于预览与悬浮窗渲染。 */
    val species: PetSpecies,
    /** 眼睛样式（ASCII 字符）。 */
    val eye: PetEye,
    /** 帽子样式。 */
    val hat: PetHat,
    /** 1% 概率的闪光个体。 */
    val shiny: Boolean,
    /** 五项性格属性，1~100。 */
    val stats: PetStats,
    /** 人设描述，会被注入到 LLM 系统提示词，让模型知道这只宠物的存在。 */
    val personality: String,
    /** 孵化时间戳（epoch ms）。 */
    val hatchedAt: Long,
)

@Serializable
enum class PetRarity(val stars: String, val weight: Int) {
    COMMON("★", 60),
    UNCOMMON("★★", 25),
    RARE("★★★", 10),
    EPIC("★★★★", 4),
    LEGENDARY("★★★★★", 1);

    companion object {
        /** 稀有度对应的 stats 下限。 */
        val FLOOR: Map<PetRarity, Int> = mapOf(
            COMMON to 5,
            UNCOMMON to 15,
            RARE to 25,
            EPIC to 35,
            LEGENDARY to 50,
        )
    }
}

/**
 * 物种枚举。每项绑定一个内置 [PetCharacter] 的 spritesheet，所以添加宠物功能
 * 不需要新的美术资源——复用 dada/huhu/bubu/huihui 四套已打包的图集。
 */
@Serializable
enum class PetSpecies(val previewCharacter: PetCharacter) {
    DUCK(PetCharacter.HUHU),
    CAT(PetCharacter.DADA),
    DRAGON(PetCharacter.BUBU),
    ROBOT(PetCharacter.HUIHUI),
    GHOST(PetCharacter.HUHU),
    BLOB(PetCharacter.DADA),
    RABBIT(PetCharacter.BUBU),
    OWL(PetCharacter.HUIHUI);
}

@Serializable
enum class PetEye(val glyph: String) {
    DOT("·"),
    STAR("✦"),
    CROSS("×"),
    CIRCLE("◉"),
    AT("@"),
    DEGREE("°");
}

@Serializable
enum class PetHat(val label: String) {
    NONE("none"),
    CROWN("crown"),
    TOPHAT("tophat"),
    PROPELLER("propeller"),
    HALO("halo"),
    WIZARD("wizard"),
    BEANIE("beanie"),
    TINYDUCK("tinyduck");
}

@Serializable
data class PetStats(
    val debugging: Int,
    val patience: Int,
    val chaos: Int,
    val wisdom: Int,
    val snark: Int,
) {
    companion object {
        val NAMES = listOf("DEBUGGING", "PATIENCE", "CHAOS", "WISDOM", "SNARK")
    }
}
