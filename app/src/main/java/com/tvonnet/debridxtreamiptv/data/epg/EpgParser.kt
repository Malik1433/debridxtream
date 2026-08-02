package com.tvonnet.debridxtreamiptv.data.epg

import android.content.Context
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.model.EpgProgram
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.io.Reader
import java.io.StringReader

object EpgParser {
    private const val TAG = "EpgParser"
    private const val DEFAULT_XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    private const val PROBE_SIZE_CHARS = 4096

    private lateinit var memoryManager: MemoryManager
    private var parsingJob: Job? = null

    fun initialize(context: Context) {
        memoryManager = MemoryManager.getInstance(context)
        memoryManager.addMemoryPressureCallback(object : MemoryManager.MemoryPressureCallback {
            override suspend fun onMemoryPressure(level: MemoryManager.MemoryPressure) {
                // Only cancel on genuine APP-HEAP exhaustion. The incoming [level] is
                // system-memory pressure, which on Android TV is CRITICAL as a normal
                // steady state — cancelling on it truncated EPG syncs mid-parse.
                if (level == MemoryManager.MemoryPressure.CRITICAL &&
                    memoryManager.appHeapPressure() == MemoryManager.MemoryPressure.CRITICAL
                ) {
                    Log.w(TAG, "App-heap critical, cancelling EPG parsing")
                    parsingJob?.cancel()
                }
            }
        })
    }

