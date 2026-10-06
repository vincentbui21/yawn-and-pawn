package com.yawnandpawn.app.ui.qr

import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.10: the 3-frames rule, the 2 s repeat rule, the camera permission gate and QR registration. */
class QrScanningTest {
    private val a = ScanResult(CodeFormat.QrCode, "kitchen")
    private val b = ScanResult(CodeFormat.QrCode, "hallway")

    private class FakePermission(
        var granted: Boolean,
        private val answer: Boolean = false,
    ) : CameraPermission {
        var requests = 0
        var settingsOpened = 0

        override fun isGranted(): Boolean = granted

        override suspend fun request(): Boolean {
            requests++
            granted = answer
            return answer
        }

        override fun openSettings() {
            settingsOpened++
        }
    }

    @Test
    fun `a code counts only after 3 frames in a row with it`() {
        val frames = ConsecutiveFrames()

        assertNull(frames.frame(listOf(a)))
        assertNull(frames.frame(listOf(a, b)), "the code followed is still in the frame")
        assertEquals(ScanEvent.Detected(listOf(a), others = true), frames.frame(listOf(b, a)), "b is in view, not stable yet")
        assertNull(frames.frame(listOf(a)), "the streak goes on; a's next report is at 6 frames")
        assertNull(frames.frame(listOf(a)))
        assertEquals(ScanEvent.Detected(listOf(a)), frames.frame(listOf(a)))
        repeat(2) { assertNull(frames.frame(listOf(a))) }
        assertEquals(ScanEvent.Detected(listOf(a)), frames.frame(listOf(a)), "held still: every 3 frames, for good")
    }

    @Test
    fun `a frame without the code, or with another one, starts over`() {
        val frames = ConsecutiveFrames()

        assertNull(frames.frame(listOf(a)))
        assertNull(frames.frame(listOf(a)))
        assertNull(frames.frame(emptyList()))
        assertNull(frames.frame(listOf(a)))
        assertNull(frames.frame(listOf(a)))
        assertNull(frames.frame(listOf(b)), "another code")
        assertNull(frames.frame(listOf(b)))
        assertEquals(ScanEvent.Detected(b), frames.frame(listOf(b)))
        assertNull(frames.frame(listOf(ScanResult(CodeFormat.Aztec, "hallway"))), "the same text in another format is another code")
    }

    @Test
    fun `each code in view has its own streak, so a second code is never starved by the first`() {
        val frames = ConsecutiveFrames()

        assertNull(frames.frame(listOf(a)))
        assertNull(frames.frame(listOf(a, b)))
        val first = frames.frame(listOf(a, b))
        assertEquals(ScanEvent.Detected(listOf(a), others = true), first)
        assertNull(first!!.single, "two codes in view: none can be picked")
        val both = frames.frame(listOf(b, a))
        assertEquals(ScanEvent.Detected(listOf(b, a)), both, "b is stable too, a frame later")
        assertNull(both!!.single)
        assertEquals(a, ScanEvent.Detected(a).single, "one code alone in view")
    }

    @Test
    fun `the same code is submitted at most once per 2 s, another code at once`() {
        val gate = RepeatGate()
        val kitchen = a.code!!
        val hallway = b.code!!

        assertTrue(gate.admit(kitchen, 10_000))
        assertFalse(gate.admit(kitchen, 11_999))
        assertTrue(gate.admit(hallway, 12_000))
        assertTrue(gate.admit(kitchen, 12_001), "the last submitted was another code")
        assertFalse(gate.admit(kitchen, 14_000))
        assertTrue(gate.admit(kitchen, 14_001))
    }

