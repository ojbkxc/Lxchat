package com.lxseek.chat.pet

/**
 * The built-in floating-pet sprites. [CLASSIC] is the legacy Canvas-drawn bubble kept as a
 * fallback when a spritesheet fails to load; the four cc-haha roles each reference a WebP
 * spritesheet in `assets/pets/<id>/spritesheet.webp` and a preview drawable used in settings.
 *
 * The persisted preference stores [prefKey]; unknown values fall back to [CLASSIC].
 */
enum class PetCharacter(
    val prefKey: String,
    /** Relative path inside `assets/` to the spritesheet WebP, or empty for [CLASSIC]. */
    val assetsPath: String,
    /** Drawable resource name (without extension) for the settings preview, or empty for [CLASSIC]. */
    val previewDrawableName: String,
) {
    CLASSIC("classic", "", ""),
    DADA("dada-code", "pets/dada-code/spritesheet.webp", "pet_preview_dada"),
    HUHU("huhu-plan", "pets/huhu-plan/spritesheet.webp", "pet_preview_huhu"),
    BUBU("bubu-fix", "pets/bubu-fix/spritesheet.webp", "pet_preview_bubu"),
    HUIHUI("huihui-build", "pets/huihui-build/spritesheet.webp", "pet_preview_huihui");

    /** Whether this character has a real spritesheet asset (vs. the Canvas fallback). */
    val hasSpritesheet: Boolean get() = assetsPath.isNotEmpty()

    companion object {
        fun fromKey(key: String?): PetCharacter =
            entries.firstOrNull { it.prefKey == key } ?: CLASSIC
    }
}

/**
 * Color palette per built-in sprite. The [accent] field matches cc-haha's brand colors and is
 * used for the tip-bubble, selection ring, and fallback rendering. The remaining fields are only
 * used by the [PetCharacter.CLASSIC] Canvas fallback and kept for backward compatibility.
 */
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
        /** The legacy Canvas-drawn bubble palette. */
        val CLASSIC = PetPalette(
            light = 0xFF93C5FD.toInt(),
            mid = 0xFF3B82F6.toInt(),
            dark = 0xFF1D4ED8.toInt(),
            shadow = 0xFF1E3A8A.toInt(),
            pupil = 0xFF1E3A8A.toInt(),
            blush = 0x33FF8FAB.toInt(),
            accent = 0xFF1D4ED8.toInt(),
        )
        // cc-haha accent colors: #4fd1b6, #6ea8ff, #ff9a76, #9b8cff.
        // Full palettes are derived from the accent for fallback shading.
        val DADA = PetPalette(
            light = 0xFFB3F0E0.toInt(),
            mid = 0xFF4FD1B6.toInt(),
            dark = 0xFF2EB89E.toInt(),
            shadow = 0xFF1F8F7B.toInt(),
            pupil = 0xFF134E42.toInt(),
            blush = 0x33FCA5A5.toInt(),
            accent = 0xFF4FD1B6.toInt(),
        )
        val HUHU = PetPalette(
            light = 0xFFB8D4FF.toInt(),
            mid = 0xFF6EA8FF.toInt(),
            dark = 0xFF4A8AE8.toInt(),
            shadow = 0xFF2E6FCC.toInt(),
            pupil = 0xFF1E4A8F.toInt(),
            blush = 0x33FDE68A.toInt(),
            accent = 0xFF6EA8FF.toInt(),
        )
        val BUBU = PetPalette(
            light = 0xFFFFCBAA.toInt(),
            mid = 0xFFFF9A76.toInt(),
            dark = 0xFFE87A52.toInt(),
            shadow = 0xFFCC5E36.toInt(),
            pupil = 0xFF7C2D12.toInt(),
            blush = 0x33FCA5A5.toInt(),
            accent = 0xFFFF9A76.toInt(),
        )
        val HUIHUI = PetPalette(
            light = 0xFFC9BCFF.toInt(),
            mid = 0xFF9B8CFF.toInt(),
            dark = 0xFF7A6BE6.toInt(),
            shadow = 0xFF5E50CC.toInt(),
            pupil = 0xFF3D2F8F.toInt(),
            blush = 0x33FDBA74.toInt(),
            accent = 0xFF9B8CFF.toInt(),
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
