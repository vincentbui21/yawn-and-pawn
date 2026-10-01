package com.yawnandpawn.app.core.sound

/**
 * Which sound an alarm plays (FR-SND-1/2), stored in `Alarm.soundRef` as text ([encode] / [parse]).
 *
 * - [BuiltIn]: `builtin:<id>`, a sound of the [SoundCatalog] (`builtin:default` is `Alarm.DEFAULT_SOUND_REF`).
 * - [System]: `system:<title>|<uri>`, one of the phone's alarm ringtones. The title is kept so a ringtone that
 *   disappears can still be named ("File missing. Default sound will play."); `%` and `|` in it are percent-escaped.
 *
 * Story 7.4 adds the user's files. Any other text parses to null: the alarm then plays the default sound.
 */
sealed interface SoundRef {
    fun encode(): String

    data class BuiltIn(
        val id: String,
    ) : SoundRef {
        override fun encode(): String = "$BUILT_IN_PREFIX$id"
    }

    data class System(
        val uri: String,
        val title: String,
    ) : SoundRef {
        override fun encode(): String = "$SYSTEM_PREFIX${escape(title)}$TITLE_END$uri"
    }

    companion object {
        private const val BUILT_IN_PREFIX = "builtin:"
        private const val SYSTEM_PREFIX = "system:"
        private const val TITLE_END = '|'

        /** The reference stored as [text], or null when it is not one this version knows. */
        fun parse(text: String): SoundRef? =
            when {
                text.startsWith(BUILT_IN_PREFIX) -> {
                    text.removePrefix(BUILT_IN_PREFIX).takeIf { it.isNotBlank() }?.let(::BuiltIn)
                }

                text.startsWith(SYSTEM_PREFIX) -> {
                    val body = text.removePrefix(SYSTEM_PREFIX)
                    val end = body.indexOf(TITLE_END)
                    val uri = if (end < 0) "" else body.substring(end + 1)
                    if (uri.isBlank()) null else System(uri = uri, title = unescape(body.substring(0, end)))
                }

                else -> {
                    null
                }
            }

        private fun escape(title: String): String = title.replace("%", "%25").replace("|", "%7C")

        private fun unescape(title: String): String = title.replace("%7C", "|").replace("%25", "%")
    }
}
