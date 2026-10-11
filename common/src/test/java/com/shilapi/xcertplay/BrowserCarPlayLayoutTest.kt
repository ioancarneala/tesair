package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import kotlin.math.abs
import kotlin.math.ln
import org.junit.Assert.*
import org.junit.Test

class BrowserCarPlayLayoutTest {
    @Test fun defaultQualityPreservesTheWorkingSessionAndItsNativeAreas() {
        val layout = BrowserCarPlayLayout.create(1920, 1080)
        assertEquals(1920, layout.canvasWidth)
        assertEquals(1440, layout.canvasHeight)
        assertEquals(0, layout.initialIndex)
        assertEquals(0, layout.currentIndex)
        // Preserve the announced area order and pixel rounding.
        val expected = """
            1920x1080 1920x1200 1920x1440 1920x960 648x1440 680x1440
            714x1440 750x1440 786x1440 826x1440 868x1440 910x1440
            956x1440 1004x1440 1054x1440 1108x1440 1162x1440 1220x1440
            1282x1440 1346x1440 1414x1440 1484x1440 1558x1440 1636x1440
            1718x1440 1804x1440 1894x1440 1920x1388 1920x1322 1920x1258
            1920x1198 1920x1142 1920x1088 1920x1036 1920x986 1920x940
            1920x894 1920x852 1920x812 1920x772 1920x736 1920x700
            1920x668 1920x636 1920x606 1920x600
        """.trim().split(Regex("\\s+"))
        assertEquals(expected, layout.areas.map { "${it.width}x${it.height}" })
        assertEquals(layout.areas, BrowserCarPlayLayout.create(1920, 1080, 100).areas)
    }

    @Test fun defaultCanvasStillFollowsTheFirstViewportWithinTheExistingBounds() {
        assertEquals(BrowserCarPlayLayout.CanvasSize(1920, 1440), BrowserCarPlayLayout.canvasSize(1280))
        assertEquals(BrowserCarPlayLayout.CanvasSize(2048, 1440), BrowserCarPlayLayout.canvasSize(2049))
        assertEquals(BrowserCarPlayLayout.CanvasSize(2560, 1440), BrowserCarPlayLayout.canvasSize(3840))
    }

    @Test fun higherQualityAddsPixelsWithoutChangingTheInitialLayoutShape() {
        val normal = BrowserCarPlayLayout.create(1920, 1080)
        val sharper = BrowserCarPlayLayout.create(1920, 1080, 120)
        assertEquals(2304, sharper.canvasWidth)
        assertEquals(1728, sharper.canvasHeight)
        assertEquals(2304, sharper.currentArea.width)
        assertEquals(1296, sharper.currentArea.height)
        assertEquals(aspect(normal), aspect(sharper), 0.000001)
    }

    @Test fun lowerQualityReducesTheCanvasAndActiveVideoTogether() {
        val layout = BrowserCarPlayLayout.create(1920, 1080, 50)
        assertEquals(960, layout.canvasWidth)
        assertEquals(720, layout.canvasHeight)
        assertEquals(960, layout.currentArea.width)
        assertEquals(540, layout.currentArea.height)
    }

    @Test fun maximumQualityKeepsBothAxesAtTheSameScaleWithinFourK() {
        val narrow = BrowserCarPlayLayout.create(1920, 1080, 160)
        assertEquals(2880, narrow.canvasWidth)
        assertEquals(2160, narrow.canvasHeight)
        assertEquals(2880, narrow.currentArea.width)
        assertEquals(1620, narrow.currentArea.height)

        val wide = BrowserCarPlayLayout.create(2560, 1440, 160)
        assertEquals(3840, wide.canvasWidth)
        assertEquals(2160, wide.canvasHeight)
        assertEquals(3840, wide.currentArea.width)
        assertEquals(2160, wide.currentArea.height)
        assertEquals(wide.areas, BrowserCarPlayLayout.create(2560, 1440, 150).areas)
    }

    @Test fun malformedQualityAndExtremeViewportsStayBoundedAndEven() {
        assertEquals(BrowserCarPlayLayout.canvasSize(1920, 30), BrowserCarPlayLayout.canvasSize(1920, Int.MIN_VALUE))
        assertEquals(BrowserCarPlayLayout.canvasSize(2560, 160), BrowserCarPlayLayout.canvasSize(Int.MAX_VALUE, Int.MAX_VALUE))
        for ((width, height) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, 2049 to 1001)) {
            for (quality in listOf(Int.MIN_VALUE, 30, 75, 100, 125, 160, Int.MAX_VALUE)) {
                val layout = BrowserCarPlayLayout.create(width, height, quality)
                assertTrue(layout.canvasWidth in 2..3840)
                assertTrue(layout.canvasHeight in 2..2160)
                assertEquals(0, layout.canvasWidth % 2)
                assertEquals(0, layout.canvasHeight % 2)
                layout.areas.forEach { area ->
                    assertEquals(0, area.originX)
                    assertEquals(0, area.originY)
                    assertTrue(area.width in 2..layout.canvasWidth)
                    assertTrue(area.height in 2..layout.canvasHeight)
                    assertEquals(0, area.width % 2)
                    assertEquals(0, area.height % 2)
                    assertEquals(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, area.dockEdge)
                }
            }
        }
    }

    @Test fun everyQualityCanChangeNativeLayoutsInsideTheSameSessionCanvas() {
        for (quality in listOf(30, 50, 100, 120, 160)) {
            val layout = BrowserCarPlayLayout.create(1920, 1080, quality)
            val canvas = layout.canvasWidth to layout.canvasHeight
            val declaredAreas = layout.areas
            for (width in 450..3200 step 25) {
                layout.use(layout.indexFor(width, 1000))
                // The same bounded native aspect grid remains available at every quality.
                assertTrue(abs(ln(aspect(layout) / (width / 1000.0))) < ln(1.035))
                assertEquals(canvas, layout.canvasWidth to layout.canvasHeight)
                assertSame(declaredAreas, layout.areas)
            }
            layout.use(layout.indexFor(1920, 1080))
            assertEquals(layout.initialIndex, layout.currentIndex)
        }
    }

    @Test fun qualityScalingPreservesEveryOriginalNativeShapeWithinPixelRounding() {
        val normal = BrowserCarPlayLayout.create(2049, 1001)
        for (quality in listOf(30, 50, 120, 160)) {
            val scaled = BrowserCarPlayLayout.create(2049, 1001, quality)
            normal.areas.forEach { original ->
                scaled.use(scaled.indexFor(original.width, original.height))
                val error = abs(ln(aspect(scaled) / (original.width.toDouble() / original.height)))
                assertTrue("Quality $quality changed the layout shape: $original", error < ln(1.012))
            }
        }
    }

    @Test fun invalidViewportDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BrowserCarPlayLayout.create(0, 1080) }
        assertThrows(IllegalArgumentException::class.java) { BrowserCarPlayLayout.create(1920, -1) }
        assertThrows(IllegalArgumentException::class.java) { BrowserCarPlayLayout.canvasSize(0) }
    }

    private fun aspect(layout: BrowserCarPlayLayout) =
        layout.currentArea.width.toDouble() / layout.currentArea.height
}
