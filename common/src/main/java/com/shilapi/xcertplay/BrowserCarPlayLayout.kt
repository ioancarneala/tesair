package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import java.util.Collections
import kotlin.math.abs
import kotlin.math.ln

/** A session's fixed encoded canvas and the native CarPlay layouts declared before connection. */
internal class BrowserCarPlayLayout private constructor(
    val canvasWidth: Int,
    val canvasHeight: Int,
    areas: List<AirPlayViewArea>,
) {
    val areas: List<AirPlayViewArea> = Collections.unmodifiableList(ArrayList(areas))
    val initialIndex: Int = 0
    @Volatile var currentIndex: Int = initialIndex
        private set
    val currentArea: AirPlayViewArea get() = areas[currentIndex]

    data class CanvasSize(val width: Int, val height: Int)

    fun indexFor(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        val aspect = width.toDouble() / height
        return areas.indices.minByOrNull { index ->
            val area = areas[index]
            abs(ln((area.width.toDouble() / area.height) / aspect))
        }!!
    }

    /** Called only once the view-area command has been queued for the current CarPlay session. */
    fun use(index: Int) {
        require(index in areas.indices)
        currentIndex = index
    }

    companion object {
        const val MIN_ASPECT = 0.45
        const val MAX_ASPECT = 3.2
        private const val ASPECT_STEP = 1.05
        private const val MAX_WIDTH = 2560
        private const val CANVAS_HEIGHT = 1440
        private const val MAX_ENCODED_WIDTH = 3840
        private const val MAX_ENCODED_HEIGHT = 2160

        /**
         * Quality scales the session canvas, never the browser's layout or its resize commands.
         * The 4K ceiling currently limits 160% to 150%; keep both axes at the same scale.
         */
        fun canvasSize(width: Int, resolutionPercent: Int = 100): CanvasSize {
            require(width > 0)
            val baseWidth = maxOf(1920, minOf(MAX_WIDTH, width)) and 1.inv()
            val percent = resolutionPercent.coerceIn(30, 160).coerceAtMost(
                minOf(MAX_ENCODED_WIDTH * 100 / baseWidth, MAX_ENCODED_HEIGHT * 100 / CANVAS_HEIGHT),
            )
            return CanvasSize(
                width = (baseWidth * percent / 100) and 1.inv(),
                height = (CANVAS_HEIGHT * percent / 100) and 1.inv(),
            )
        }

        fun create(width: Int, height: Int, resolutionPercent: Int = 100): BrowserCarPlayLayout {
            require(width > 0 && height > 0)
            val (canvasWidth, canvasHeight) = canvasSize(width, resolutionPercent)
            fun area(aspect: Double): AirPlayViewArea {
                val w: Double
                val h: Double
                if (aspect >= canvasWidth.toDouble() / canvasHeight) {
                    w = canvasWidth.toDouble(); h = w / aspect
                } else {
                    h = canvasHeight.toDouble(); w = h * aspect
                }
                return AirPlayViewArea(
                    width = (w.toInt() and 1.inv()).coerceIn(2, canvasWidth),
                    height = (h.toInt() and 1.inv()).coerceIn(2, canvasHeight),
                    dockEdge = AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE,
                )
            }
            // Keep the first viewport's exact aspect in addition to a small bounded ratio grid.
            // Every rectangle stays inside the same landscape canvas; portrait never needs a square.
            val areas = mutableListOf(area(width.toDouble() / height))
            listOf(16.0 / 9, 16.0 / 10, 4.0 / 3, 2.0).forEach { areas += area(it) }
            var ratio = MIN_ASPECT
            while (ratio < MAX_ASPECT) {
                areas += area(ratio)
                ratio *= ASPECT_STEP
            }
            areas += area(MAX_ASPECT)
            return BrowserCarPlayLayout(canvasWidth, canvasHeight, areas.distinct())
        }
    }
}
