package com.lyricsmachine.app.speech

/**
 * Runs [block], turning a native-library failure (missing or incompatible `.so`, failed static initialiser
 * of [WhisperNative]) into an [IllegalStateException]. Those failures are [Error]s that callers catching
 * [Exception] would miss and crash on; as exceptions they become ordinary engine failures.
 */
internal inline fun <T> loadOrThrowIllegalState(block: () -> T): T = try {
    block()
} catch (e: ExceptionInInitializerError) {
    throw IllegalStateException("speech engine unavailable", e)
} catch (e: LinkageError) { // UnsatisfiedLinkError, NoClassDefFoundError after a failed init
    throw IllegalStateException("speech engine unavailable", e)
}
