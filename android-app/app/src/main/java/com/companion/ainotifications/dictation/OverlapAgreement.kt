package com.companion.ainotifications.dictation

/** Reconcile actual overlapping audio, never unrelated adjacent text. */
object OverlapAgreement {
    data class Join(val committed: String, val current: String, val matchedWords: Int)
    private data class Match(val oldStart: Int, val newStart: Int, val count: Int)
    private fun key(word: String) = word.lowercase().filter { it.isLetterOrDigit() }
    fun join(previous: String, current: String): Join? {
        val old = previous.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val next = current.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val left = old.map(::key)
        val right = next.map(::key)
        val matches = ArrayList<Match>()
        for (dropEnd in 0..minOf(2, old.size)) {
            val end = old.size - dropEnd
            for (newStart in 0..minOf(2, next.size)) {
                for (oldStart in 0 until end) {
                    val count = end - oldStart
                    if (count < 2 || newStart + count > next.size) continue
                    val anchor = left.subList(oldStart, end)
                    if (anchor.any { it.isEmpty() } || anchor.sumOf { it.length } < 8 || anchor.distinct().size < 2) continue
                    if (anchor == right.subList(newStart, newStart + count)) matches.add(Match(oldStart, newStart, count))
                }
            }
        }
        val length = matches.maxOfOrNull { it.count } ?: return null
        val best = matches.filter { it.count == length }
        if (best.map { it.oldStart to it.newStart }.distinct().size != 1) return null
        val match = best.first()
        val corrected = next.toMutableList()
        // Fresh-window capitalization must not turn a previously lowercase
        // overlapping word into a new sentence. Only reconcile the opening
        // matched word; preserve known names, acronyms, quotes and pronoun I.
        if (match.oldStart > 0 && match.newStart == 0) {
            val before = old[match.oldStart - 1]
            val earlier = old[match.oldStart]
            val opening = next.first()
            if (before.last() !in ".!?…:;\"'“”‘’([{" && earlier.all { it.isLetter() } &&
                earlier == earlier.lowercase() && earlier.any { it.isLowerCase() } &&
                opening.first() in 'A'..'Z' && opening.drop(1).any { it.isLowerCase() } &&
                opening.drop(1).none { it.isUpperCase() } && key(earlier) == key(opening)) {
                corrected[0] = opening.replaceFirstChar { it.lowercaseChar() }
            }
        }
        return Join(old.take(match.oldStart).joinToString(" "), corrected.drop(match.newStart).joinToString(" "), match.count)
    }
}
