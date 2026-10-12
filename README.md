# Emulador Neo (Android)

App Android mínima para jugar Metal Slug, King of Fighters y el resto del catálogo de Neo Geo.
No reinventa la emulación: usa el core **FinalBurn Neo** (el mismo que usan RetroArch y Lemuroid,
el más compatible y preciso para Neo Geo) y añade alrededor un frontend propio, ligero y rápido.

## Qué hace

- Lista de juegos: añades tus `.zip` con el selector de archivos de Android y se copian a la app.
- Vídeo: cada frame se copia directo a la ventana nativa (`ANativeWindow`); el escalado a pantalla
  completa lo hace el hardware del móvil, sin gastar CPU. Mantiene la proporción 4:3.
- Audio: AAudio en modo baja latencia (~50 ms). El ritmo del juego lo marca el audio, así no hay cortes.
- Controles táctiles: cruceta de 8 direcciones y botones A B C D en el arco de Neo Geo, más COIN y START.
  Vibración al pulsar.
- Mandos Bluetooth/USB: se detectan solos. El primer mando es el jugador 1 y el segundo el jugador 2.
  Al usar un mando el control táctil se oculta; toca la pantalla para que vuelva.
- Guardar / Cargar partida en cualquier momento, Reiniciar y Salir (botones arriba).

## Compilar

1. Instala [Android Studio](https://developer.android.com/studio) (incluye el SDK).
   En *Settings > Languages & Frameworks > Android SDK > SDK Tools* marca **NDK (Side by side)** y **CMake**.
2. *File > Open* y elige la carpeta `emulador-android`.
3. Conecta el móvil con la depuración USB activada y pulsa **Run ▶**.
   Para un APK que puedas copiar al móvil: *Build > Build APK(s)* (sale en `app/build/outputs/apk/`).
   Para máximo rendimiento usa la variante `release` (*Build > Select Build Variant*), ya viene firmada.

La primera compilación necesita internet: descarga automáticamente el core FinalBurn Neo oficial de
libretro (`buildbot.libretro.com`) y lo mete en el APK. Si no hay conexión, descarga a mano
`fbneo_libretro_android.so.zip` para `arm64-v8a` desde
`https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/`, descomprímelo y guárdalo como
`app/src/main/jniLibs/arm64-v8a/libfbneo_libretro_android.so`.

Requisitos: Android 8.0 o superior, móvil de 64 bits (casi todos). Para móviles de 32 bits añade
`"armeabi-v7a"` a la lista `abis` de `app/build.gradle.kts`.

## Usar

1. Pulsa **Añadir ROMs (.zip)** y elige tus juegos **y** la BIOS `neogeo.zip` (puedes elegir varios a la vez).
   La app no incluye ni descarga ROMs ni BIOS: tienen que ser tuyas.
2. Los zip deben ser romsets compatibles con FinalBurn Neo y conservar su nombre (`mslug.zip`, `kof98.zip`…).
3. Toca un juego para jugar. Botón atrás para salir. Mantén pulsado un juego en la lista para borrarlo.

Metal Slug 6, KOF XI y KOF NeoWave son de Atomiswave, no de Neo Geo: este core no los ejecuta.

## Controles en mando físico

| Mando (disposición Xbox) | Neo Geo |
|---|---|
| A (abajo) | A |
| B (derecha) | B |
| X (izquierda) | C |
| Y (arriba) | D |
| RB / LB | A+B / C+D |
| RT / LT | A+B+C / B+C+D |
| Start / Select | Start / Coin |

Teclado: WASD o flechas, J K U I = A B C D, Enter = Start, 5 = Coin.

## Estructura

```
app/src/main/cpp/frontend.cpp     Frontend libretro: carga el core, vídeo, audio, entrada, partidas guardadas
app/src/main/cpp/libretro.h       Cabecera oficial de la API libretro (licencia MIT)
app/src/main/java/com/emuneo/app/
  MainActivity.kt                 Lista de juegos y copia de ROMs
  EmulatorActivity.kt             Pantalla de juego, mandos físicos, menú
  GamepadView.kt                  Mando táctil
  NativeBridge.kt                 Funciones JNI y asignación de botones
  Storage.kt                      Carpetas de ROMs, sistema y partidas
```

El juego corre en un proceso aparte (`:emu`) que se cierra al salir, así cada juego empieza con el core limpio.

## Ideas para seguir

- Menú de opciones del core (BIOS UniBIOS, región, etc.): implementar `RETRO_ENVIRONMENT_GET_VARIABLE` en `frontend.cpp`.
- Filtro de escalado / scanlines con OpenGL ES en vez de `ANativeWindow`.
- Run-ahead para reducir aún más el retraso de los controles.
- Editor de posición y tamaño de los botones táctiles.

## Licencias

FinalBurn Neo tiene licencia no comercial: puedes usarlo y compartir la app gratis, pero no venderla.
`libretro.h` es MIT. El código de esta app es tuyo.
