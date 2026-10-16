package com.yawnandpawn.app.core.session

/**
 * The app screens' theme (Settings › Appearance, Story 5.1): follow the system light / dark setting, or always Light or
 * Dark. Wake screens always use Sunrise. The names are stored, so they never change.
 */
enum class ThemeMode {
    System,
    Light,
    Dark,
    ;

    companion object {
        /** The mode stored as [name], or null for a missing or unknown name (a newer or damaged value). */
        fun parse(name: String?): ThemeMode? = entries.firstOrNull { it.name == name }
    }
}
