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
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
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
    enum class Scene { WELCOME, HOTSPOT, BACKGROUND, ANDROID_BLUETOOTH, IPHONE, IPHONE_SETTINGS, TESLA_WIFI, TESLA_BROWSER, AUDIO, READY }

    private enum class Symbol { WIFI, BLUETOOTH, SHIELD, CHECK }
    private val palette = BrowserDesign.onboarding(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val bounds = RectF()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var currentScene = scene
    private var artwork = loadArtwork(scene)
    private var pairingDeviceName: String? = null

    fun setPairingDeviceName(name: String?) {
        pairingDeviceName = name?.takeIf { it.isNotBlank() }
        invalidate()
    }

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
            resolveSize((measuredWidth * (if (currentScene == Scene.IPHONE_SETTINGS) 330f else 200f) / 360f).roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val drawingHeight = if (currentScene == Scene.IPHONE_SETTINGS) 330f else 200f
        val scale = min(width / 360f, height / drawingHeight)
        val saved = canvas.save()
        canvas.translate((width - 360f * scale) / 2f, (height - drawingHeight * scale) / 2f)
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
            Scene.ANDROID_BLUETOOTH -> {
                device(canvas, R.drawable.tesair_android, 112f, 0f, 248f, 200f)
                badge(canvas, 247f, 126f, Symbol.BLUETOOTH)
            }
            Scene.IPHONE -> {
                connector(canvas, 132f, 100f, 228f, 100f)
                device(canvas, R.drawable.tesair_android, 20f, 4f, 147f, 195f)
                device(canvas, R.drawable.tesair_iphone, 213f, 4f, 340f, 195f)
                badge(canvas, 180f, 100f, Symbol.BLUETOOTH)
            }
            Scene.IPHONE_SETTINGS -> iphoneSettings(canvas)
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

    private fun iphoneSettings(canvas: Canvas) {
        device(canvas, R.drawable.tesair_iphone_pairing_frame, 72f, 0f, 288f, 322f)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        // The illustrated Settings screen uses iOS colors; the surrounding app keeps its own theme.
        val screen = if (dark) Color.BLACK else Color.rgb(242, 242, 247)
        val card = if (dark) Color.rgb(28, 28, 30) else Color.WHITE
        val ink = if (dark) Color.WHITE else Color.BLACK
        val secondary = Color.rgb(142, 142, 147)
        val blue = if (dark) Color.rgb(10, 132, 255) else Color.rgb(0, 122, 255)
        val saved = canvas.save()
        // Screen inset measured from the approved transparent frame, independent of any Android model.
        canvas.translate(115.2f, 19.9f)
        canvas.scale(129.4f / 133f, 282f / 288f)
        paint.style = Paint.Style.FILL
        paint.color = screen
        canvas.drawRoundRect(0f, 0f, 133f, 288f, 16.8f, 16.8f, paint)
        paint.color = Color.BLACK
        canvas.drawRoundRect(45f, 3.1f, 88f, 15.4f, 6f, 6f, paint)
        artText(canvas, "‹ ${context.getString(R.string.onboarding_iphone_settings_label)}", 8f, 45f, 11f, blue, 116f)
        paint.color = blue
        canvas.drawRoundRect(46.5f, 60f, 86.5f, 100f, 11f, 11f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.3f
        paint.color = Color.WHITE
        path.reset()
        path.moveTo(66.5f, 68f); path.lineTo(66.5f, 92f)
        path.lineTo(74.5f, 85f); path.lineTo(59.5f, 73f)
        path.moveTo(59.5f, 87f); path.lineTo(74.5f, 75f); path.lineTo(66.5f, 68f)
        canvas.drawPath(path, paint)
        val bluetoothLabel = context.getString(R.string.onboarding_iphone_bluetooth_label)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 17f
        artText(canvas, bluetoothLabel, (133f - paint.measureText(bluetoothLabel).coerceAtMost(116f)) / 2f,
            129f, 17f, ink, 116f, bold = true)
        paint.style = Paint.Style.FILL
        paint.color = card
        canvas.drawRoundRect(6f, 148f, 127f, 185f, 10f, 10f, paint)
        artText(canvas, bluetoothLabel, 13f, 172f, 11f, ink, 76f)
        paint.color = if (dark) Color.rgb(48, 209, 88) else Color.rgb(52, 199, 89)
        canvas.drawRoundRect(98f, 161f, 121f, 175f, 7f, 7f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(114f, 168f, 5.5f, paint)
        artText(canvas, context.getString(R.string.onboarding_iphone_devices_label), 7f, 213f, 8.5f, secondary, 117f)
        paint.color = card
        canvas.drawRoundRect(6f, 223f, 127f, 260f, 10f, 10f, paint)
        artText(canvas, pairingDeviceName.orEmpty(), 13f, 246f, 11f, ink, 107f, bold = true)
        paint.color = ink
        canvas.drawRoundRect(40f, 276f, 90f, 280f, 2f, 2f, paint)
        canvas.restoreToCount(saved)
    }

    private fun artText(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, maxWidth: Float? = null, bold: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        paint.color = color
        textPaint.set(paint)
        val shown = if (maxWidth == null) value else TextUtils.ellipsize(value, textPaint, maxWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, x, y, paint)
    }

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
            Scene.HOTSPOT, Scene.BACKGROUND, Scene.ANDROID_BLUETOOTH -> intArrayOf(R.drawable.tesair_android)
            Scene.IPHONE -> intArrayOf(R.drawable.tesair_android, R.drawable.tesair_iphone)
            Scene.IPHONE_SETTINGS -> intArrayOf(R.drawable.tesair_iphone_pairing_frame)
            Scene.AUDIO -> intArrayOf(R.drawable.tesair_iphone, R.drawable.tesair_display)
            else -> intArrayOf(R.drawable.tesair_display)
        }
        return ids.associateWith { image(resources, it) }
    }

    private companion object {
        // The four bundled device illustrations decode once, never during drawing.
        val images = SparseArray<Bitmap>(4)
        @Synchronized fun image(resources: Resources, id: Int): Bitmap = images[id] ?: requireNotNull(
            BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false }),
        ).also { images.put(id, it) }
    }
}