    @Test
    fun `a scan result keeps its raw value out of its text and gives the stored code`() {
        assertEquals("ScanResult(QrCode)", a.toString())
        assertEquals(RegisteredCode.of(CodeFormat.QrCode, "kitchen"), a.code)
        assertNull(ScanResult(CodeFormat.QrCode, "  ").code)

        val bytes = byteArrayOf(0, -1, 32, 10)
        val binary = ScanResult.ofBytes(CodeFormat.QrCode, bytes)
        assertEquals(RegisteredCode.ofBytes(CodeFormat.QrCode, bytes), binary.code, "a binary code: its bytes, untrimmed")
        assertEquals(binary, ScanResult.ofBytes(CodeFormat.QrCode, bytes.copyOf()), "the same bytes are the same scan")
        assertEquals("ScanResult(QrCode)", binary.toString())
        assertNull(ScanResult.ofBytes(CodeFormat.QrCode, ByteArray(0)).code)
    }

    @Test
    fun `picking a camera check asks only when the permission is missing`() =
        runTest {
            val granted = FakePermission(granted = true)
            assertTrue(granted.allowsCameraCheck())
            assertEquals(0, granted.requests)

            val allowed = FakePermission(granted = false, answer = true)
            assertTrue(allowed.allowsCameraCheck())
            assertEquals(1, allowed.requests)

            val denied = FakePermission(granted = false, answer = false)
            assertFalse(denied.allowsCameraCheck(), "denied or don't ask again: the card stays unselected")
        }

    @Test
    fun `registration pauses on a found code, scans again, and hands the chosen code over`() =
        runTest {
            val chosen = mutableListOf<RegisteredCode>()
            var backs = 0
            val model = QrRegistrationModel(FakePermission(granted = true), onCodeChosen = { chosen += it }, onBack = { backs++ })
            model.onShown()
            assertEquals(QrRegistrationUiState(QrScanStep.Scanning, cameraUnavailable = false), model.state)
            assertTrue(model.scanning)

            model.onScan(ScanEvent.Detected(ScanResult(CodeFormat.QrCode, " ")))
            assertEquals(QrScanStep.Scanning, model.state.step, "a blank code is never offered")
            model.onScan(ScanEvent.Detected(listOf(a, b)))
            model.onScan(ScanEvent.Detected(listOf(a), others = true))
            assertEquals(QrScanStep.Scanning, model.state.step, "two codes in view: it waits until one code is shown")
            model.onScan(ScanEvent.Detected(a))
            model.onScan(ScanEvent.Detected(b))
            assertEquals(QrScanStep.Detected, model.state.step)
            assertEquals(a.code, model.found, "the first code found waits; later ones are ignored")

            model.onIntent(QrIntent.ScanAgain)
            assertEquals(QrScanStep.Scanning, model.state.step)
            assertNull(model.found)
            model.onScan(ScanEvent.Detected(b))
            model.onIntent(QrIntent.TorchToggled)
            assertTrue(model.torchOn)
            model.onIntent(QrIntent.UseCode)
            assertEquals(listOf(b.code!!), chosen)

            model.onIntent(QrIntent.PrintableClicked)
            model.onIntent(QrIntent.Back)
            assertEquals(1, backs)
        }

    @Test
    fun `registration without the permission asks once, then shows camera unavailable with Fix`() =
        runTest {
            val permission = FakePermission(granted = false, answer = false)
            val model = QrRegistrationModel(permission, onCodeChosen = {}, onBack = {})
            assertTrue(model.state.cameraUnavailable)

            model.onShown()
            model.onShown()
            assertEquals(1, permission.requests, "closing the dialog resumes the screen; it never asks twice")
            assertTrue(model.state.cameraUnavailable)
            assertFalse(model.scanning)
            model.onIntent(QrIntent.FixCamera)
            assertEquals(1, permission.settingsOpened)

            permission.granted = true
            model.onShown()
            assertFalse(model.state.cameraUnavailable, "back from the settings with the permission")
            model.onScan(ScanEvent.CameraUnavailable)
            assertTrue(model.state.cameraUnavailable, "a camera that cannot start")
            model.onIntent(QrIntent.UseCode)
        }
}
