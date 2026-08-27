package com.lxseek.chat.ui.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Host that renders a [UiDslDocument] as a Compose component tree.
 *
 * Each [UiDslNode] is mapped to its corresponding Material3 / Foundation composable.
 * Interactive components (TextField, Switch, Slider) hold local state via [remember]
 * so the rendered UI is fully functional without external state plumbing.
 *
 * The [onAction] callback is invoked with the node's [UiDslNode.onClick] identifier
 * when the user interacts with an actionable node (Button click, Card click,
 * Switch toggle). A no-op default is provided for static previews.
 *
 * Style properties in [UiDslNode.props] are translated to a [Modifier] via
 * [modifierFromProps] (padding, width, height, fillMaxWidth, fillMaxHeight) and
 * to specific attributes (color, fontSize) where applicable.
 */
@Composable
fun ComposeDslHost(
    document: UiDslDocument,
    onAction: (String) -> Unit = {},
) {
    RenderNode(document.root, onAction)
}

/**
 * Renders a single [UiDslNode] and recurses into its children.
 */
@Composable
private fun RenderNode(node: UiDslNode, onAction: (String) -> Unit) {
    when (node.type) {
        UiDslComponentType.Column -> Column(modifier = modifierFromProps(node.props)) {
            node.children.forEach { RenderNode(it, onAction) }
        }

        UiDslComponentType.Row -> Row(modifier = modifierFromProps(node.props)) {
            node.children.forEach { RenderNode(it, onAction) }
        }

        UiDslComponentType.Text -> Text(
            text = node.content ?: "",
            modifier = modifierFromProps(node.props),
            color = parseColor(node.props["color"]) ?: Color.Unspecified,
            fontSize = node.props["fontSize"]?.toIntOrNull()?.sp ?: TextUnit.Unspecified,
        )

        UiDslComponentType.Button -> Button(
            onClick = { node.onClick?.let(onAction) },
            modifier = modifierFromProps(node.props),
        ) {
            Text(text = node.content ?: "")
        }

        UiDslComponentType.Image -> AsyncImage(
            model = node.props["src"],
            contentDescription = node.content,
            modifier = modifierFromProps(node.props),
        )

        UiDslComponentType.Spacer -> Spacer(
            modifier = Modifier.height(node.props["height"]?.toIntOrNull()?.dp ?: 8.dp),
        )

        UiDslComponentType.Card -> {
            val modifier = modifierFromProps(node.props)
            val clickAction = node.onClick
            if (clickAction != null) {
                Card(onClick = { onAction(clickAction) }, modifier = modifier) {
                    node.children.forEach { RenderNode(it, onAction) }
                }
            } else {
                Card(modifier = modifier) {
                    node.children.forEach { RenderNode(it, onAction) }
                }
            }
        }

        UiDslComponentType.TextField -> {
            var value by remember { mutableStateOf(node.content ?: "") }
            val placeholderText = node.props["placeholder"]
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = modifierFromProps(node.props),
                placeholder = if (placeholderText != null) {
                    { Text(placeholderText) }
                } else {
                    null
                },
            )
        }

        UiDslComponentType.Switch -> {
            var checked by remember {
                mutableStateOf(node.props["checked"]?.toBoolean() ?: false)
            }
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    node.onClick?.let(onAction)
                },
                modifier = modifierFromProps(node.props),
            )
        }

        UiDslComponentType.Slider -> {
            var value by remember {
                mutableStateOf(node.props["value"]?.toFloatOrNull() ?: 0f)
            }
            Slider(
                value = value,
                onValueChange = { value = it },
                modifier = modifierFromProps(node.props),
            )
        }

        // Restricted dropdown: rendered as a static column of option nodes.
        // Each child is rendered normally and may carry its own onClick action.
        UiDslComponentType.Dropdown -> Column(modifier = modifierFromProps(node.props)) {
            node.children.forEach { RenderNode(it, onAction) }
        }

        UiDslComponentType.LazyColumn -> LazyColumn(modifier = modifierFromProps(node.props)) {
            items(node.children) { child -> RenderNode(child, onAction) }
        }

        UiDslComponentType.Divider -> HorizontalDivider()
    }
}

/**
 * Translates common style [props] into a [Modifier]. Supported keys (all values
 * are strings parsed by the consumer):
 * - `padding`  — uniform padding in dp (Int).
 * - `width`    — fixed width in dp (Int).
 * - `height`   — fixed height in dp (Int).
 * - `fillMaxWidth`  — "true" to fill the maximum available width.
 * - `fillMaxHeight` — "true" to fill the maximum available height.
 *
 * Unknown keys are ignored so the DSL stays forward-compatible.
 */
private fun modifierFromProps(props: Map<String, String>): Modifier {
    var modifier = Modifier
    props["padding"]?.toIntOrNull()?.let { modifier = modifier.padding(it.dp) }
    props["width"]?.toIntOrNull()?.let { modifier = modifier.width(it.dp) }
    props["height"]?.toIntOrNull()?.let { modifier = modifier.height(it.dp) }
    if (props["fillMaxWidth"]?.toBoolean() == true) {
        modifier = modifier.fillMaxWidth()
    }
    if (props["fillMaxHeight"]?.toBoolean() == true) {
        modifier = modifier.fillMaxHeight()
    }
    return modifier
}

/**
 * Parses a hex color string (e.g. "#FF0000", "#FFFF0000") into a Compose [Color].
 * Returns `null` for blank or malformed input so the caller can keep the theme
 * default (e.g. [Color.Unspecified]).
 */
private fun parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: IllegalArgumentException) {
        null
    }
}