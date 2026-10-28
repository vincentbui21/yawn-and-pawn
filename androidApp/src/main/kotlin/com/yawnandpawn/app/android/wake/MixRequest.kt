package com.yawnandpawn.app.android.wake

/**
 * A ring that mixes the recording [recordingId] of the session [sessionId] into its alarm sound ("Mix into alarm",
 * Story 7.5): passed to [AndroidAlarmPlayer.play] by [WakeRuntime] only for a session whose frozen mode is Mix with a
 * frozen recording (`MotivationResolver.mixRecordingId`), and never for a Direct Boot ring.
 */
data class MixRequest(
    val sessionId: String,
    val recordingId: String,
)
