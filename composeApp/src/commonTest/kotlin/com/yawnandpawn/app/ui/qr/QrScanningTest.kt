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
        assertEquals(a, frames.frame(listOf(b, a)))
        assertNull(frames.frame(listOf(a)), "the streak starts again once reported")
        assertNull(frames.frame(listOf(a)))
        assertEquals(a, frames.frame(listOf(a)))
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
        assertEquals(b, frames.frame(listOf(b)))
        assertNull(frames.frame(listOf(ScanResult(CodeFormat.Aztec, "hallway"))), "the same text in another format is another code")
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
