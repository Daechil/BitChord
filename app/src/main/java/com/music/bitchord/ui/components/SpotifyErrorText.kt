package com.music.bitchord.ui.components

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.spotify.SpotifyErrors

/** KO: a failed Spotify request in words a listener can act on — see [SpotifyErrors]. */
internal fun spotifyErrorText(context: Context, error: Throwable): String = context.getString(
    when (SpotifyErrors.kind(error)) {
        SpotifyErrors.Kind.OFFLINE -> R.string.spotify_error_offline
        SpotifyErrors.Kind.SIGNED_OUT -> R.string.spotify_error_signed_out
        SpotifyErrors.Kind.UNAVAILABLE -> R.string.spotify_error_unavailable
        SpotifyErrors.Kind.BUSY -> R.string.spotify_error_busy
        SpotifyErrors.Kind.OTHER -> R.string.spotify_error_other
    },
)
