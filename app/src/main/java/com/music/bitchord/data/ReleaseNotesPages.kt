package com.music.bitchord.data

/**
 * KO: a release's notes cut into pages, one per "### " section, for the
 * update and what's-new windows — a long changelog read a section at a time
 * rather than scrolled through in one box.
 *
 * What comes before the first section (the beta banner, the entry's own
 * one-line summary) stays above the pages as [intro]; the "## " heading the
 * notes workflow puts over every entry says nothing and is left out. Notes
 * without sections are cut at blank lines into pages of about [PAGE_CHARS].
 */
object ReleaseNotesPages {

    data class Page(val title: String?, val markdown: String)

    data class Notes(val intro: String, val pages: List<Page>)

    /** About how much text one page holds when the notes have no sections to cut at. */
    private const val PAGE_CHARS = 900

    fun split(body: String?): Notes {
        val text = body.orEmpty().replace("\r\n", "\n").trim()
        if (text.isEmpty()) return Notes("", emptyList())
        val intro = StringBuilder()
        val pages = mutableListOf<Page>()
        var title: String? = null
        var current: StringBuilder? = null
        fun close() {
            val content = current?.toString()?.trim() ?: return
            if (content.isNotEmpty() || title != null) pages += Page(title, content)
        }
        for (line in text.lines()) {
            when {
                line.startsWith("### ") -> {
                    close()
                    title = line.removePrefix("### ").trim()
                    current = StringBuilder()
                }
                // The workflow's "## 바뀐 내용" over every entry: a heading with nothing to say.
                line.startsWith("## ") && current == null -> Unit
                current != null -> current!!.appendLine(line)
                else -> intro.appendLine(line)
            }
        }
        close()
        if (pages.isEmpty()) return Notes("", byLength(intro.toString().trim()))
        return Notes(intro.toString().trim(), pages)
    }

    /** [text] cut at blank lines into pages of about [PAGE_CHARS]; a paragraph is never split. */
    private fun byLength(text: String): List<Page> {
        if (text.isEmpty()) return emptyList()
        val pages = mutableListOf<Page>()
        val page = StringBuilder()
        for (paragraph in text.split(Regex("\n\\s*\n"))) {
            if (page.isNotEmpty() && page.length + paragraph.length > PAGE_CHARS) {
                pages += Page(null, page.toString().trim())
                page.clear()
            }
            page.append(paragraph).append("\n\n")
        }
        if (page.isNotBlank()) pages += Page(null, page.toString().trim())
        return pages
    }
}
