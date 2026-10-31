package com.xuanthanhtnt.xtplayer.trakt

/**
 * When a scrobble may go out (PlayerScreen.swift `scrobble`): only with a Trakt item and a known duration — the
 * player often reports the duration just after "playing", so the start is sent again once it is known, otherwise
 * the pause / stop that follow would be dropped too and Trakt would never see the file. A pause or stop needs a
 * start before it; a stop is sent once.
 */
class ScrobbleGate {
    private var stopped = true

    /** Whether [action] may be sent now; records it. */
    fun allow(action: TraktService.ScrobbleAction, hasMedia: Boolean, durationKnown: Boolean): Boolean {
        if (!hasMedia || !durationKnown) return false
        return when (action) {
            TraktService.ScrobbleAction.START -> {
                stopped = false
                true
            }
            TraktService.ScrobbleAction.PAUSE -> !stopped
            TraktService.ScrobbleAction.STOP -> if (stopped) false else {
                stopped = true
                true
            }
        }
    }
}
