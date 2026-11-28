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
 *
 * The release footer (after the last "---": version, commit, "install the
 * APK under Assets") is for the GitHub page and left out. Notes covering
 * several releases (see AppUpdateChecker.notesBetween) carry a "## v<version>"
 * line before each; the pages after it are labelled with that [Page.version],
 * and what that release says before its first section is left out.
 */
object ReleaseNotesPages {

    /** What a section is, from "[새 기능]", "[개선]" or "[수정]" in front of its title. */
    enum class Kind { NEW, IMPROVED, FIXED }

    data class Page(val title: String?, val markdown: String, val version: String? = null, val kind: Kind? = null)

    private val KIND_TAGS = mapOf(
        "새 기능" to Kind.NEW, "new" to Kind.NEW,
        "개선" to Kind.IMPROVED, "improved" to Kind.IMPROVED,
        "수정" to Kind.FIXED, "fixed" to Kind.FIXED,
    )

    /** "[새 기능] 일본어 후리가나" → (NEW, "일본어 후리가나"); a title without a tag stays as it is. */
    fun kindOf(title: String): Pair<Kind?, String> {
        // The GitHub page writes the tag in colour: $\color{#1B7F3B}{\textsf{새 기능}}$ 제목.
        val tag = Regex("^\\[([^\\]]+)]\\s*").find(title)
            ?: Regex("^\\$\\\\color\\{[^}]*}\\{\\\\textsf\\{([^}]+)}}\\$\\s*").find(title)
            ?: return null to title
        val kind = KIND_TAGS[tag.groupValues[1].trim().lowercase()] ?: return null to title
        return kind to title.substring(tag.range.last + 1).trim()
    }

    data class Notes(val intro: String, val pages: List<Page>)

    /** About how much text one page holds when the notes have no sections to cut at. */
    private const val PAGE_CHARS = 900

    fun split(body: String?): Notes {
        val text = withoutFooter(body.orEmpty().replace("\r\n", "\n")).trim()
        if (text.isEmpty()) return Notes("", emptyList())
        val intro = StringBuilder()
        val pages = mutableListOf<Page>()
        var title: String? = null
        var current: StringBuilder? = null
        var version: String? = null
        var skippingLead = false
        fun close() {
            val content = current?.toString()?.trim() ?: return
            if (content.isNotEmpty() || title != null) {
                val (kind, plain) = title?.let(::kindOf) ?: (null to null)
                pages += Page(plain ?: title, content, version, kind)
            }
        }
        for (line in text.lines()) {
            when {
                line.startsWith("### ") -> {
                    close()
                    title = line.removePrefix("### ").trim()
                    current = StringBuilder()
                    skippingLead = false
                }
                VERSION_HEADING.matches(line) -> {
                    close()
                    current = null
                    version = label(line.removePrefix("## ").trim().removePrefix("v"))
                    // The first release's banner stays above the pages; a later one's says it again.
                    skippingLead = pages.isNotEmpty()
                }
                skippingLead -> Unit
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

    private val VERSION_HEADING = Regex("^## v?\\d+\\.\\d+\\S*ko\\.\\d+\\S*\\s*$")

    /** "1.10-ko.78-beta" → "ko.78 베타", for a page's version label. */
    fun label(version: String): String {
        val build = Regex("ko\\.(\\d+)").find(version)?.groupValues?.get(1) ?: return version
        return if (version.endsWith("-beta")) "ko.$build 베타" else "ko.$build"
    }

    /** [text] up to its last "---" line, when what follows is the release footer. */
    internal fun withoutFooter(text: String): String {
        val lines = text.lines()
        val rule = lines.indexOfLast { it.trim() == "---" }
        if (rule < 0) return text
        val after = lines.drop(rule + 1).joinToString("\n")
        return if (FOOTER.containsMatchIn(after)) lines.take(rule).joinToString("\n") else text
    }

    private val FOOTER = Regex("버전 \\S+.*빌드|설치:|Assets")

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
