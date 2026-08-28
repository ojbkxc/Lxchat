package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import kotlin.math.roundToInt

/**
 * Generation parameter slider row.
 * Always shows the slider value. When at default, value is grey and "Default" text is shown beside it.
 * When set, value is primary-colored with a "Reset" link below the slider.
 */
@Composable
internal fun GenParamSlider(
    label: String,
    desc: String,
    value: Float?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    val defaultSliderPos = (valueRange.start + valueRange.endInclusive) / 2f
    val persistedSliderPos = value ?: defaultSliderPos
    var sliderPos by remember { mutableFloatStateOf(persistedSliderPos) }
    LaunchedEffect(persistedSliderPos) {
        sliderPos = persistedSliderPos
    }
    // Reset is reflected synchronously; only the DataStore write is async. justReset
    // flips the label to "not specified" immediately and is cleared once the async
    // [value] catches up (becomes null on reset, or a new value if the user re-sets).
    var justReset by remember { mutableStateOf(false) }
    LaunchedEffect(value) { justReset = false }
    val draftChangedFromDefault = kotlin.math.abs(sliderPos - defaultSliderPos) > 0.0001f
    val hasExplicitOrDraftValue = (value != null && !justReset) || draftChangedFromDefault
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (!hasExplicitOrDraftValue) {
                        Text(
                            text = stringResource(R.string.gen_not_specified),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    } else {
                        Text(
                            text = format(sliderPos),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.gen_reset),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.clickable {
                                sliderPos = defaultSliderPos
                                justReset = true
                                onReset()
                            }
                        )
                    }
                }
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Slider(
                    value = sliderPos,
                    onValueChange = { sliderPos = it },
                    onValueChangeFinished = {
                        val committed = sliderPos.coerceIn(valueRange.start, valueRange.endInclusive)
                        val shouldCommit = value != null || kotlin.math.abs(committed - defaultSliderPos) > 0.0001f
                        sliderPos = committed
                        if (shouldCommit) {
                            if (value == null || kotlin.math.abs(value - committed) > 0.0001f) {
                                onValueChange(committed)
                            }
                        }
                    },
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}

/** Int slider variant with discrete preset values (used for max tokens). */
@Composable
internal fun GenParamSlider(
    label: String,
    desc: String,
    value: Int?,
    presets: IntArray,
    format: (Int) -> String,
    onValueChange: (Int) -> Unit,
    onReset: () -> Unit
) {
    fun toIndex(v: Int) = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - v) } ?: 3
    val defaultIndex = 3.coerceIn(0, presets.lastIndex)
    val persistedIndex = if (value != null) toIndex(value) else defaultIndex
    var sliderPos by remember { mutableFloatStateOf(persistedIndex.toFloat()) }
    LaunchedEffect(persistedIndex) {
        sliderPos = persistedIndex.toFloat()
    }
    // Reset is reflected synchronously; only the DataStore write is async (see float variant).
    var justReset by remember { mutableStateOf(false) }
    LaunchedEffect(value) { justReset = false }
    val draftIndex = sliderPos.roundToInt().coerceIn(0, presets.lastIndex)
    val hasExplicitOrDraftValue = (value != null && !justReset) || draftIndex != defaultIndex
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (!hasExplicitOrDraftValue) {
                        Text(
                            text = stringResource(R.string.gen_not_specified),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    } else {
                        Text(
                            text = format(presets[draftIndex]),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.gen_reset),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.clickable {
                                sliderPos = defaultIndex.toFloat()
                                justReset = true
                                onReset()
                            }
                        )
                    }
                }
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Slider(
                    value = sliderPos,
                    onValueChange = { sliderPos = it },
                    onValueChangeFinished = {
                        val committedIndex = sliderPos.roundToInt().coerceIn(0, presets.lastIndex)
                        val committedValue = presets[committedIndex]
                        val shouldCommit = value != null || committedIndex != defaultIndex
                        sliderPos = committedIndex.toFloat()
                        if (shouldCommit) {
                            if (value != committedValue) {
                                onValueChange(committedValue)
                            }
                        }
                    },
                    valueRange = 0f..(presets.size - 1).toFloat(),
                    steps = presets.size - 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}