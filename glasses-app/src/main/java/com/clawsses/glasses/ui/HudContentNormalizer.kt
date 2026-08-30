package com.clawsses.glasses.ui

/** Removes model soft wraps while preserving structural Markdown boundaries. */
internal object HudContentNormalizer {
    fun unwrapSoftLineBreaks(text: String): String {
        val lines = text.split("\n")
        if (lines.size <= 1) return text

        var insideCodeFence = false
        return buildString(text.length) {
            for (index in lines.indices) {
                val line = lines[index]
                append(line)
                if (index >= lines.lastIndex) continue

                val next = lines[index + 1]
                val startsCodeFence = line.trimStart().startsWith("```")
                val keepNewline = insideCodeFence ||
                    startsCodeFence ||
                    line.isBlank() ||
                    next.isBlank() ||
                    next.trimStart().let {
                        it.startsWith("- ") ||
                            it.startsWith("* ") ||
                            it.startsWith("+ ") ||
                            it.matches(Regex("^\\d+[.)].+")) ||
                            it.startsWith("#") ||
                            it.startsWith("```") ||
                            it.startsWith("> ")
                    }
                if (keepNewline) append('\n') else if (line.isNotEmpty()) append(' ')
                if (startsCodeFence) insideCodeFence = !insideCodeFence
            }
        }
    }
}
