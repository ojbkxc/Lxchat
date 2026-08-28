package com.lxseek.chat.pet

import com.lxseek.chat.R

/**
 * The built-in floating-pet sprites. [CLASSIC] is the legacy Canvas-drawn bubble kept as an
 * internal fallback when a spritesheet fails to load; the four cc-haha roles each have a WebP
 * spritesheet used in settings.
 *
 * Loading priority: local cache (`filesDir/pets/<id>/`) → bundled [assetsPath] → [downloadUrl].
 * Only [HUHU] is bundled in the APK (to keep size small); the other three are downloaded on
 * first selection from [downloadUrl] and cached permanently under `filesDir/pets/<id>/`.
 *
 * The persisted preference stores [prefKey]; unknown values fall back to [HUHU].
 * Only spritesheet characters are shown in the settings picker (see [selectableEntries]).
 */
enum class PetCharacter(
    val prefKey: String,
    /** Relative path inside `assets/` to the bundled spritesheet WebP, or empty if not bundled. */
    val assetsPath: String,
    /** URL to download the spritesheet WebP when not bundled, or empty if bundled. */
    val downloadUrl: String,
    /** Drawable resource name (without extension) for the settings preview, or empty for [CLASSIC]. */
    val previewDrawableName: String,
    /** Drawable resource id for the settings preview, or 0 for [CLASSIC]. */
    val previewResId: Int,
) {
    CLASSIC("classic", "", "", "", 0),
    DADA("dada-code", "", "https://raw.githubusercontent.com/ojbkxc/Lxchat/main/pets-download/dada-code/spritesheet.webp", "pet_preview_dada", R.drawable.pet_preview_dada),
    HUHU("huhu-plan", "pets/huhu-plan/spritesheet.webp", "", "pet_preview_huhu", R.drawable.pet_preview_huhu),
    BUBU("bubu-fix", "", "https://raw.githubusercontent.com/ojbkxc/Lxchat/main/pets-download/bubu-fix/spritesheet.webp", "pet_preview_bubu", R.drawable.pet_preview_bubu),
    HUIHUI("huihui-build", "", "https://raw.githubusercontent.com/ojbkxc/Lxchat/main/pets-download/huihui-build/spritesheet.webp", "pet_preview_huihui", R.drawable.pet_preview_huihui);

    /** Whether this character has a real spritesheet (bundled or downloadable). */
    val hasSpritesheet: Boolean get() = assetsPath.isNotEmpty() || downloadUrl.isNotEmpty()

    /** Whether the spritesheet is bundled in the APK (vs. requiring a download). */
    val isBundled: Boolean get() = assetsPath.isNotEmpty()

    companion object {
        /** Characters shown in the settings picker (excludes the internal [CLASSIC] fallback). */
        val selectableEntries: List<PetCharacter> get() = entries.filter { it.hasSpritesheet }

        fun fromKey(key: String?): PetCharacter =
            entries.firstOrNull { it.prefKey == key } ?: HUHU
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
