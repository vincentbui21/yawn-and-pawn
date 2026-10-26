package com.yawnandpawn.app.ui.format

import java.text.NumberFormat
import java.util.Locale

actual fun formatPercent(percent: Int): String = NumberFormat.getPercentInstance(Locale.getDefault()).format(percent / PERCENT)

private const val PERCENT = 100.0
