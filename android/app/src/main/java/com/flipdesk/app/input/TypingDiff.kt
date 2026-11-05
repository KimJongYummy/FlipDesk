package com.flipdesk.app.input

/** What to send to the PC so that its text goes from [old] to [new]: N backspaces, then insert. */
data class TypingEdit(val backspaces: Int, val insert: String) {
    val isEmpty get() = backspaces == 0 && insert.isEmpty()
}

object TypingDiff {
    /**
     * Diff against the common prefix. IMEs (autocorrect, composition, swipe typing) replace the
     * tail of the word being typed, so "backspace the tail, type the new tail" mirrors exactly
     * what happened in the phone's text field.
     */
    fun diff(old: String, new: String): TypingEdit {
        var i = 0
        val max = minOf(old.length, new.length)
        while (i < max && old[i] == new[i]) i++
        // Don't split a surrogate pair.
        if (i > 0 && i < old.length && Character.isLowSurrogate(old[i]) && Character.isHighSurrogate(old[i - 1])) i--
        val removed = old.substring(i)
        return TypingEdit(removed.codePointCount(0, removed.length), new.substring(i))
    }
}
