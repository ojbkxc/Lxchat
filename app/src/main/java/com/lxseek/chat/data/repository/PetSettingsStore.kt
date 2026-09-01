package com.lxseek.chat.data.repository

import com.lxseek.chat.data.SettingsManager
import com.lxseek.chat.pet.PetCharacter
import com.lxseek.chat.pet.CustomPet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class PetSettingsStore(
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope,
    initialLoadSignals: MutableList<CompletableDeferred<Unit>>,
) {
    val petOverlayEnabled: StateFlow<Boolean> =
        sharedSettingsState(settingsManager.petOverlayEnabled, false, scope, initialLoadSignals)
    val petOverlayImagePath: StateFlow<String> =
        sharedSettingsState(settingsManager.petOverlayImagePath, "", scope, initialLoadSignals)
    val petOverlaySizeScale: StateFlow<Float> =
        sharedSettingsState(settingsManager.petOverlaySizeScale, 1.0f, scope, initialLoadSignals)
    val petOverlayAlpha: StateFlow<Float> =
        sharedSettingsState(settingsManager.petOverlayAlpha, 1.0f, scope, initialLoadSignals)
    val petOverlayCharacter: StateFlow<String> = sharedSettingsState(
        settingsManager.petOverlayCharacter,
        PetCharacter.HUHU.prefKey,
        scope,
        initialLoadSignals,
    )
    val petEmotionEnabled: StateFlow<Boolean> =
        sharedSettingsState(settingsManager.petEmotionEnabled, true, scope, initialLoadSignals)
    val petsLibrary: StateFlow<List<CustomPet>> =
        sharedSettingsState(settingsManager.petsLibrary, emptyList(), scope, initialLoadSignals)
    val activePetId: StateFlow<String> =
        sharedSettingsState(settingsManager.activePetId, "", scope, initialLoadSignals)
    val petPromptInjectionEnabled: StateFlow<Boolean> =
        sharedSettingsState(
            settingsManager.petPromptInjectionEnabled,
            true,
            scope,
            initialLoadSignals,
        )

    suspend fun savePetOverlayEnabled(enabled: Boolean) =
        settingsManager.savePetOverlayEnabled(enabled)
    suspend fun savePetOverlayImagePath(path: String) =
        settingsManager.savePetOverlayImagePath(path)
    suspend fun savePetOverlaySizeScale(scale: Float) =
        settingsManager.savePetOverlaySizeScale(scale)
    suspend fun savePetOverlayAlpha(alpha: Float) = settingsManager.savePetOverlayAlpha(alpha)
    suspend fun savePetOverlayCharacter(character: String) =
        settingsManager.savePetOverlayCharacter(character)
    suspend fun savePetEmotionEnabled(enabled: Boolean) =
        settingsManager.savePetEmotionEnabled(enabled)
    suspend fun savePetsLibrary(pets: List<CustomPet>) = settingsManager.savePetsLibrary(pets)
    suspend fun saveActivePetId(id: String) = settingsManager.saveActivePetId(id)
    suspend fun savePetPromptInjectionEnabled(enabled: Boolean) =
        settingsManager.savePetPromptInjectionEnabled(enabled)
}
