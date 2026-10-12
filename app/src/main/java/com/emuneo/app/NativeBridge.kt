package com.emuneo.app

import android.view.Surface

/** Puente con el frontend libretro en C++ (app/src/main/cpp/frontend.cpp). */
object NativeBridge {
    init {
        System.loadLibrary("neoemu")
    }

    // Bits del RetroPad de libretro (RETRO_DEVICE_ID_JOYPAD_*).
    const val B = 1 shl 0
    const val Y = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val UP = 1 shl 4
    const val DOWN = 1 shl 5
    const val LEFT = 1 shl 6
    const val RIGHT = 1 shl 7
    const val A = 1 shl 8
    const val X = 1 shl 9
    const val L = 1 shl 10
    const val R = 1 shl 11
    const val L2 = 1 shl 12
    const val R2 = 1 shl 13

    // Cómo asigna FinalBurn Neo los botones de Neo Geo en el RetroPad (modo "Classic").
    // Las combinaciones son macros del propio core: AB = R, CD = L, ABC = R2, BCD = L2.
    const val NEO_A = B
    const val NEO_B = A
    const val NEO_C = Y
    const val NEO_D = X
    const val NEO_COIN = SELECT
    const val NEO_START = START

    @JvmStatic external fun loadCore(corePath: String, systemDir: String, saveDir: String): Boolean
    @JvmStatic external fun loadGame(romPath: String): Boolean
    @JvmStatic external fun getAspectRatio(): Float
    @JvmStatic external fun start()
    @JvmStatic external fun setPaused(paused: Boolean)
    @JvmStatic external fun stop()
    @JvmStatic external fun setSurface(surface: Surface?)
    @JvmStatic external fun setButtons(port: Int, mask: Int)
    @JvmStatic external fun saveState()
    @JvmStatic external fun loadState()
    @JvmStatic external fun reset()
}
