package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.SparseArray
import android.view.View
import com.shilapi.xcertplay.host.R
import kotlin.math.min
import kotlin.math.roundToInt

/** Bundled illustrations are decorative; instructions and connection details remain live text. */
internal class BrowserOnboardingArtView(
    context: Context,
    scene: Scene,
) : View(context) {
    enum class Scene { WELCOME, HOTSPOT, BACKGROUND, IPHONE, TESLA_WIFI, TESLA_BROWSER, AUDIO, READY }

    private enum class Symbol { WIFI, BLUETOOTH, SHIELD, CHECK }
    private val palette = BrowserDesign.onboarding(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val bounds = RectF()
    private var currentScene = scene
    private var artwork = loadArtwork(scene)

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setScene(scene: Scene) {
        if (currentScene == scene) return
        artwork = loadArtwork(scene)
        currentScene = scene
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize(BrowserDesign.dp(context, 360), widthMeasureSpec)
        setMeasuredDimension(measuredWidth,
            resolveSize((measuredWidth * 200f / 360f).roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val scale = min(width / 360f, height / 200f)
        val saved = canvas.save()
        canvas.translate((width - 360f * scale) / 2f, (height - 200f * scale) / 2f)
        canvas.scale(scale, scale)
        when (currentScene) {
            Scene.WELCOME -> {
                device(canvas, R.drawable.tesair_display, 85f, 10f, 360f, 186f)
                device(canvas, R.drawable.tesair_android, 0f, 61f, 88f, 193f)
                device(canvas, R.drawable.tesair_iphone, 44f, 40f, 148f, 196f)
            }
            Scene.HOTSPOT -> device(canvas, R.drawable.tesair_android, 112f, 0f, 248f, 200f)
            Scene.BACKGROUND -> {
                device(canvas, R.drawable.tesair_android, 112f, 0f, 248f, 200f)
                badge(canvas, 247f, 126f, Symbol.SHIELD)
            }
            Scene.IPHONE -> {
                connector(canvas, 132f, 100f, 228f, 100f)
                device(canvas, R.drawable.tesair_android, 20f, 4f, 147f, 195f)
                device(canvas, R.drawable.tesair_iphone, 213f, 4f, 340f, 195f)
                badge(canvas, 180f, 100f, Symbol.BLUETOOTH)
            }
            Scene.TESLA_WIFI -> {
                display(canvas)
                badge(canvas, 324f, 36f, Symbol.WIFI)
            }
            Scene.TESLA_BROWSER -> display(canvas)
            Scene.AUDIO -> {
                connector(canvas, 88f, 101f, 150f, 101f)
                device(canvas, R.drawable.tesair_display, 126f, 32f, 360f, 180f)
                device(canvas, R.drawable.tesair_iphone, 0f, 3f, 122f, 190f)
                badge(canvas, 122f, 101f, Symbol.BLUETOOTH)
            }
            Scene.READY -> {
                display(canvas)
                badge(canvas, 324f, 36f, Symbol.CHECK)
            }
        }
        canvas.restoreToCount(saved)
    }

    private fun display(canvas: Canvas) = device(canvas, R.drawable.tesair_display, 8f, 0f, 352f, 200f)

    private fun device(canvas: Canvas, id: Int, left: Float, top: Float, right: Float, bottom: Float) {
        val bitmap = artwork.getValue(id)
        val scale = min((right - left) / bitmap.width, (bottom - top) / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val x = (left + right - w) / 2f
        val y = (top + bottom - h) / 2f
        bounds.set(x, y, x + w, y + h)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }

    private fun connector(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = palette.line
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    private fun badge(canvas: Canvas, x: Float, y: Float, symbol: Symbol) {
        paint.style = Paint.Style.FILL
        paint.color = palette.background
        canvas.drawCircle(x, y, 23f, paint)
        paint.color = palette.field
        canvas.drawCircle(x, y, 20f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = palette.accent
        path.reset()
        when (symbol) {
            Symbol.BLUETOOTH -> {
                path.moveTo(x, y - 12f); path.lineTo(x, y + 12f)
                path.lineTo(x + 8f, y + 5f); path.lineTo(x - 7f, y - 7f)
                path.moveTo(x - 7f, y + 7f); path.lineTo(x + 8f, y - 5f)
                path.lineTo(x, y - 12f)
            }
            Symbol.WIFI -> {
                path.moveTo(x - 11f, y - 3f); path.quadTo(x, y - 13f, x + 11f, y - 3f)
                path.moveTo(x - 6f, y + 2f); path.quadTo(x, y - 4f, x + 6f, y + 2f)
                canvas.drawCircle(x, y + 8f, 1f, paint)
            }
            Symbol.SHIELD -> {
                path.moveTo(x, y - 12f); path.lineTo(x + 10f, y - 8f)
                path.lineTo(x + 9f, y + 3f); path.quadTo(x + 7f, y + 9f, x, y + 13f)
                path.quadTo(x - 7f, y + 9f, x - 9f, y + 3f)
                path.lineTo(x - 10f, y - 8f); path.close()
                path.moveTo(x - 4f, y); path.lineTo(x - 1f, y + 3f); path.lineTo(x + 5f, y - 3f)
            }
            Symbol.CHECK -> {
                path.moveTo(x - 8f, y); path.lineTo(x - 2f, y + 6f); path.lineTo(x + 9f, y - 6f)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun loadArtwork(scene: Scene): Map<Int, Bitmap> {
        val ids = when (scene) {
            Scene.WELCOME -> intArrayOf(R.drawable.tesair_android, R.drawable.tesair_iphone, R.drawable.tesair_display)
            Scene.HOTSPOT, Scene.BACKGROUND -> intArrayOf(R.drawable.tesair_android)
            Scene.IPHONE -> intArrayOf(R.drawable.tesair_android, R.drawable.tesair_iphone)
            Scene.AUDIO -> intArrayOf(R.drawable.tesair_iphone, R.drawable.tesair_display)
            else -> intArrayOf(R.drawable.tesair_display)
        }
        return ids.associateWith { image(resources, it) }
    }

    private companion object {
        // Three density-independent WebP files share a bounded cache; nothing decodes during drawing.
        val images = SparseArray<Bitmap>(3)
        @Synchronized fun image(resources: Resources, id: Int): Bitmap = images[id] ?: requireNotNull(
            BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false }),
        ).also { images.put(id, it) }
    }
}
