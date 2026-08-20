package com.lxseek.chat.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableGlobMatcherTest {
    @Test
    fun doubleStarMatchesZeroOrManyDirectories() {
        assertTrue(PortableGlobMatcher.matches("/work/**/*.kt", "/work/Main.kt"))
        assertTrue(PortableGlobMatcher.matches("/work/**/*.kt", "/work/src/ui/Main.kt"))
        assertFalse(PortableGlobMatcher.matches("/work/**/*.kt", "/work/src/Main.java"))
    }

    @Test
    fun singleStarQuestionAndClassStayInsideAPathSegment() {
        assertTrue(PortableGlobMatcher.matches("/tmp/file-?.k[tx]x", "/tmp/file-a.ktx"))
        assertFalse(PortableGlobMatcher.matches("/tmp/*.txt", "/tmp/nested/file.txt"))
        assertTrue(PortableGlobMatcher.matches("/tmp/[!a]*.txt", "/tmp/beta.txt"))
        assertFalse(PortableGlobMatcher.matches("/tmp/[!a]*.txt", "/tmp/alpha.txt"))
    }
}
