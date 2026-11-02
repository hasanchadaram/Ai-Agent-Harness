package com.companion.ainotifications.dictation

import kotlinx.coroutines.channels.Channel

/** Session-owned PCM. Capture only copies a short read; snapshots copy outside the lock. */
class StreamingAudio(private val silenceMs: Int = 320, private val quietRms: Int = 180,
    private val detector: SpeechDetector? = null) {
    private data class Block(val start: Int, val pcm: ByteArray)
    data class Position(val samples: Int, val pause: Int, val lastSpeech: Int, val finished: Boolean,
        val pauses: Int, val detector: String, val probability: Float, val pendingSamples: Int)
    private val blocks = ArrayList<Block>()
    private var samples = 0
    private var pause = 0
    private var lastSpeech = 0
    private var finished = false
    private var frameSamples = 0
    private var frameEnergy = 0L
    private var quietSamples = 0
    private var pauseReported = false
    private val pauses = ArrayList<Int>()
    private val speechEnds = ArrayList<Int>()
    private val frame = FloatArray(detector?.frameSize ?: 320)
    private var probability = 0f
    private var speaking = false
    internal val updates = Channel<Unit>(Channel.CONFLATED)

    @Synchronized fun append(pcm: ByteArray, count: Int = pcm.size) {
        check(!finished)
        require(count in 0..pcm.size && count % 2 == 0)
        require(samples + count / 2 <= DictationProtocol.SAMPLE_RATE * DictationProtocol.MAX_SECONDS)
        blocks.add(Block(samples, pcm.copyOf(count)))
        for (i in 0 until count step 2) {
            val value = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            frameEnergy += value.toLong() * value
            frame[frameSamples] = value / 32768f
            frameSamples++
            samples++
            if (frameSamples == frame.size) {
                probability = detector?.probability(frame)
                    ?: if (frameEnergy > quietRms.toLong() * quietRms * frameSamples) 1f else 0f
                speaking = if (speaking) probability >= 0.30f else probability >= 0.50f
                if (speaking) {
                    lastSpeech = samples
                    speechEnds.add(samples)
                    quietSamples = 0
                    pauseReported = false
                } else {
                    quietSamples += frameSamples
                    if (!pauseReported && lastSpeech > pause && quietSamples >= silenceMs * 16) {
                        pause = samples
                        pauses.add(pause)
                        pauseReported = true
                    }
                }
                frameSamples = 0
                frameEnergy = 0
            }
        }
        updates.trySend(Unit)
    }

    @Synchronized fun position(): Position {
        // Stop can arrive before a complete 20 ms analysis frame. Include its energy
        // so a short word after the previous pause is not mistaken for trailing quiet.
        val partialSpeech = detector == null && frameSamples > 0 && frameEnergy > quietRms.toLong() * quietRms * frameSamples
        return Position(samples, pause, if (partialSpeech) samples else lastSpeech, finished,
            pauses.size, detector?.name ?: "Energy (test/batch)", probability, frameSamples)
    }

    @Synchronized fun pauseBetween(start: Int, end: Int): Int? {
        val index = pauses.binarySearch(end).let { if (it >= 0) it else -it - 2 }
        return if (index >= 0) pauses[index].takeIf { it > start } else null
    }

    @Synchronized fun hasSpeech(start: Int, end: Int): Boolean {
        val index = speechEnds.binarySearch(start + 1).let { if (it >= 0) it else -it - 1 }
        return (index < speechEnds.size && speechEnds[index] - frame.size < end) ||
            (detector == null && end == samples && frameSamples > 0 && frameEnergy > quietRms.toLong() * quietRms * frameSamples)
    }

    @Synchronized internal fun contextState() = position() to speechEnds.toIntArray()
    internal val analysisFrameSize get() = frame.size

    @Synchronized fun finish() {
        finished = true
        updates.trySend(Unit)
    }

    fun snapshot(start: Int, end: Int): ByteArray {
        val selected = synchronized(this) {
            require(start in 0..end && end <= samples)
            var low = 0
            var high = blocks.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (blocks[middle].start + blocks[middle].pcm.size / 2 <= start) low = middle + 1 else high = middle
            }
            val selected = ArrayList<Block>()
            var index = low
            while (index < blocks.size && blocks[index].start < end) selected.add(blocks[index++])
            selected
        }
        val result = ByteArray((end - start) * 2)
        for (block in selected) {
            val left = maxOf(start, block.start)
            val right = minOf(end, block.start + block.pcm.size / 2)
            block.pcm.copyInto(result, (left - start) * 2, (left - block.start) * 2, (right - block.start) * 2)
        }
        return result
    }
}

