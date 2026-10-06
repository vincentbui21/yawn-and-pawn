package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.ui.checks.core
import com.yawnandpawn.app.ui.checks.toUi
import com.yawnandpawn.app.ui.checks.CheckType as UiCheckType

/** The UI check of a core [type], by its stable id, or null for one a user never sees (the Epic 1 placeholder). */
fun uiCheckType(type: CheckType): UiCheckType? = type.toUi()

/** The core check type a UI [type] stands for, when core has its plugin (not the camera checks before Story 3.10). */
fun coreCheckType(type: UiCheckType): CheckType? = type.core

/**
 * The Fallback check picker (Story 3.9): the core fallback choices (every pickable check without the camera, Math
 * first) that have a screen, in their order.
 */
fun fallbackPickerUiState(): FallbackPickerUiState = FallbackPickerUiState(options = CheckType.fallbackChoices.mapNotNull(::uiCheckType))
