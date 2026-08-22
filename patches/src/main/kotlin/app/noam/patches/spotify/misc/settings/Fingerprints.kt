package app.noam.patches.spotify.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * The method that builds the settings section containing the app-appearance rows.
 *
 * Spotify obfuscates every settings class, but the row ids are plain strings that have been stable
 * across releases, so they are what the patch anchors on. Everything the patch needs after that —
 * the row class, the accessor classes and the list builder — is read out of the matched method.
 */
internal object SettingsSectionFingerprint : Fingerprint(
    filters = listOf(
        string("appLanguage"),
        string(ANCHOR_ROW_ID),
    ),
)
