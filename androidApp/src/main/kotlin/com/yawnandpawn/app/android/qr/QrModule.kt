package com.yawnandpawn.app.android.qr

import com.yawnandpawn.app.ui.qr.CameraPermission
import com.yawnandpawn.app.ui.qr.CodeScanner
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of Story 3.10, included by `appModule`: the CameraX + ML Kit [CodeScanner] (tests replace it with
 * `FakeCodeScanner`) and the camera permission (one instance, which `MainActivity` attaches its launcher to).
 */
fun qrModule(): Module =
    module {
        single<CodeScanner> { CameraXCodeScanner(androidContext(), get()) }
        single { AndroidCameraPermission(androidContext(), get()) }
        single<CameraPermission> { get<AndroidCameraPermission>() }
    }
