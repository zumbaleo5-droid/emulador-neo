package com.emuneo.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Mando táctil multitáctil: cruceta de 8 direcciones a la izquierda, botones A B C D
 * a la derecha con la disposición en arco de Neo Geo, y COIN / START abajo en el centro.
 * Cada vez que cambia algo llama a [onChange] con el bitmask del RetroPad.
 */
class GamepadView(context: Context, private val onChange: (Int) -> Unit) : View(context) {

    private class Btn(val label: String, val mask: Int, var x: Float = 0f, var y: Float = 0f, var r: Float = 0f)

    private val buttons = listOf(
        Btn("A", NativeBridge.NEO_A),
        Btn("B", NativeBridge.NEO_B),
        Btn("C", NativeBridge.NEO_C),
        Btn("D", NativeBridge.NEO_D),
        Btn("COIN", NativeBridge.NEO_COIN),
        Btn("START", NativeBridge.NEO_START),
    )
    private var padX = 0f
    private var padY = 0f
    private var padR = 0f
    private var mask = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val neoColors = intArrayOf(
        Color.argb(110, 230, 60, 60),   // A rojo
        Color.argb(110, 240, 200, 40),  // B amarillo
        Color.argb(110, 60, 200, 90),   // C verde
        Color.argb(110, 60, 120, 230),  // D azul
    ).map { c -> Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c } }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val u = min(w, h) / 10f  // unidad de tamaño
        padR = u * 1.9f
        padX = u * 0.6f + padR
        padY = h - u * 0.6f - padR

        val br = u * 0.75f
        val bx = w - u * 5.0f
        val by = h - u * 1.5f
        // Arco de Neo Geo: A abajo-izquierda, D arriba-derecha
        val pos = arrayOf(0f to 0.6f, 1.4f to -0.1f, 2.8f to -0.7f, 4.0f to -1.9f)
        buttons.take(4).forEachIndexed { i, b ->
            b.x = bx + pos[i].first * u
            b.y = by + pos[i].second * u
            b.r = br
        }
        buttons[4].apply { x = w / 2f - u * 1.3f; y = h - u * 0.7f; r = u * 0.5f }
        buttons[5].apply { x = w / 2f + u * 1.3f; y = h - u * 0.7f; r = u * 0.5f }
        text.textSize = u * 0.45f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(padX, padY, padR, fill)
        val dirs = mask and (NativeBridge.UP or NativeBridge.DOWN or NativeBridge.LEFT or NativeBridge.RIGHT)
        if (dirs != 0) {
            val dx = (if (mask and NativeBridge.RIGHT != 0) 1 else 0) - (if (mask and NativeBridge.LEFT != 0) 1 else 0)
            val dy = (if (mask and NativeBridge.DOWN != 0) 1 else 0) - (if (mask and NativeBridge.UP != 0) 1 else 0)
            canvas.drawCircle(padX + dx * padR * 0.55f, padY + dy * padR * 0.55f, padR * 0.35f, pressed)
        } else {
            canvas.drawCircle(padX, padY, padR * 0.35f, fill)
        }
        buttons.forEachIndexed { i, b ->
            val paint = if (mask and b.mask != 0) pressed else neoColors.getOrElse(i) { fill }
            canvas.drawCircle(b.x, b.y, b.r, paint)
            canvas.drawText(b.label, b.x, b.y + text.textSize / 3, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var newMask = 0
        val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (!ended) {
            for (i in 0 until event.pointerCount) {
                // El dedo que se levanta en ACTION_POINTER_UP sigue en la lista: lo ignoramos.
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) continue
                newMask = newMask or hit(event.getX(i), event.getY(i))
            }
        }
        if (newMask != mask) {
            if (newMask and mask.inv() and BUTTONS != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            mask = newMask
            onChange(mask)
            invalidate()
        }
        return true
    }

    private fun hit(x: Float, y: Float): Int {
        // Cruceta: zona generosa (1.5x el radio) con zona muerta en el centro.
        val dx = x - padX
        val dy = y - padY
        val dist = hypot(dx, dy)
        if (dist < padR * 1.5f) {
            if (dist < padR * 0.25f) return 0
            val angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
            val sector = ((angle + 22.5) / 45).toInt() % 8  // 0 = derecha, en sentido antihorario
            return DIRECTIONS[sector]
        }
        // Botones: el más cercano dentro de 1.3x su radio, así es fácil pulsar dos a la vez entre ellos.
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (b in buttons) {
            val d = hypot(x - b.x, y - b.y)
            if (d < b.r * 1.3f && d < bestDist) {
                best = b.mask
                bestDist = d
            }
        }
        return best
    }

    private companion object {
        val BUTTONS = NativeBridge.NEO_A or NativeBridge.NEO_B or NativeBridge.NEO_C or NativeBridge.NEO_D
        val DIRECTIONS = intArrayOf(
            NativeBridge.RIGHT,
            NativeBridge.RIGHT or NativeBridge.UP,
            NativeBridge.UP,
            NativeBridge.UP or NativeBridge.LEFT,
            NativeBridge.LEFT,
            NativeBridge.LEFT or NativeBridge.DOWN,
            NativeBridge.DOWN,
            NativeBridge.DOWN or NativeBridge.RIGHT,
        )
    }
}