/** Text stability only: it never converts words into audio offsets or discards audio. */
class LocalAgreement2 {
    private var previous = emptyList<String>()
    var stable: String = ""
        private set
    var tentative: String = ""
        private set

    fun update(text: String) {
        val current = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var agreed = 0
        // A repeated hypothesis can still contain a premature period. Keep the
        // recent ending provisional until later speech supplies right context.
        while (agreed < minOf(previous.size, (current.size - 3).coerceAtLeast(0)) &&
            previous[agreed] == current[agreed]) agreed++
        stable = current.take(agreed).joinToString(" ")
        tentative = current.drop(agreed).joinToString(" ")
        previous = current
    }

    fun reset() { previous = emptyList(); stable = ""; tentative = "" }
}

/** Preserve speech and normal pauses; remove only excess quiet from ASR context.
 * Original PCM is untouched. The worker's offsets are mapped back for progress.
 */
internal class RecognitionAudio(private val source: StreamingAudio) {
    private data class Span(val first: Int, val end: Int)
    private var spans = emptyList<Span>()
    private var raw = source.position()
    private var samples = 0
    private val quietPadding = 12_000 // 750 ms on each side of gaps over 1.5 s.

    fun position(): StreamingAudio.Position {
        val (position, speechEnds) = source.contextState()
        raw = position
        val regions = speechEnds.map { Span(it - source.analysisFrameSize, it) }.toMutableList()
        if (raw.finished && raw.pendingSamples > 0) regions.add(Span(raw.samples - raw.pendingSamples, raw.samples))
        val selected = ArrayList<Span>()
        var cursor = 0
        var last = 0
        for (region in regions) {
            if (region.first - last > quietPadding * 2) {
                selected.add(Span(cursor, last + quietPadding))
                cursor = region.first - quietPadding
            }
            last = region.end
        }
        val end = minOf(raw.samples, last + quietPadding)
        if (cursor < end) selected.add(Span(cursor, end))
        spans = selected
        samples = spans.sumOf { it.end - it.first }
        val lastSpeech = spans.sumOf { (raw.lastSpeech - it.first).coerceIn(0, it.end - it.first) }
        return raw.copy(samples = samples, pause = 0, lastSpeech = lastSpeech)
    }

    private fun ranges(start: Int, end: Int): List<Span> {
        require(start in 0..end && end <= samples)
        var offset = 0
        return buildList {
            for (span in spans) {
                val size = span.end - span.first
                val left = maxOf(start, offset)
                val right = minOf(end, offset + size)
                if (left < right) add(Span(span.first + left - offset, span.first + right - offset))
                offset += size
            }
        }
    }

    fun snapshot(start: Int, end: Int): ByteArray {
        val output = ByteArray((end - start) * 2)
        var offset = 0
        for (span in ranges(start, end)) {
            val part = source.snapshot(span.first, span.end)
            part.copyInto(output, offset)
            offset += part.size
        }
        return output
    }

    fun hasSpeech(start: Int, end: Int) = ranges(start, end).any { source.hasSpeech(it.first, it.end) }

    fun rawOffset(sample: Int): Int {
        if (sample == samples && raw.finished) return raw.samples
        var offset = 0
        for (span in spans) {
            if (sample <= offset + span.end - span.first) return span.first + sample - offset
            offset += span.end - span.first
        }
        return raw.samples
    }
}
