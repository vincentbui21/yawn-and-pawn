package com.yawnandpawn.app.debug.preview

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.symbol_alarm_fill1
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.theme.SunrisePpsColors
import org.jetbrains.compose.resources.painterResource
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/*
 * Play Store graphics for the closed test (docs/store/README.md): the phone screenshots, the feature graphic and the
 * 512 px icon, rendered from the real composables with the design preview's sample data. These are not regression
 * tests: they only run when YP_STORE_GRAPHICS=1, so the normal test run skips them.
 *
 *   YP_STORE_GRAPHICS=1 ./gradlew :androidApp:testDebugUnitTest --tests "*StoreGraphics*" --rerun
 */

private const val STORE_DIR = "../docs/store"

private fun storeGraphicsRequested() =
    assumeTrue("set YP_STORE_GRAPHICS=1 to render the store graphics", System.getenv("YP_STORE_GRAPHICS") == "1")

/** Writes [bitmap] as a PNG, with an alpha channel only when [alpha] (Play wants none on screenshots and the feature graphic). */
private fun writePng(
    bitmap: Bitmap,
    path: String,
    alpha: Boolean,
) {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val image = BufferedImage(bitmap.width, bitmap.height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
    val file = File(path)
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
}

private fun render(
    composeRule: androidx.compose.ui.test.junit4.ComposeTestRule,
    path: String,
    alpha: Boolean = false,
    content: @Composable () -> Unit,
) {
    ActivityScenario.launch(PreviewActivity::class.java).use { scenario ->
        scenario.onActivity { it.setContent(content = content) }
        composeRule.waitForIdle()
        writePng(composeRule.onRoot().captureToImage().asAndroidBitmap(), path, alpha)
    }
}

/** Lays [content] out at [width] x [height] and draws it at [scale], taking only the scaled size. */
private fun Modifier.scaledScreen(
    width: Dp,
    height: Dp,
    scale: Float,
) = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixed(width.roundToPx(), height.roundToPx()))
    layout((placeable.width * scale).toInt(), (placeable.height * scale).toInt()) {
        placeable.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

private val PHONE_WIDTH = 360.dp
private val PHONE_HEIGHT = 800.dp

/** One store screenshot: a design preview state and its caption. */
data class StoreShot(
    val file: String,
    val previewId: String,
    val mode: PpsThemeMode,
    val caption: String,
) {
    val item: PreviewItem get() = PreviewCatalog.items.single { it.id == previewId }

    override fun toString(): String = file
}

/**
 * The phone screenshots at 1080 x 2400 px (360 x 800 dp at 3x): each state alone in `screenshots/`, and with a plain
 * caption above a smaller copy of the screen in `screenshots/captioned/`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xxhdpi")
class StoreGraphicsScreenshotTest(
    private val shot: StoreShot,
) {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Before
    fun requested() = storeGraphicsRequested()

    @Test
    fun `renders the plain screenshot`() =
        render(composeRule, "$STORE_DIR/screenshots/${shot.file}.png") {
            PreviewFrame(mode = shot.mode, largeFont = false) { shot.item.render(false) }
        }

    @Test
    fun `renders the captioned screenshot`() =
        render(composeRule, "$STORE_DIR/screenshots/captioned/${shot.file}.png") {
            PreviewFrame(mode = shot.mode, largeFont = false) {
                PpsTheme(mode = shot.mode, wake = shot.item.wake) { Captioned(shot) }
            }
        }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> =
            listOf(
                StoreShot("01-ringing", "ringing_first", PpsThemeMode.Light, "Every snooze costs real money."),
                StoreShot("02-snooze-confirm", "sheet_confirm", PpsThemeMode.Light, "You see the price before you pay."),
                StoreShot("03-check-math", "check_math", PpsThemeMode.Light, "Getting up is free. Just finish a quick check."),
                StoreShot("04-home", "home_list", PpsThemeMode.Dark, "Your alarms and your streak at a glance."),
                StoreShot("05-progress", "progress_full", PpsThemeMode.Light, "Every morning on your calendar."),
                StoreShot("06-success", "success_on_time", PpsThemeMode.Light, "Up on time. Keep the streak going."),
            ).map { arrayOf(it) }
    }
}

private const val CAPTIONED_SCALE = 0.78f

@Composable
private fun Captioned(shot: StoreShot) {
    val colors = PpsTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.gradientTop, colors.bg))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(152.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = shot.caption,
                style = PpsTheme.typography.headline,
                color = colors.text,
                textAlign = TextAlign.Center,
            )
        }
        val shape = PpsTheme.shapes.lg
        Box(Modifier.clip(shape).border(1.dp, colors.glassEdge, shape)) {
            Box(Modifier.scaledScreen(PHONE_WIDTH, PHONE_HEIGHT, CAPTIONED_SCALE)) { shot.item.render(false) }
        }
    }
}

/** The feature graphic (1024 x 500, no alpha) and the 512 px app icon (32-bit). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreGraphicsArtTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Before
    fun requested() = storeGraphicsRequested()

    @Test
    @Config(qualifiers = "w1024dp-h500dp-mdpi")
    fun `renders the feature graphic`() =
        render(composeRule, "$STORE_DIR/feature-graphic.png") {
            PreviewFrame(mode = PpsThemeMode.Light, largeFont = false) { PpsTheme(wake = true) { FeatureGraphic() } }
        }

    @Test
    @Config(qualifiers = "w512dp-h512dp-mdpi")
    fun `renders the app icon`() =
        render(composeRule, "$STORE_DIR/icon.png", alpha = true) {
            PreviewFrame(mode = PpsThemeMode.Light, largeFont = false) { AppIcon() }
        }
}

private const val FEATURE_SCREEN_SCALE = 0.62f

@Composable
private fun FeatureGraphic() {
    val colors = PpsTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(colors.gradientTop, colors.bg)))
                .padding(start = 72.dp, end = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.width(520.dp)) {
            Text("Yawn & Pawn", style = PpsTheme.typography.display, color = colors.text)
            Spacer(Modifier.height(16.dp))
            Text("Every snooze costs real money.", style = PpsTheme.typography.headline, color = colors.text)
            Spacer(Modifier.height(8.dp))
            Text("Waking up is free.", style = PpsTheme.typography.headline, color = colors.accentText)
        }
        // A crop of the real ringing screen: its lower part, with the time and both buttons.
        val shape = PpsTheme.shapes.lg
        Box(
            Modifier
                .align(Alignment.Bottom)
                .size(PHONE_WIDTH * FEATURE_SCREEN_SCALE, 436.dp)
                .clip(shape)
                .border(1.dp, colors.glassEdge, shape),
        ) {
            Box(
                Modifier
                    .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                    .scaledScreen(PHONE_WIDTH, PHONE_HEIGHT, FEATURE_SCREEN_SCALE),
            ) {
                PreviewCatalog.items.single { it.id == "ringing_first" }.render(false)
            }
        }
    }
}

/** The icon concept: the app's filled alarm symbol in Sunrise cream on the Sunrise accent, full bleed (Play masks it). */
@Composable
private fun AppIcon() {
    Box(Modifier.fillMaxSize().background(SunrisePpsColors.accent), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(Res.drawable.symbol_alarm_fill1),
            contentDescription = null,
            modifier = Modifier.size(300.dp),
            colorFilter = ColorFilter.tint(SunrisePpsColors.bg),
        )
    }
}
