package io.github.lynlioss.klang.spotify

/** Upgrade the old automatic default to YouTube unless a Qobuz account was explicitly connected. */
internal fun playbackPreference(value: String?, qobuzConnected: Boolean): String = when (value) {
    "qobuz" -> if (qobuzConnected) "qobuz" else "youtube"
    "auto" -> if (qobuzConnected) "auto" else "youtube"
    else -> "youtube"
}
