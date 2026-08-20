package com.lxseek.chat.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SettingsResourceContractTest {
    @Test
    fun localizedStringKeysMatchDefaultResources() {
        val resourceDirectory = locateResourceDirectory()
        val defaultKeys = readStringKeys(File(resourceDirectory, "values"))
        val localeDirectoryPattern = Regex("""values-[a-z]{2,3}(?:-r[A-Z]{2})?""")
        val localeDirectories = resourceDirectory.listFiles()
            .orEmpty()
            .filter { it.isDirectory && localeDirectoryPattern.matches(it.name) }

        assertTrue("No localized resource directories found", localeDirectories.isNotEmpty())
        localeDirectories.forEach { localeDirectory ->
            val missingKeys = defaultKeys - readStringKeys(localeDirectory)
            assertTrue(
                "${localeDirectory.name} is missing: ${missingKeys.sorted().joinToString()}",
                missingKeys.isEmpty(),
            )
        }
    }

    @Test
    fun complexVectorTopologyIsPreserved() {
        val resourceDirectory = locateResourceDirectory()
        val drawableDirectory = File(resourceDirectory, "drawable")
        val deepSeekPaths = readVectorPaths(File(drawableDirectory, "provider_deepseek.xml"))
        val mcpPaths = readVectorPaths(File(drawableDirectory, "ic_mcp.xml"))

        assertEquals("DeepSeek vector path count changed unexpectedly", 1, deepSeekPaths.size)
        assertTrue(
            "DeepSeek paths must preserve the SVG even-odd fill rule",
            deepSeekPaths.all { it.fillType == "evenOdd" },
        )
        val deepSeekSourcePath = readSvgPathData(
            File(checkNotNull(resourceDirectory.parentFile), "assets/deepseek.svg"),
        )
        assertEquals(
            "DeepSeek VectorDrawable must use the canonical source SVG geometry",
            deepSeekSourcePath,
            deepSeekPaths.single().pathData,
        )
        assertArcFlagsAreSeparated(deepSeekPaths.single().pathData)
        val deepSeekInset = readSingleGroupTransform(
            File(drawableDirectory, "provider_deepseek.xml"),
        )
        assertEquals(12f, deepSeekInset.pivotX, 0f)
        assertEquals(12f, deepSeekInset.pivotY, 0f)
        assertEquals(0.96f, deepSeekInset.scaleX, 0f)
        assertEquals(0.96f, deepSeekInset.scaleY, 0f)

        assertEquals("MCP vector path count changed unexpectedly", 2, mcpPaths.size)
        assertTrue(
            "MCP paths must preserve the SVG even-odd fill rule",
            mcpPaths.all { it.fillType == "evenOdd" },
        )
        mcpPaths.forEach { path ->
            assertArcFlagsAreSeparated(path.pathData)
        }
    }

    private fun assertArcFlagsAreSeparated(pathData: String) {
        val number = """[+-]?(?:\d+(?:\.\d+)?|\.\d+)"""
        val arcCommand = Regex(
            """(?i)(?<![A-Za-z])a\s+$number\s+$number\s+$number\s+[01]\s+[01]\s+$number\s+$number""",
        )
        val arcMarker = Regex("""(?i)(?<![A-Za-z])a(?=\s)""")
        val commandCount = arcMarker.findAll(pathData).count()
        val normalizedCount = arcCommand.findAll(pathData).count()

        assertTrue("Expected at least one arc command in: $pathData", commandCount > 0)
        assertEquals(
            "Every arc command must use separate large-arc and sweep flag tokens",
            commandCount,
            normalizedCount,
        )
        assertFalse("Compact 00- arc flags are forbidden", pathData.contains("00-"))
        assertFalse("Compact 01- arc flags are forbidden", pathData.contains("01-"))
    }

    private fun readStringKeys(directory: File): Set<String> =
        directory.listFiles { file -> file.isFile && file.extension == "xml" }
            .orEmpty()
            .flatMap { file ->
                parse(file)
                    .getElementsByTagName("string")
                    .let { nodes ->
                        (0 until nodes.length).mapNotNull { index ->
                            (nodes.item(index) as? Element)
                                ?.getAttribute("name")
                                ?.takeIf(String::isNotBlank)
                        }
                    }
            }
            .toSet()

    private fun readSvgPathData(file: File): String {
        assertTrue("Missing SVG source: ${file.path}", file.isFile)
        val nodes = parse(file).getElementsByTagName("path")
        assertEquals("Expected one path in ${file.name}", 1, nodes.length)
        return (nodes.item(0) as Element).getAttribute("d")
    }

    private fun readVectorPaths(file: File): List<VectorPath> {
        assertTrue("Missing vector resource: ${file.path}", file.isFile)
        val nodes = parse(file).getElementsByTagName("path")
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as Element
            VectorPath(
                fillType = element.getAttributeNS(ANDROID_NAMESPACE, "fillType"),
                pathData = element.getAttributeNS(ANDROID_NAMESPACE, "pathData"),
            )
        }
    }

    private fun readSingleGroupTransform(file: File): GroupTransform {
        val nodes = parse(file).getElementsByTagName("group")
        assertEquals("Expected one safety-inset group in ${file.name}", 1, nodes.length)
        val element = nodes.item(0) as Element
        return GroupTransform(
            pivotX = element.androidFloat("pivotX"),
            pivotY = element.androidFloat("pivotY"),
            scaleX = element.androidFloat("scaleX"),
            scaleY = element.androidFloat("scaleY"),
        )
    }

    private fun Element.androidFloat(name: String): Float =
        getAttributeNS(ANDROID_NAMESPACE, name).toFloat()

    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

    private fun locateResourceDirectory(): File {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = checkNotNull(cursor)
            val candidates = listOf(
                File(directory, "app/src/main/res"),
                File(directory, "src/main/res"),
            )
            candidates.firstOrNull(File::isDirectory)?.let { return it }
            cursor = directory.parentFile
        }
        error("Unable to locate app/src/main/res from ${System.getProperty("user.dir")}")
    }

    private data class VectorPath(
        val fillType: String,
        val pathData: String,
    )

    private data class GroupTransform(
        val pivotX: Float,
        val pivotY: Float,
        val scaleX: Float,
        val scaleY: Float,
    )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
