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

/**
 * Navigates to a settings destination.
 *
 * Every settings row action — a screen, a link, or a lambda — ends up producing a destination string
 * that is handed to this method, which makes it the one place to intercept the Morphe row's tap.
 * Its log message is a plain string literal, so it is unaffected by obfuscation.
 */
internal object NavigateToDestinationFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/String;", "L", "Landroid/os/Bundle;"),
    filters = listOf(
        string("Missing instrumentation during Element navigation"),
    ),
)
