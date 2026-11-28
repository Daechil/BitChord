package com.music.bitchord.data.spotify

import com.music.bitchord.data.spotify.SpotifyErrors.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyErrorsTest {
    private fun kind(error: Throwable) = SpotifyErrors.kind(error)

    @Test
    fun `no connection reads as offline, however deep the cause`() {
        assertEquals(Kind.OFFLINE, kind(java.net.UnknownHostException("api-partner.spotify.com")))
        assertEquals(Kind.OFFLINE, kind(java.net.SocketTimeoutException("timeout")))
        assertEquals(Kind.OFFLINE, kind(IllegalStateException("wrapped", java.net.ConnectException("refused"))))
    }

    @Test
    fun `both ways Spotify's status is written are read`() {
        assertEquals(Kind.SIGNED_OUT, kind(IllegalStateException("Spotify fetchPlaylist failed (401)")))
        assertEquals(Kind.SIGNED_OUT, kind(IllegalStateException("Spotify sign-in expired")))
        assertEquals(Kind.UNAVAILABLE, kind(IllegalStateException("Spotify fetchPlaylist failed (404)")))
        assertEquals(Kind.UNAVAILABLE, kind(IllegalStateException("Failed to load Spotify playlist (HTTP 404)")))
        assertEquals(Kind.BUSY, kind(IllegalStateException("HTTP 429")))
        assertEquals(Kind.BUSY, kind(IllegalStateException("Spotify libraryV3 failed (503)")))
    }

    @Test
    fun `a private or missing playlist reads as unavailable`() {
        assertEquals(Kind.UNAVAILABLE, kind(IllegalStateException("Spotify playlist entity missing or private")))
        assertEquals(Kind.UNAVAILABLE, kind(IllegalStateException("Could not parse Spotify playlist metadata. Make sure the playlist is public.")))
        assertEquals(Kind.UNAVAILABLE, kind(IllegalStateException("No tracks found in public Spotify playlist.")))
    }

    @Test
    fun `anything else is the plain retry message, never the raw text`() {
        assertEquals(Kind.OTHER, kind(IllegalArgumentException("Unexpected JSON token at offset 0")))
        assertEquals(Kind.OTHER, kind(IllegalStateException("Spotify playlist response was empty")))
        assertEquals(Kind.OTHER, kind(RuntimeException()))
    }
}
