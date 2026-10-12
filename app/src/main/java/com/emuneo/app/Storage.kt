package com.emuneo.app

import android.content.Context
import java.io.File

/** Carpetas privadas de la app. FBNeo busca neogeo.zip junto a la ROM, así que todo va en roms/. */
object Storage {
    fun roms(ctx: Context) = File(ctx.filesDir, "roms").apply { mkdirs() }
    fun system(ctx: Context) = File(ctx.filesDir, "system").apply { mkdirs() }
    fun saves(ctx: Context) = File(ctx.filesDir, "saves").apply { mkdirs() }

    const val BIOS = "neogeo.zip"

    fun games(ctx: Context): List<File> =
        roms(ctx).listFiles { f -> f.isFile && f.name.endsWith(".zip", true) && !f.name.equals(BIOS, true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun hasBios(ctx: Context) = File(roms(ctx), BIOS).exists()
}
