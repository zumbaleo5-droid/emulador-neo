package com.emuneo.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Pantalla de juego. Vive en el proceso ":emu" (ver AndroidManifest.xml). */
class EmulatorActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_ROM = "rom"
        private const val CORE_FILE = "libfbneo_libretro_android.so"
    }

    private lateinit var gamepad: GamepadView
    private var started = false

    // Estado de entrada: táctil (siempre jugador 1) y mandos físicos por dispositivo.
    private var touchMask = 0
    private val devicePorts = HashMap<Int, Int>()
    private val keyMasks = HashMap<Int, Int>()
    private val axisMasks = HashMap<Int, Int>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()

        val rom = intent.getStringExtra(EXTRA_ROM) ?: return finish()
        val core = File(applicationInfo.nativeLibraryDir, CORE_FILE)
        when {
            !core.exists() -> return fail("No se encontró el core FinalBurn Neo en el APK. Vuelve a compilar con conexión a internet (ver README).")
            !NativeBridge.loadCore(core.absolutePath, Storage.system(this).absolutePath, Storage.saves(this).absolutePath) ->
                return fail("No se pudo iniciar el core FinalBurn Neo.")
            !NativeBridge.loadGame(rom) ->
                return fail("No se pudo cargar el juego. Comprueba que el zip es un romset compatible con FinalBurn Neo y que neogeo.zip está añadido.")
        }

        val surface = SurfaceView(this).apply { holder.addCallback(this@EmulatorActivity) }
        gamepad = GamepadView(this) { mask ->
            touchMask = mask
            push(0)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(AspectFrame(this@EmulatorActivity, NativeBridge.getAspectRatio()).apply { addView(surface) })
            addView(gamepad)
            addView(menuBar(), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            // Si el mando táctil está oculto (porque se usó un mando físico), tocar la pantalla lo vuelve a mostrar.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) gamepad.visibility = View.VISIBLE
                true
            }
        }
        setContentView(root)

        NativeBridge.start()
        started = true
    }

    private fun menuBar() = LinearLayout(this).apply {
        alpha = 0.6f
        fun add(label: String, action: () -> Unit) = addView(Button(context).apply {
            text = label
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { action() }
        })
        add("Guardar") { NativeBridge.saveState(); toast("Partida guardada") }
        add("Cargar") { NativeBridge.loadState(); toast("Cargando partida guardada") }
        add("Reiniciar") { NativeBridge.reset() }
        add("Salir") { finish() }
    }

    // --- Ciclo de vida -------------------------------------------------------

    override fun onResume() {
        super.onResume()
        enterFullscreen()
        if (started) NativeBridge.setPaused(false)
    }

    override fun onPause() {
        if (started) NativeBridge.setPaused(true)
        super.onPause()
    }

    override fun onDestroy() {
        if (started) NativeBridge.stop()
        super.onDestroy()
        // Cerramos el proceso ":emu" para que el siguiente juego empiece con el core limpio.
        Process.killProcess(Process.myPid())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        NativeBridge.setPaused(true)
        AlertDialog.Builder(this)
            .setMessage("¿Salir del juego?")
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNegativeButton("Seguir") { _, _ -> NativeBridge.setPaused(false) }
            .setOnCancelListener { NativeBridge.setPaused(false) }
            .show()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = NativeBridge.setSurface(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = NativeBridge.setSurface(holder.surface)
    override fun surfaceDestroyed(holder: SurfaceHolder) = NativeBridge.setSurface(null)

    // --- Mandos físicos (Bluetooth / USB) y teclado --------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val bit = keyToBit(event.keyCode)
        if (bit == 0 || !started) return super.dispatchKeyEvent(event)
        if (event.repeatCount > 0) return true

        val id = event.deviceId
        val old = keyMasks[id] ?: 0
        keyMasks[id] = when (event.action) {
            KeyEvent.ACTION_DOWN -> old or bit
            KeyEvent.ACTION_UP -> old and bit.inv()
            else -> old
        }
        onPhysicalInput(id)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!started || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.actionMasked != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        // La cruceta de muchos mandos llega como "hat"; si no, usamos el stick izquierdo.
        val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).takeIf { abs(it) > 0.5f } ?: event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).takeIf { abs(it) > 0.5f } ?: event.getAxisValue(MotionEvent.AXIS_Y)
        val l2 = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r2 = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))

        var mask = 0
        if (x < -0.5f) mask = mask or NativeBridge.LEFT
        if (x > 0.5f) mask = mask or NativeBridge.RIGHT
        if (y < -0.5f) mask = mask or NativeBridge.UP
        if (y > 0.5f) mask = mask or NativeBridge.DOWN
        if (l2 > 0.5f) mask = mask or NativeBridge.L2
        if (r2 > 0.5f) mask = mask or NativeBridge.R2
        if (axisMasks[event.deviceId] != mask) {
            axisMasks[event.deviceId] = mask
            onPhysicalInput(event.deviceId)
        }
        return true
    }

    private fun onPhysicalInput(deviceId: Int) {
        // Primer mando que se usa = jugador 1, el siguiente = jugador 2.
        val port = devicePorts.getOrPut(deviceId) { min(devicePorts.size, 1) }
        if (gamepad.visibility == View.VISIBLE) {
            gamepad.visibility = View.GONE
            touchMask = 0
            push(0)
        }
        push(port)
    }

    private fun push(port: Int) {
        var mask = if (port == 0) touchMask else 0
        for ((dev, p) in devicePorts) {
            if (p == port) mask = mask or (keyMasks[dev] ?: 0) or (axisMasks[dev] ?: 0)
        }
        NativeBridge.setButtons(port, mask)
    }

    /** Disposición tipo Xbox: el botón de abajo es A de Neo Geo, derecha B, izquierda C y arriba D. */
    private fun keyToBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> NativeBridge.UP
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> NativeBridge.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> NativeBridge.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> NativeBridge.RIGHT
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_J -> NativeBridge.NEO_A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_K -> NativeBridge.NEO_B
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_U -> NativeBridge.NEO_C
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_I -> NativeBridge.NEO_D
        KeyEvent.KEYCODE_BUTTON_L1 -> NativeBridge.L    // macro C+D
        KeyEvent.KEYCODE_BUTTON_R1 -> NativeBridge.R    // macro A+B
        KeyEvent.KEYCODE_BUTTON_L2 -> NativeBridge.L2   // macro B+C+D
        KeyEvent.KEYCODE_BUTTON_R2 -> NativeBridge.R2   // macro A+B+C
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> NativeBridge.NEO_START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_5 -> NativeBridge.NEO_COIN
        else -> 0
    }

    // --- Utilidades ----------------------------------------------------------

    private fun fail(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Aceptar") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun enterFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    /** Centra su único hijo con la proporción de pantalla del juego (4:3 en Neo Geo). */
    private class AspectFrame(context: Context, private val aspect: Float) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            var cw = w
            var ch = (w / aspect).toInt()
            if (ch > h) {
                ch = h
                cw = (h * aspect).toInt()
            }
            for (i in 0 until childCount) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val x = (r - l - c.measuredWidth) / 2
                val y = (b - t - c.measuredHeight) / 2
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }
    }
}