    suspend fun parse(xmlContent: String): Map<String, List<EpgProgram>> {
        // Check if content is too large for memory
        val maxSize = memoryManager.getMaxParsingSize()
        if (xmlContent.length > maxSize) {
            Log.e(TAG, "XML content too large: ${xmlContent.length} bytes > $maxSize bytes")
            return emptyMap()
        }

        return try {
            parse(StringReader(xmlContent))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "EPG parsing failed due to memory constraints. XML content too large.", e)
            memoryManager.requestGarbageCollection()
            emptyMap()
        } catch (e: CancellationException) {
            // Deliberately NOT rethrown: under heap pressure the parse is cancelled on purpose and we
            // degrade to "no EPG this round" (the generational replace keeps the existing rows).
            Log.i(TAG, "EPG parsing cancelled due to memory pressure", e)
            emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "EPG parsing failed", e)
            emptyMap()
        }
    }

    suspend fun parseInputStream(inputStream: InputStream): Map<String, List<EpgProgram>> {
        return try {
            parse(InputStreamReader(inputStream, Charsets.UTF_8))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "EPG parsing failed due to memory constraints.", e)
            memoryManager.requestGarbageCollection()
            emptyMap()
        } catch (e: CancellationException) {
            // See parseString(): cancellation here is the intentional heap-pressure abort, not a bug.
            Log.i(TAG, "EPG parsing cancelled due to memory pressure", e)
            emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "EPG parsing failed", e)
            emptyMap()
        }
    }

    suspend fun parse(reader: Reader): Map<String, List<EpgProgram>> {
        val epgMap = mutableMapOf<String, MutableList<EpgProgram>>()
        parseStream(reader) { program ->
            val list = epgMap.getOrPut(program.channelId) { mutableListOf() }
            list.add(program)
        }
        return epgMap
    }

    /**
     * Stream XML and invoke callback for every programme parsed.
     * Uses streaming parser with minimal memory footprint.
     * Returns the total number of programmes encountered.
     */
    suspend fun parseStream(reader: Reader, onProgram: suspend (EpgProgram) -> Unit): Int {
        val counts = StreamCounts()

        // Phase 4: launch as a CHILD of the caller (coroutineContext) rather than a brand-new
        // detached CoroutineScope — otherwise cancelling the caller left this parse running
        // (a detached-scope leak). Still stored in parsingJob for the explicit external cancel.
        parsingJob = CoroutineScope(coroutineContext + Dispatchers.IO).launch {
            try {
                runParseLoop(createStreamingParser(reader), onProgram, counts)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "EPG parsing failed due to memory constraints", e)
                memoryManager.requestGarbageCollection()
            } catch (e: CancellationException) {
                // Intentional heap-pressure abort (see parseString) — degrade, don't crash the sync.
                Log.i(TAG, "EPG parsing cancelled", e)
            } catch (e: Exception) {
                Log.e(TAG, "EPG parsing failed", e)
            }
        }

        try {
            parsingJob?.join()
        } catch (e: CancellationException) {
            Log.i(TAG, "EPG parsing join cancelled", e)
            throw e // our own caller was cancelled — propagate
        }

        return counts.programs
    }

    /** Mutable tallies for one parseStream run (written only inside the parse coroutine). */
    private class StreamCounts {
        var programs = 0
        var skippedInvalidTags = 0
    }

    // The pull-event loop, verbatim from parseStream: tag errors recover in place (skip one
    // event), an unrecoverable parser state ends the loop and keeps what was parsed so far.
    private suspend fun runParseLoop(
        parser: XmlPullParser,
        onProgram: suspend (EpgProgram) -> Unit,
        counts: StreamCounts
    ) {
        var eventType = parser.eventType
        var currentProgram: EpgProgram? = null

        while (eventType != XmlPullParser.END_DOCUMENT && coroutineContext.isActive) {
            try {
                currentProgram = handleEvent(parser, eventType, currentProgram, onProgram, counts)
                eventType = parser.next()

            } catch (tagException: Exception) {
                Log.w(TAG, "Error parsing tag, continuing...", tagException)
                eventType = recoverOrEnd(parser) ?: break
            }
        }

        if (counts.skippedInvalidTags > 0) {
            Log.i(
                TAG,
                "EPG parsing completed: ${counts.programs} programs, skipped ${counts.skippedInvalidTags} invalid tags"
            )
        }
    }

    // One pull event, verbatim: START_TAG updates the in-flight programme, a closing
    // programme tag emits it (and resets), everything else passes the current one through.
    private suspend fun handleEvent(
        parser: XmlPullParser,
        eventType: Int,
        currentProgram: EpgProgram?,
        onProgram: suspend (EpgProgram) -> Unit,
        counts: StreamCounts
    ): EpgProgram? {
        if (eventType == XmlPullParser.START_TAG) return handleStartTag(parser, currentProgram, counts)
        if (eventType != XmlPullParser.END_TAG || parser.name != "programme") return currentProgram
        currentProgram?.let { emitProgramme(it, onProgram, counts) }
        return null
    }

    // One recovery step after a tag error: the next event, or null when the parser is wedged
    // (the loop then ends and keeps everything parsed so far).
    private fun recoverOrEnd(parser: XmlPullParser): Int? = try {
        parser.next()
    } catch (recoveryException: Exception) {
        Log.e(TAG, "Failed to recover from parsing error", recoveryException)
        null
    }

    private fun createStreamingParser(reader: Reader): XmlPullParser {
        val chunkSize = memoryManager.getOptimalChunkSize()
        val bufferedReader = BufferedReader(reader, chunkSize)
        val xmlFactory = XmlPullParserFactory.newInstance()
        xmlFactory.isNamespaceAware = false
        val parser = xmlFactory.newPullParser()

        // Create a custom Reader that processes XML declarations on-the-fly
        val processedReader = createXmlDeclarationFixingReader(bufferedReader)
        parser.setInput(processedReader)
        return parser
    }

    // One START_TAG, verbatim from the parse loop: returns the updated in-flight programme.
    private fun handleStartTag(
        parser: XmlPullParser,
        currentProgram: EpgProgram?,
        counts: StreamCounts
    ): EpgProgram? {
        when (parser.name) {
            "programme" -> {
                return EpgProgram(
                    channelId = parser.getAttributeValue(null, "channel") ?: "",
                    start = parseTimestamp(parser.getAttributeValue(null, "start")),
                    stop = parseTimestamp(parser.getAttributeValue(null, "stop")),
                    title = null,
                    desc = null,
                    category = null
                )
            }
            "title" -> {
                val titleText = parser.nextText()
                return currentProgram?.copy(title = cleanTextContent(titleText))
            }
            "desc" -> {
                val descText = parser.nextText()
                return currentProgram?.copy(desc = cleanTextContent(descText))
            }
            "category" -> {
                val categoryText = parser.nextText()
                return currentProgram?.copy(category = cleanTextContent(categoryText))
            }
            // Skip invalid HTML/JavaScript tags
            "a", "script", "style", "noscript", "iframe", "object", "embed" -> {
                Log.w(TAG, "Skipping invalid HTML tag: ${parser.name}")
                counts.skippedInvalidTags++
                skipToEndTag(parser, parser.name)
            }
        }
        return currentProgram
    }

    // A closed programme, verbatim from the parse loop: validate, default the stop window, emit.
    private suspend fun emitProgramme(
        program: EpgProgram,
        onProgram: suspend (EpgProgram) -> Unit,
        counts: StreamCounts
    ) {
        // Require a real start. parseTimestamp returns -1L for
        // malformed input; adding those previously fabricated a
        // 'now' timestamp and injected bogus "now-airing" rows.
        if (program.channelId.isEmpty() || program.start <= 0L) return
        // Valid start but missing/invalid stop: give a 1h default
        // window rather than dropping the programme wholesale.
        val safeProgram = if (program.stop <= program.start)
            program.copy(stop = program.start + 3_600_000L)
        else program
        onProgram(safeProgram)
        counts.programs++

        // Yield to avoid blocking and allow cancellation
        yield()

        // Periodically relieve APP-HEAP pressure, but
        // never abort on system-memory pressure (normal
        // on TV) — aborting drops every remaining channel.
        if (counts.programs % 100 == 0) {
            if (memoryManager.appHeapPressure() == MemoryManager.MemoryPressure.CRITICAL) {
                Log.w(TAG, "App-heap critical during parse; requesting GC and continuing")
                memoryManager.requestGarbageCollection()
            }
        }
    }

    /**
     * Creates a Reader that fixes XML declaration issues on-the-fly
     */
    private fun createXmlDeclarationFixingReader(reader: BufferedReader): Reader {
        // NOTE: Previous implementation used readLine() which can load huge "single-line" XMLTV files into memory,
        // causing OOM (common on TV devices). This version is truly streaming and never buffers the full document.

        val pushbackReader = PushbackReader(reader, PROBE_SIZE_CHARS)

        val probe = CharArray(PROBE_SIZE_CHARS)
        val probeRead = pushbackReader.read(probe).coerceAtLeast(0)
        if (probeRead > 0) {
            pushbackReader.unread(probe, 0, probeRead)
        }

        val (hasXmlDeclAtStart, needsXmlDecl) = detectDeclarationState(probe, probeRead)
        val strippingReader = XmlDeclarationStrippingReader(
            source = pushbackReader,
            initialSeenXmlDeclaration = hasXmlDeclAtStart || needsXmlDecl
        )

        return if (needsXmlDecl) {
            Log.w(TAG, "Missing XML declaration, adding in streaming mode...")
            ChainedReader(StringReader("$DEFAULT_XML_DECLARATION\n"), strippingReader)
        } else {
            strippingReader
        }
    }

    private fun detectDeclarationState(sample: CharArray, length: Int): Pair<Boolean, Boolean> {
        if (length <= 0) return false to false
        var i = 0
        // Skip BOM and leading whitespace
        while (i < length) {
            val c = sample[i]
            if (c == '\uFEFF' || c == '\u0000') {
                i++
                continue
            }
            if (!c.isWhitespace()) break
            i++
        }
        if (i >= length) return false to false

        // XML declaration must appear at the very start of the document (ignoring BOM).
        val hasXmlDeclAtStart = startsWithIgnoreCase(sample, length, i, "<?xml")
        val firstIsTagStart = sample[i] == '<'
        val needsXmlDecl = firstIsTagStart && !hasXmlDeclAtStart
        return hasXmlDeclAtStart to needsXmlDecl
    }

    private fun startsWithIgnoreCase(sample: CharArray, length: Int, start: Int, prefix: String): Boolean {
        if (start + prefix.length > length) return false
        for (j in prefix.indices) {
            val a = sample[start + j].lowercaseChar()
            val b = prefix[j].lowercaseChar()
            if (a != b) return false
        }
        return true
    }

    /**
     * Streaming reader that strips *extra* XML declarations (<?xml ... ?>) found after the first one.
     * This prevents XmlPullParser from failing on broken providers while keeping memory usage minimal.
     */
    private class XmlDeclarationStrippingReader(
        private val source: Reader,
        initialSeenXmlDeclaration: Boolean
    ) : Reader() {

        private var seenXmlDeclaration: Boolean = initialSeenXmlDeclaration
        private var isStart: Boolean = true
        private val pending = CharArray(16)
        private var pendingLen = 0
        private var pendingPos = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (len == 0) return 0

            var out = off
            var remaining = len

            while (remaining > 0) {
                val fromPending = drainPending(cbuf, out, remaining)
                if (fromPending > 0) {
                    out += fromPending
                    remaining -= fromPending
                    continue
                }

                val chInt = source.read()
                if (chInt == -1) break
                var ch = chInt.toChar()

                // Drop BOM at stream start.
                if (isStart && ch == '\uFEFF') continue
                if (!ch.isWhitespace()) isStart = false

                if (ch != '<') {
                    cbuf[out++] = ch
                    remaining--
                    continue
                }

                // Rare path — a tag start. Queues into pending; drained at the top of the loop.
                queueTagStart()
            }

            val written = out - off
            return if (written == 0) -1 else written
        }

        // A '<' was read: a normal tag queues both chars verbatim; "<?" classifies the
        // processing instruction (keep the first XML declaration, strip duplicates).
        // At end-of-stream the lone '<' is still emitted, as before.
        private fun queueTagStart() {
            val nextInt = source.read()
            if (nextInt == -1) {
                pushPending('<')
                return
            }
            val next = nextInt.toChar()
            if (next != '?') {
                pushPending('<')
                pushPending(next)
                return
            }
            handleProcessingInstruction()
        }

        // We have read "<?": look for "<?xml" (case-insensitive) and either queue the
        // instruction into pending (drained by the read loop) or skip a duplicate XML
        // declaration entirely. Verbatim from read().
        private fun handleProcessingInstruction() {
            val probe = CharArray(3)
            val read3 = source.read(probe, 0, 3)
            if (read3 < 3) {
                // Not enough to decide; emit what we got.
                emitChars('<', '?', *probe.copyOf(read3))
                return
            }

            val isXml = probe[0].lowercaseChar() == 'x' &&
                probe[1].lowercaseChar() == 'm' &&
                probe[2].lowercaseChar() == 'l'

            if (!isXml) {
                emitChars('<', '?', probe[0], probe[1], probe[2])
                return
            }

            if (seenXmlDeclaration) {
                // Skip this extra XML declaration entirely (until "?>").
                skipUntilProcessingInstructionEnd()
                return
            }

            // Keep the first xml declaration.
            seenXmlDeclaration = true
            emitChars('<', '?', probe[0], probe[1], probe[2])
        }

        private fun drainPending(dest: CharArray, off: Int, len: Int): Int {
            val available = pendingLen - pendingPos
            if (available <= 0) return 0
            val n = minOf(available, len)
            pending.copyInto(dest, destinationOffset = off, startIndex = pendingPos, endIndex = pendingPos + n)
            pendingPos += n
            if (pendingPos >= pendingLen) {
                pendingPos = 0
                pendingLen = 0
            }
            return n
        }

        private fun pushPending(c: Char) {
            if (pendingLen >= pending.size) {
                // Pending should remain tiny; if it ever grows, just drop (best-effort stream sanitization).
                return
            }
            pending[pendingLen++] = c
        }

        private fun emitChars(vararg chars: Char) {
            // Push into pending so caller's loop will drain them into the output buffer.
            for (c in chars) pushPending(c)
        }

        private fun skipUntilProcessingInstructionEnd() {
            var prevWasQuestion = false
            while (true) {
                val i = source.read()
                if (i == -1) return
                val c = i.toChar()
                if (prevWasQuestion && c == '>') return
                prevWasQuestion = c == '?'
            }
        }

        override fun close() {
            source.close()
        }
    }

    private class ChainedReader(
        private val first: Reader,
        private val second: Reader
    ) : Reader() {
        private var usingFirst = true

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (!usingFirst) return second.read(cbuf, off, len)
            val n = first.read(cbuf, off, len)
            if (n != -1) return n
            usingFirst = false
            return second.read(cbuf, off, len)
        }

        override fun close() {
            try {
                first.close()
            } finally {
                second.close()
            }
        }
    }



    // Phase 6: compile the HTML-tag regex ONCE. cleanTextContent runs on every programme's
    // title/desc/category (~870k times on a 289k-programme guide) and used to Pattern.compile a
    // fresh Regex on each call.
    private val htmlTagRegex = Regex("<[^>]+>")

    /**
     * Clean text content by removing HTML entities and normalizing
     */
    private fun cleanTextContent(text: String?): String {
        if (text.isNullOrBlank()) return ""

        return text
            // Remove HTML entities
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            // Remove remaining HTML tags if any
            .replace(htmlTagRegex, "")
            // Trim whitespace
            .trim()
    }

    /**
     * Skip parser to the end of the specified tag
     */
    private fun skipToEndTag(parser: XmlPullParser, tagName: String) {
        try {
            var eventType = parser.eventType
            var depth = 1
            while (eventType != XmlPullParser.END_DOCUMENT && depth > 0) {
                eventType = parser.next()
                depth += nestedTagDelta(parser, eventType, tagName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error while skipping tag: $tagName", e)
        }
    }

    /** +1 entering another [tagName], −1 leaving one, 0 for everything else. */
    private fun nestedTagDelta(parser: XmlPullParser, eventType: Int, tagName: String): Int = when {
        eventType == XmlPullParser.START_TAG && parser.name == tagName -> 1
        eventType == XmlPullParser.END_TAG && parser.name == tagName -> -1
        else -> 0
    }
    
    internal fun parseTimestamp(timestamp: String?): Long {
        // Parse XMLTV format: YYYYMMDDHHmmss [+-]HHmm
        // Example: "20231105143000 +0200" or "20231105143000 +02:00"
        return try {
            if (timestamp == null || timestamp.length < 14) {
                Log.w(TAG, "Invalid timestamp: $timestamp")
                return -1L // sentinel: caller skips/repairs; never fabricate 'now' (bogus now-airing)
            }
            
            // Extract date/time components from format: YYYYMMDDHHmmss
            val year = timestamp.substring(0, 4).toInt()
            val month = timestamp.substring(4, 6).toInt()
            val day = timestamp.substring(6, 8).toInt()
            val hour = timestamp.substring(8, 10).toInt()
            val minute = timestamp.substring(10, 12).toInt()
            val second = timestamp.substring(12, 14).toInt()
            
            // Create Calendar instance in UTC timezone
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.set(year, month - 1, day, hour, minute, second) // Month is 0-indexed
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            
            // XMLTV timestamp is local time at that offset, so subtract it to get canonical UTC.
            calendar.timeInMillis - timezoneOffsetMillis(timestamp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse timestamp: $timestamp", e)
            -1L // sentinel: caller skips/repairs; never fabricate 'now' (bogus now-airing)
        }
    }

    /**
     * Signed millis of the "+HHmm"/"-HH:mm" XMLTV suffix after position 14, or 0 when absent
     * or garbled (a missing sign defaults to positive, matching real-world feeds).
     */
    private fun timezoneOffsetMillis(timestamp: String): Long {
        if (timestamp.length <= 14) return 0L
        val offsetStr = timestamp.substring(14).trim()
        if (offsetStr.isEmpty()) return 0L
        val signStr = offsetStr.substring(0, 1)
        val isPositive = signStr != "-"
        val timePart = offsetStr.substring(if (signStr == "+" || signStr == "-") 1 else 0).replace(":", "")
        if (timePart.length < 4) return 0L
        val offsetHours = timePart.substring(0, 2).toIntOrNull() ?: 0
        val offsetMinutes = timePart.substring(2, 4).toIntOrNull() ?: 0
        val offsetMillis = (offsetHours * 60L * 60 * 1000) + (offsetMinutes * 60L * 1000)
        return if (isPositive) offsetMillis else -offsetMillis
    }
}
