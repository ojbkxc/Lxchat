package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lxseek.chat.ui.theme.LxDesign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.pet.CustomPet
import com.lxseek.chat.pet.PetHatcher
import com.lxseek.chat.pet.PetPalette
import com.lxseek.chat.pet.PetRarity
import com.lxseek.chat.pet.PetSpecies
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * 「添加宠物」设置子页：参照 cc-haha 的 `/buddy hatch` 流程，让用户孵化一只独一无二的桌面宠物。
 *
 * 流程：随机一个 seed → 用 [PetHatcher.roll] 生成外观/属性 → 用户编辑名字与人设 →
 * 存入 `pets_library_json` 并自动设为 active pet。
 *
 * 同时展示已有宠物列表，可切换 active / 放生。预览图复用内置 [PetCharacter] 的 preview drawable
 * （由 [PetSpecies.previewCharacter] 映射，无需新增美术资源）。
 */
@Composable
fun SettingsAddPetPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pets by viewModel.settings.petsLibrary.collectAsState()
    val activePetId by viewModel.settings.activePetId.collectAsState()
    val injectionEnabled by viewModel.settings.petPromptInjectionEnabled.collectAsState()

    // ── 孵化草稿 ──
    var seed by remember { mutableStateOf(PetHatcher.newSeed()) }
    var draft by remember(seed) { mutableStateOf(PetHatcher.roll(seed, PetHatcher.randomName(), "")) }
    var nameDraft by remember(draft.id) { mutableStateOf(draft.name) }
    var personalityDraft by remember(draft.id) { mutableStateOf(draft.personality) }

    LaunchedEffect(draft.id) {
        if (personalityDraft.isBlank()) {
            personalityDraft = PetHatcher.defaultPersonality(draft.rarity, draft.species)
        }
    }

    fun reroll() {
        seed = PetHatcher.newSeed()
    }

    fun commitHatch() {
        val finalName = nameDraft.trim().ifBlank { PetHatcher.randomName() }
        val finalPersonality = personalityDraft.trim().ifBlank {
            PetHatcher.defaultPersonality(draft.rarity, draft.species)
        }
        val newPet = draft.copy(name = finalName, personality = finalPersonality)
        scope.launch {
            val next = pets + newPet
            viewModel.settings.savePetsLibrary(next)
            viewModel.settings.saveActivePetId(newPet.id)
        }
        onBack()
    }

    fun activate(petId: String) {
        scope.launch { viewModel.settings.saveActivePetId(petId) }
    }

    fun release(pet: CustomPet) {
        scope.launch {
            val next = pets.filterNot { it.id == pet.id }
            viewModel.settings.savePetsLibrary(next)
            if (activePetId == pet.id) {
                viewModel.settings.saveActivePetId(next.firstOrNull()?.id ?: "")
            }
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.pet_add_title),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            Text(
                text = stringResource(R.string.pet_add_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )

        // ── 孵化预览卡 ──
        SettingsGroup(title = stringResource(R.string.pet_hatch_action), items = listOf({
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PetPreview(pet = draft, sizeDp = 72.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${draft.rarity.stars} ${rarityLabel(draft.rarity)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = rarityColor(draft.rarity),
                        )
                        Text(
                            text = "${speciesLabel(draft.species)} · ${draft.eye.glyph} · ${draft.hat.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (draft.shiny) {
                            Text(
                                text = stringResource(R.string.pet_shiny),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    OutlinedButton(onClick = ::reroll) {
                        Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.pet_hatch_again))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text(stringResource(R.string.pet_edit_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = personalityDraft,
                    onValueChange = { personalityDraft = it },
                    label = { Text(stringResource(R.string.pet_edit_personality)) },
                    supportingText = { Text(stringResource(R.string.pet_edit_personality_desc)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))
                StatsRow(draft)

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = ::commitHatch,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.pet_hatch_confirm, nameDraft.ifBlank { draft.name }))
                }
            }
        }))

        // ── 注入开关 ──
        SettingsGroup(title = stringResource(R.string.pet_prompt_injection), items = listOf({
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.pet_prompt_injection)) },
                supportingContent = { Text(stringResource(R.string.pet_prompt_injection_desc)) },
                leadingContent = {
                    Icon(
                        Icons.Default.Pets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = injectionEnabled,
                        onCheckedChange = { v ->
                            scope.launch { viewModel.settings.savePetPromptInjectionEnabled(v) }
                        },
                    )
                },
                modifier = Modifier.clickable {
                    scope.launch { viewModel.settings.savePetPromptInjectionEnabled(!injectionEnabled) }
                },
            )
        }))

        // ── 已有宠物列表 ──
        if (pets.isNotEmpty()) {
            SettingsGroup(
                title = stringResource(R.string.pet_library_title),
                items = pets.map { pet ->
                    @Composable {
                        PetLibraryRow(
                            pet = pet,
                            isActive = pet.id == activePetId,
                            onActivate = { activate(pet.id) },
                            onRelease = { release(pet) },
                        )
                    }
                },
            )
        } else {
            Text(
                text = stringResource(R.string.pet_library_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        }  // SettingsGroupColumn
    }
}

