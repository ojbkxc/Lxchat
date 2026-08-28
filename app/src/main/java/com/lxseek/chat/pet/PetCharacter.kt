package com.lxseek.chat.pet

/**
 * The built-in floating-pet sprites. The original single bubble is kept as [CLASSIC]; the other
 * four roles (borrowed from the cc-haha desktop pets) each get their own color palette and a
 * signature accessory so they read as distinct characters.
 *
 * The persisted preference stores [prefKey]; unknown values fall back to [CLASSIC].
 */
enum class PetCharacter(val prefKey: String) {
    CLASSIC("classic"),
    DADA("dada"),
    HUHU("huhu"),
    BUBU("bubu"),
    HUIHUI("huihui");

    companion object {
        fun fromKey(key: String?): PetCharacter =
            entries.firstOrNull { it.prefKey == key } ?: CLASSIC
    }
}

/** Color palette per built-in sprite, used both by the overlay and the settings-page swatch. */
class PetPalette(
    val light: Int,
    val mid: Int,
    val dark: Int,
    val shadow: Int,
    val pupil: Int,
    val blush: Int,
    val accent: Int,
) {
    companion object {
        /** The four roles (Dada/Huhu/Bubu/Huihui) plus the improved classic bubble. */
        val CLASSIC = PetPalette(
            light = 0xFF93C5FD.toInt(),
            mid = 0xFF3B82F6.toInt(),
            dark = 0xFF1D4ED8.toInt(),
            shadow = 0xFF1E3A8A.toInt(),
            pupil = 0xFF1E3A8A.toInt(),
            blush = 0x33FF8FAB.toInt(),
            accent = 0xFF1D4ED8.toInt(),
        )
        val DADA = PetPalette(
            light = 0xFFC7D2FE.toInt(),
            mid = 0xFF818CF8.toInt(),
            dark = 0xFF4F46E5.toInt(),
            shadow = 0xFF3730A3.toInt(),
            pupil = 0xFF1E1B4B.toInt(),
            blush = 0x33FCA5A5.toInt(),
            accent = 0xFF312E81.toInt(),
        )
        val HUHU = PetPalette(
            light = 0xFF6EE7B7.toInt(),
            mid = 0xFF10B981.toInt(),
            dark = 0xFF047857.toInt(),
            shadow = 0xFF065F46.toInt(),
            pupil = 0xFF064E3B.toInt(),
            blush = 0x33FDE68A.toInt(),
            accent = 0xFF065F46.toInt(),
        )
        val BUBU = PetPalette(
            light = 0xFFFDBA74.toInt(),
            mid = 0xFFFB923C.toInt(),
            dark = 0xFFEA580C.toInt(),
            shadow = 0xFFC2410C.toInt(),
            pupil = 0xFF7C2D12.toInt(),
            blush = 0x33FCA5A5.toInt(),
            accent = 0xFF9A3412.toInt(),
        )
        val HUIHUI = PetPalette(
            light = 0xFFF9A8D4.toInt(),
            mid = 0xFFEC4899.toInt(),
            dark = 0xFFDB2777.toInt(),
            shadow = 0xFFBE185D.toInt(),
            pupil = 0xFF831843.toInt(),
            blush = 0x33FDBA74.toInt(),
            accent = 0xFF9D174D.toInt(),
        )

        fun of(character: PetCharacter): PetPalette = when (character) {
            PetCharacter.CLASSIC -> CLASSIC
            PetCharacter.DADA -> DADA
            PetCharacter.HUHU -> HUHU
            PetCharacter.BUBU -> BUBU
            PetCharacter.HUIHUI -> HUIHUI
        }

        /** Foreground body color used for a small preview swatch in the settings page. */
        fun swatch(character: PetCharacter): Int = of(character).mid
    }
}