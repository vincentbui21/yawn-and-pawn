package com.yawnandpawn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.yawnandpawn.app.ui.theme.PpsThemeMode

@Preview(name = "Light")
@Composable
private fun AppLightPreview() {
    App(themeMode = PpsThemeMode.Light)
}

@Preview(name = "Dark")
@Composable
private fun AppDarkPreview() {
    App(themeMode = PpsThemeMode.Dark)
}
