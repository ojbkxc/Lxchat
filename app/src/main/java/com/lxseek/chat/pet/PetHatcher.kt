package com.lxseek.chat.pet

/**
 * 宠物孵化器：移植自 cc-haha 的 `companion.ts`（mulberry32 PRNG + roll 逻辑）。
 *
 * 用一个 seed 字符串确定性地生成一只有稀有度/物种/眼睛/帽子/属性/闪光的宠物，
 * 同一 seed 永远孵化出同一只——这样保存的 pet 只要存 seed 就能复现外观，无需保存整张图。
 */
object PetHatcher {

    private fun hashString(s: String): Long {
        var h = 2166136261L
        for (i in 0 until s.length) {
            h = h xor s.codePointAt(i).toLong()
            h *= 16777619L
        }
        return h
    }

    private fun mulberry32(seed: Long): () -> Double {
        var a = seed
        return {
            a = a + 0x6D2B79F5L
            var t = a
            t = t xor (t ushr 15)
            t = t * (t or 1L)
            t = t xor (t ushr 7)
            t = t * (t or 61L)
            t = t xor (t ushr 14)
            (t and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }

    private fun <T> pick(rng: () -> Double, arr: List<T>): T = arr[(rng() * arr.size).toInt()]

    private fun rollRarity(rng: () -> Double): PetRarity {
        val total = PetRarity.entries.sumOf { it.weight }
        var roll = rng() * total
        for (r in PetRarity.entries) {
            roll -= r.weight
            if (roll < 0) return r
        }
        return PetRarity.COMMON
    }

    private fun rollStats(rng: () -> Double, rarity: PetRarity): PetStats {
        val floor = PetRarity.FLOOR[rarity] ?: 5
        val statNames = PetStats.NAMES
        val peak = pick(rng, statNames)
        var dump = pick(rng, statNames)
        while (dump == peak) dump = pick(rng, statNames)

        fun statFor(name: String): Int {
            return when (name) {
                peak -> minOf(100, floor + 50 + (rng() * 30).toInt())
                dump -> maxOf(1, floor - 10 + (rng() * 15).toInt())
                else -> floor + (rng() * 40).toInt()
            }
        }
        return PetStats(
            debugging = statFor("DEBUGGING"),
            patience = statFor("PATIENCE"),
            chaos = statFor("CHAOS"),
            wisdom = statFor("WISDOM"),
            snark = statFor("SNARK"),
        )
    }

    /** 从 seed 字符串生成一只 [CustomPet] 的所有字段（除 name/personality 外）。 */
    fun roll(seed: String, name: String, personality: String, hatchedAt: Long = System.currentTimeMillis()): CustomPet {
        val rng = mulberry32(hashString(seed))
        val rarity = rollRarity(rng)
        val species = pick(rng, PetSpecies.entries.toList())
        val eye = pick(rng, PetEye.entries.toList())
        val hat = if (rarity == PetRarity.COMMON) PetHat.NONE else pick(rng, PetHat.entries.toList())
        val shiny = rng() < 0.01
        val stats = rollStats(rng, rarity)
        return CustomPet(
            seed = seed,
            name = name,
            personality = personality,
            rarity = rarity,
            species = species,
            eye = eye,
            hat = hat,
            shiny = shiny,
            stats = stats,
            hatchedAt = hatchedAt,
        )
    }

    /** 生成一个新的随机 seed（基于时间 + Math.random）。 */
    fun newSeed(): String = "hatch:${System.currentTimeMillis()}:${randomToken()}"

    private fun randomToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(8)
        repeat(8) { sb.append(chars[(Math.random() * chars.length).toInt()]) }
        return sb.toString()
    }

    /** 随机一个名字（形容词 + 名词），参考 cc-haha 的 hatch 逻辑。 */
    fun randomName(): String {
        val adjectives = listOf("Bright", "Cozy", "Swift", "Calm", "Wise", "Bold", "Fuzzy", "Lucky", "Snappy", "Quirky")
        val nouns = listOf("Spark", "Pixel", "Ember", "Glitch", "Byte", "Flux", "Drift", "Blip", "Quip", "Zap")
        val adj = adjectives[(Math.random() * adjectives.size).toInt()]
        val noun = nouns[(Math.random() * nouns.size).toInt()]
        return "$adj $noun"
    }

    /** 根据稀有度+物种生成默认人设描述。 */
    fun defaultPersonality(rarity: PetRarity, species: PetSpecies): String =
        "A ${rarity.name.lowercase()} ${species.name.lowercase()} who loves debugging and hanging out."
}
