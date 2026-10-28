package com.yawnandpawn.app.ui.sound

/**
 * The system document picker for audio files (Story 7.4): "Pick a file" in the Sound sub-screen's "Your files". No
 * storage or media permission: the picker grants read access to the one file the user picks.
 */
interface SoundFilePicker {
    /**
     * Opens the picker from the screen in front (audio files only) and returns the picked file's URI, or null when the user
     * cancelled it or no screen is in front.
     */
    suspend fun pick(): String?
}
