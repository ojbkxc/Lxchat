package buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinSourceSizePolicyTest {
    @Test
    fun `999 lines pass without a baseline`() {
        assertTrue(evaluate(999).isEmpty())
    }

    @Test
    fun `1000 lines fail without a baseline`() {
        val violation = evaluate(1000).single()
        assertEquals(KotlinSourceSizeViolationReason.NEW_OVERSIZED_SOURCE, violation.reason)
        assertEquals(999, violation.allowedLines)
    }

    @Test
    fun `baseline file growth fails at its recorded cap`() {
        val violation = evaluate(current = 1201, baseline = 1200).single()
        assertEquals(KotlinSourceSizeViolationReason.BASELINE_GROWTH, violation.reason)
        assertEquals(1200, violation.allowedLines)
    }

    @Test
    fun `baseline file shrink remains accepted while still oversized`() {
        assertTrue(evaluate(current = 1100, baseline = 1200).isEmpty())
    }

    @Test
    fun `new oversized file cannot inherit another source baseline`() {
        val violations = KotlinSourceSizePolicy.evaluate(
            currentLines = mapOf("app/New.kt" to 1400, "app/Legacy.kt" to 1200),
            baselineLines = mapOf("app/Legacy.kt" to 1200),
        )
        assertEquals(1, violations.size)
        assertEquals("app/New.kt", violations.single().path)
        assertEquals(KotlinSourceSizeViolationReason.NEW_OVERSIZED_SOURCE, violations.single().reason)
    }

    @Test
    fun `baseline allowlist cannot add a path outside the frozen migration set`() {
        val violation = KotlinSourceSizePolicy.evaluate(
            currentLines = mapOf("app/New.kt" to 1400),
            baselineLines = mapOf("app/New.kt" to 1400),
            allowedBaselineCaps = mapOf("app/Legacy.kt" to 1500),
        ).single()
        assertEquals(KotlinSourceSizeViolationReason.INVALID_BASELINE, violation.reason)
        assertEquals(999, violation.allowedLines)
    }

    @Test
    fun `baseline cap cannot be raised above its frozen value`() {
        val violation = KotlinSourceSizePolicy.evaluate(
            currentLines = mapOf("app/Legacy.kt" to 1201),
            baselineLines = mapOf("app/Legacy.kt" to 1300),
            allowedBaselineCaps = mapOf("app/Legacy.kt" to 1200),
        ).single()
        assertEquals(KotlinSourceSizeViolationReason.INVALID_BASELINE, violation.reason)
        assertEquals(1200, violation.allowedLines)
    }

    @Test
    fun `generated cache build and vendored directories are excluded`() {
        listOf(
            "app/build/generated/Generated.kt",
            "build/cache/Cached.kt",
            ".gradle/kotlin/Accessor.kt",
            ".kotlin/sessions/State.kt",
            ".harness/runtime/Local.kt",
            ".claude/cache/Local.kt",
            "thirdparty/library/Vendored.kt",
        ).forEach { path -> assertTrue(path, KotlinSourceSizePolicy.isExcluded(path)) }
        assertFalse(
            KotlinSourceSizePolicy.isExcluded("app/src/main/java/com/example/Handwritten.kt"),
        )
    }

    @Test
    fun `Windows and Unix newlines have identical physical counts`() {
        assertEquals(3, KotlinSourceSizePolicy.countPhysicalLines("one\ntwo\nthree\n"))
        assertEquals(3, KotlinSourceSizePolicy.countPhysicalLines("one\r\ntwo\r\nthree\r\n"))
        assertEquals(3, KotlinSourceSizePolicy.countPhysicalLines("one\rtwo\rthree\r"))
        assertEquals(3, KotlinSourceSizePolicy.countPhysicalLines("one\ntwo\nthree"))
        assertEquals(0, KotlinSourceSizePolicy.countPhysicalLines(""))
    }

    private fun evaluate(current: Int, baseline: Int? = null): List<KotlinSourceSizeViolation> =
        KotlinSourceSizePolicy.evaluate(
            currentLines = mapOf("app/Test.kt" to current),
            baselineLines = baseline?.let { mapOf("app/Test.kt" to it) }.orEmpty(),
        )
}
