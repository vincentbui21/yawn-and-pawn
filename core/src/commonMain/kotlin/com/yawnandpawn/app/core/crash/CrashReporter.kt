package com.yawnandpawn.app.core.crash

/**
 * Port for reporting an unexpected exception (AD-12, NFR-2). The wake runtime reports every uncaught exception of the
 * wake flow here before it switches to the default sound and dispatches `ProcessRestored`. Production binds the
 * Crashlytics reporter when the app has a Firebase configuration (Story 1.19), else a no-op reporter that only logs.
 * `FakeCrashReporter` in tests.
 *
 * A report never carries personal data: implementations send the exception (type, message and stack) only, never
 * alarm labels, sounds, photos, recordings or purchase tokens.
 */
fun interface CrashReporter {
    fun report(throwable: Throwable)
}