@Composable
private fun PetPreview(pet: CustomPet, sizeDp: androidx.compose.ui.unit.Dp) {
    val previewRes = pet.species.previewCharacter.previewResId
    Surface(
        modifier = Modifier.size(sizeDp).clip(RoundedCornerShape(LxDesign.cornerS)),
        color = Color(PetPalette.of(pet.species.previewCharacter).accent).copy(alpha = 0.12f),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.Image(
                painter = painterResource(previewRes),
                contentDescription = null,
                modifier = Modifier.size(sizeDp).clip(RoundedCornerShape(LxDesign.cornerS)),
            )
        }
    }
}

@Composable
private fun StatsRow(pet: CustomPet) {
    val stats = listOf(
        "DEBUGGING" to pet.stats.debugging,
        "PATIENCE" to pet.stats.patience,
        "CHAOS" to pet.stats.chaos,
        "WISDOM" to pet.stats.wisdom,
        "SNARK" to pet.stats.snark,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.pet_field_stats),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        stats.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(96.dp),
                )
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PetLibraryRow(
    pet: CustomPet,
    isActive: Boolean,
    onActivate: () -> Unit,
    onRelease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PetPreview(pet = pet, sizeDp = 44.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pet.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${pet.rarity.stars} ${speciesLabel(pet.species)} · ${pet.personality.take(40)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isActive) {
            Text(
                text = "★",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        OutlinedButton(
            onClick = onActivate,
            enabled = !isActive,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) { Text(stringResource(R.string.pet_activate)) }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = onRelease,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun rarityColor(rarity: PetRarity): Color = when (rarity) {
    PetRarity.COMMON -> MaterialTheme.colorScheme.onSurfaceVariant
    PetRarity.UNCOMMON -> MaterialTheme.colorScheme.primary
    PetRarity.RARE -> MaterialTheme.colorScheme.tertiary
    PetRarity.EPIC -> MaterialTheme.colorScheme.secondary
    PetRarity.LEGENDARY -> MaterialTheme.colorScheme.error
}

private fun rarityLabel(rarity: PetRarity): String = rarity.name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun speciesLabel(species: PetSpecies): String = stringResource(
    when (species) {
        PetSpecies.DUCK -> R.string.pet_species_duck
        PetSpecies.CAT -> R.string.pet_species_cat
        PetSpecies.DRAGON -> R.string.pet_species_dragon
        PetSpecies.ROBOT -> R.string.pet_species_robot
        PetSpecies.GHOST -> R.string.pet_species_ghost
        PetSpecies.BLOB -> R.string.pet_species_blob
        PetSpecies.RABBIT -> R.string.pet_species_rabbit
        PetSpecies.OWL -> R.string.pet_species_owl
    }
)
