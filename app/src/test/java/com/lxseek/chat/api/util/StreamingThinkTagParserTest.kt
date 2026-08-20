package com.lxseek.chat.api.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StreamingThinkTagParserTest {

    private suspend fun collectFeeds(
        parser: StreamingThinkTagParser,
        feeds: List<String>,
        thinkingEnabled: Boolean
    ): Pair<String, String> {
        val textBuf = StringBuilder()
        val thoughtBuf = StringBuilder()
        for (feed in feeds) {
            parser.feed(feed, thinkingEnabled,
                onText = { textBuf.append(it) },
                onThought = { thoughtBuf.append(it) }
            )
        }
        parser.flush(
            onText = { textBuf.append(it) },
            onThought = { thoughtBuf.append(it) }
        )
        return textBuf.toString() to thoughtBuf.toString()
    }

    @Test
    fun noThinkTags() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(parser, listOf("Hello world"), true)
        assertEquals("Hello world", text)
        assertTrue(thought.isEmpty())
    }

    @Test
    fun singleThinkBlock() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(parser, listOf("before <think>reasoning</think> after"), true)
        assertEquals("before  after", text)
        assertEquals("reasoning", thought)
    }

    @Test
    fun multipleThinkBlocks_areAllParsed() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(parser,
            listOf("<think>first</think> middle <think>second</think> end"), true)
        assertEquals(" middle  end", text)
        assertEquals("firstsecond", thought)
    }

    @Test
    fun partialTagArrival() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(parser,
            listOf("hel", "lo <thi", "nk>ins", "ide</think> tail"), true)
        assertEquals("hello  tail", text)
        assertEquals("inside", thought)
    }

    @Test
    fun partialCloseTag() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(parser,
            listOf("<think>foo bar</t", "hink> end"), true)
        assertEquals(" end", text)
        assertEquals("foo bar", thought)
    }

    @Test
    fun thinkingDisabled_thoughtPassedToText() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(parser,
            listOf("before <think>inner</think> after"), false)
        assertEquals("before  after", text)
        assertTrue(thought.isEmpty())
    }

    @Test
    fun flush_emitsPendingBufferedOnPartialCloseTag() = runTest {
        val parser = StreamingThinkTagParser()
        val textBuf = StringBuilder()
        val thoughtBuf = StringBuilder()
        // Feed partial closing tag: parser buffers "inside</t" waiting for "hink>"
        parser.feed("<think>inside</t", true,
            onText = { textBuf.append(it) },
            onThought = { thoughtBuf.append(it) }
        )
        // "inside" was emitted as thought (complete chunk before partial tag), "</t" is buffered
        assertEquals("inside", thoughtBuf.toString())
        // flush should emit the remaining buffered "</t" as thought
        parser.flush(
            onText = { textBuf.append(it) },
            onThought = { thoughtBuf.append(it) }
        )
        // After flush, "</t" is emitted from the pending buffer
        assertEquals("inside</t", thoughtBuf.toString())
    }

    @Test
    fun emptyFeed_noEmission() = runTest {
        val parser = StreamingThinkTagParser()
        var emitted = false
        parser.feed("", true,
            onText = { emitted = true },
            onThought = { emitted = true }
        )
        assertFalse(emitted)
    }

    @Test
    fun tagAcrossMultipleFeeds() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(parser,
            listOf("a", "<think>", "b", "c", "</think>", "d"), true)
        assertEquals("ad", text)
        assertEquals("bc", thought)
    }

    @Test
    fun multipleSupportedXmlTagsAndMixedCase_areParsed() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(
            parser,
            listOf(
                "<THINKING>a</thinking>",
                "<reasoning>b</reasoning>",
                "<analysis>c</analysis>",
                "<thought>d</thought>",
                "answer",
            ),
            true,
        )

        assertEquals("answer", text)
        assertEquals("abcd", thought)
    }

    @Test
    fun harmonyChannelMarkersAcrossChunks_areParsed() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(
            parser,
            listOf(
                "<|start|>assistant<|channel|>anal",
                "ysis<|message|>reason",
                "<|channel|>final<|message|>answer",
            ),
            true,
        )

        assertEquals("answer", text)
        assertEquals("reason", thought)
    }

    @Test
    fun unknownAngleBracketTagsRemainLiteralText() = runTest {
        val parser = StreamingThinkTagParser()
        val source = "Use <widget id=\"x\">value</widget> and <T>."
        val (text, thought) = collectFeeds(
            parser,
            source.chunked(2),
            true,
        )

        assertEquals(source, text)
        assertTrue(thought.isEmpty())
    }

    @Test
    fun reservedTagsInsideInlineAndFencedCodeRemainLiteral() = runTest {
        val parser = StreamingThinkTagParser()
        val source = "`<think>inline</think>`\n```\n<analysis>code</analysis>\n```"
        val (text, thought) = collectFeeds(
            parser,
            source.map(Char::toString),
            true,
        )

        assertEquals(source, text)
        assertTrue(thought.isEmpty())
    }

    @Test
    fun incompleteOpeningTagAtEndOfStreamIsNotLost() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(parser, listOf("answer <thi"), true)

        assertEquals("answer <thi", text)
        assertTrue(thought.isEmpty())
    }

    @Test
    fun unclosedThinkingBlockFlushesItsContentAsThought() = runTest {
        val parser = StreamingThinkTagParser()
        val (text, thought) = collectFeeds(parser, listOf("<think>unfinished"), true)

        assertEquals("", text)
        assertEquals("unfinished", thought)
    }

    @Test
    fun everyTwoChunkBoundaryProducesTheSameResult() = runTest {
        val sources = listOf(
            "<think>reason</think>answer",
            "<thinking>reason</thinking>answer",
            "<reasoning>reason</reasoning>answer",
            "<analysis>reason</analysis>answer",
            "<thought>reason</thought>answer",
            "<|channel|>analysis<|message|>reason<|end|>answer",
            "<|start|>assistant<|channel|>reasoning<|message|>reason" +
                "<|channel|>final<|message|>answer",
        )

        sources.forEach { source ->
            for (split in 0..source.length) {
                val parser = StreamingThinkTagParser()
                val (text, thought) = collectFeeds(
                    parser,
                    listOf(source.substring(0, split), source.substring(split)),
                    true,
                )
                assertEquals("split=$split source=$source", "answer", text)
                assertEquals("split=$split source=$source", "reason", thought)
            }
        }
    }
}
