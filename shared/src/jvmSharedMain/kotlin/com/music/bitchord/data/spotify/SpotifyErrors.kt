package com.music.bitchord.data.spotify

/** KO: a playlist Spotify says is not there for this account — private, deleted, or a wrong link. */
class SpotifyUnavailableException(message: String) : IllegalStateException(message)

/**
 * KO: what went wrong with a Spotify request, in the few kinds a listener can act on —
 * so the screen says "check your connection" rather than "Unable to resolve host
 * api-partner.spotify.com" or "Spotify playlist response was empty".
 */
object SpotifyErrors {
    enum class Kind { OFFLINE, SIGNED_OUT, UNAVAILABLE, BUSY, OTHER }

    private val STATUS = Regex("(?:HTTP\\s*|\\()(\\d{3})\\b")
    private val GONE_WORDS = listOf("not found", "notfound", "private", "is public", "missing", "no tracks found")

    fun kind(error: Throwable): Kind {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        if (chain.any { it is SpotifyUnavailableException }) return Kind.UNAVAILABLE
        // No answer at all: no connection, a host not found, a timeout, a dropped line.
        if (chain.any { it is java.io.IOException }) return Kind.OFFLINE
        val message = chain.mapNotNull { it.message }.joinToString(" ")
        val code = STATUS.find(message)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            message.contains("sign-in expired", ignoreCase = true) || code == 401 || code == 403 -> Kind.SIGNED_OUT
            code == 404 || GONE_WORDS.any { message.contains(it, ignoreCase = true) } -> Kind.UNAVAILABLE
            code == 429 || (code != null && code >= 500) -> Kind.BUSY
            else -> Kind.OTHER
        }
    }
}
