package com.emuneo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/** Lista de juegos. Las ROMs se copian a la carpeta privada de la app porque el core necesita rutas de archivo. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: ListView
    private var games: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { setPadding(0, 0, 0, pad / 2) }
        val addButton = Button(this).apply {
            text = "Añadir ROMs (.zip)"
            setOnClickListener { pickRoms() }
        }
        list = ListView(this).apply {
            setOnItemClickListener { _, _, pos, _ -> launch(games[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(games[pos]); true }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@MainActivity).apply {
                text = "Emulador Neo"
                textSize = 24f
                gravity = Gravity.START
            })
            addView(status)
            addView(addButton)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        games = Storage.games(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, games.map { it.nameWithoutExtension })
        status.text = buildString {
            append(if (Storage.hasBios(this@MainActivity)) "BIOS neogeo.zip: encontrada" else "Falta neogeo.zip: añádela junto a tus ROMs")
            append("\n${games.size} juego(s). Mantén pulsado un juego para borrarlo.")
        }
        status.setTextColor(if (Storage.hasBios(this)) Color.rgb(80, 180, 80) else Color.rgb(220, 120, 40))
    }

    private fun pickRoms() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_ROMS)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROMS || resultCode != RESULT_OK || data == null) return

        val uris = buildList {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
            data.data?.let { if (isEmpty()) add(it) }
        }
        status.text = "Copiando ${uris.size} archivo(s)…"
        thread {
            val copied = uris.count { copyRom(it) }
            runOnUiThread {
                Toast.makeText(this, "$copied archivo(s) añadidos", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun copyRom(uri: Uri): Boolean = runCatching {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return false
        if (!name.endsWith(".zip", true)) return false
        // FBNeo identifica el juego por el nombre del zip (mslug.zip, kof98.zip…), así que lo conservamos.
        val target = File(Storage.roms(this), name.lowercase())
        contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        true
    }.getOrDefault(false)

    private fun confirmDelete(game: File) {
        AlertDialog.Builder(this)
            .setMessage("¿Borrar ${game.nameWithoutExtension}?")
            .setPositiveButton("Borrar") { _, _ -> game.delete(); refresh() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun launch(game: File) {
        if (!Storage.hasBios(this)) {
            Toast.makeText(this, "Sin neogeo.zip los juegos de Neo Geo no arrancarán", Toast.LENGTH_LONG).show()
        }
        startActivity(Intent(this, EmulatorActivity::class.java).putExtra(EmulatorActivity.EXTRA_ROM, game.absolutePath))
    }

    private companion object {
        const val REQUEST_ROMS = 1
    }
}
