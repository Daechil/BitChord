package com.music.bitchord.playback.smart

/**
 * KO: what Automix is about to do, in the few words the stats line over the
 * artwork has room for — "AutoMix · 크로스페이드 12초 뒤 · 8초".
 *
 * The controller's full English line (both songs' grids, which check cleared
 * each loop, the tempo ramp) is for whoever is debugging a mix, and goes to
 * the copied log instead; on the artwork it ran to two lines of internal terms.
 * This carries only the numbers, so the player can put them in the reader's
 * language.
 *
 * [inSeconds] is how long until the mix starts, 0 once it has.
 */
sealed interface AutomixSummary {
    /** A loop mix is armed and starts in [inSeconds]. */
    data class LoopSoon(val inSeconds: Int) : AutomixSummary

    /** Both songs are looping under each other. */
    data object Looping : AutomixSummary

    /** The loop has been let go and the next song is taking over. */
    data object LettingGo : AutomixSummary

    /** A plain equal-power crossfade of [fadeSeconds]. */
    data class Crossfade(val inSeconds: Int, val fadeSeconds: Int) : AutomixSummary

    /** A DJ filter sweep over [bars] bars, or [fadeSeconds] when the beat is not known. */
    data class FilterMix(val inSeconds: Int, val bars: Int, val fadeSeconds: Int) : AutomixSummary

    /** A beat-matched mix over [bars] bars; [tempoChanged] when the next song is stretched to fit. */
    data class BeatMix(val inSeconds: Int, val bars: Int, val fadeSeconds: Int, val tempoChanged: Boolean) : AutomixSummary
}
