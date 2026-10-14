package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-w360dp-h330dp-notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserOnboardingArtViewTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun settingsScreenFollowsBothThemesWhilePreservingThePhoneBezel() {
        for (theme in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("en-w360dp-h330dp-$theme")
            val palette = BrowserDesign.onboarding(app)
            val rendered = render("Test Android")
            val screen = if (theme == "night") Color.BLACK else Color.rgb(242, 242, 247)
            val card = if (theme == "night") Color.rgb(28, 28, 30) else Color.WHITE
            assertEquals(screen, rendered.getPixel(118, 95))
            assertEquals(card, rendered.getPixel(125, 177))
            assertNotEquals("The screen must not cover the left bezel", palette.background, rendered.getPixel(110, 177))
            val file = File("build/outputs/onboarding-art/iphone-settings-${if (theme == "night") "dark" else "light"}.png")
            requireNotNull(file.parentFile).mkdirs()
            file.outputStream().use { assertTrue(rendered.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    @Test fun theProvidedDeviceNameChangesOnlyItsReservedRow() {
        val first = render("First Android")
        val second = render("Second Android")
        var changed = 0
        for (y in 0 until first.height) for (x in 0 until first.width) {
            if (first.getPixel(x, y) != second.getPixel(x, y)) {
                assertTrue("Device-name text escaped its row at $x,$y", x in 120..242 && y in 235..280)
                changed++
            }
        }
        assertTrue("The graphic must use the provided name", changed > 0)
    }

    private fun render(name: String): Bitmap {
        val view = BrowserOnboardingArtView(app, BrowserOnboardingArtView.Scene.IPHONE_SETTINGS)
        view.setPairingDeviceName(name)
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(330, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 360, 330)
        return Bitmap.createBitmap(360, 330, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(BrowserDesign.onboarding(app).background)
            view.draw(canvas)
        }
    }
}
