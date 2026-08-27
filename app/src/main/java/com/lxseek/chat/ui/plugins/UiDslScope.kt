package com.lxseek.chat.ui.plugins

import kotlinx.serialization.Serializable

/**
 * Supported UI component types in the restricted Compose DSL.
 *
 * Each enum constant maps 1:1 to a Compose / Material3 composable inside
 * [ComposeDslHost]. The set is intentionally small ("restricted") so that
 * untrusted JSON documents can only describe a safe subset of UI trees.
 */
enum class UiDslComponentType {
    Column,
    Row,
    Text,
    Button,
    Image,
    Spacer,
    Card,
    TextField,
    Switch,
    Slider,
    Dropdown,
    LazyColumn,
    Divider,
}

/**
 * A single UI node in the DSL tree.
 *
 * @property type     The Compose component to render.
 * @property content  Optional text content (used by Text, Button, TextField, Image description).
 * @property children Nested nodes rendered inside this node (Column, Row, Card, …).
 * @property props    Style properties as strings (padding, color, width, height, …).
 *                    Kept as [String] so the whole document is serialisable with
 *                    kotlinx.serialization without custom polymorphic adapters.
 * @property onClick  Optional action identifier forwarded to [ComposeDslHost]'s
 *                    `onAction` callback when the user interacts with the node.
 */
@Serializable
data class UiDslNode(
    val type: UiDslComponentType,
    val content: String? = null,
    val children: List<UiDslNode> = emptyList(),
    val props: Map<String, String> = emptyMap(),
    val onClick: String? = null,
)

/**
 * Root DSL document.
 *
 * @property version DSL schema version (defaults to "1.0").
 * @property root    The top-level [UiDslNode] to render.
 */
@Serializable
data class UiDslDocument(
    val version: String = "1.0",
    val root: UiDslNode,
)