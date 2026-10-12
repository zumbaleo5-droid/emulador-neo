// Frontend libretro mínimo para Android.
//
// Carga un core libretro (FinalBurn Neo) con dlopen, lo ejecuta en su propio
// hilo, pinta cada frame en un ANativeWindow (el escalado lo hace el compositor
// de Android, sin coste de CPU), reproduce el audio con AAudio y lee los botones
// que le pasa la parte Kotlin.

#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <aaudio/AAudio.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "libretro.h"

#define TAG "NeoEmu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// Core
// ---------------------------------------------------------------------------

struct Core {
    void* handle = nullptr;
    void (*init)();
    void (*deinit)();
    unsigned (*api_version)();
    void (*get_system_info)(retro_system_info*);
    void (*get_system_av_info)(retro_system_av_info*);
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*reset)();
    void (*run)();
    size_t (*serialize_size)();
    bool (*serialize)(void*, size_t);
    bool (*unserialize)(const void*, size_t);
    bool (*load_game)(const retro_game_info*);
    void (*unload_game)();
};

Core g_core;
bool g_coreInitialized = false;
bool g_gameLoaded = false;

std::string g_systemDir;
std::string g_saveDir;
std::string g_statePath;

retro_pixel_format g_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
retro_system_av_info g_avInfo{};

// ---------------------------------------------------------------------------
// Vídeo
// ---------------------------------------------------------------------------

std::mutex g_windowMutex;
ANativeWindow* g_window = nullptr;
unsigned g_bufW = 0, g_bufH = 0;
int32_t g_bufFormat = 0;

inline uint16_t rgb1555To565(uint16_t p) {
    return static_cast<uint16_t>(((p & 0x7FE0) << 1) | ((p & 0x0200) >> 4) | (p & 0x001F));
}

void videoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;  // frame duplicado: se queda el anterior en pantalla

    std::lock_guard<std::mutex> lock(g_windowMutex);
    if (!g_window) return;

    const bool is32 = g_pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888;
    const int32_t format = is32 ? WINDOW_FORMAT_RGBX_8888 : WINDOW_FORMAT_RGB_565;
    if (width != g_bufW || height != g_bufH || format != g_bufFormat) {
        ANativeWindow_setBuffersGeometry(g_window, width, height, format);
        g_bufW = width;
        g_bufH = height;
        g_bufFormat = format;
    }

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(g_window, &buf, nullptr) != 0) return;

    const unsigned rows = std::min<unsigned>(height, buf.height);
    const unsigned cols = std::min<unsigned>(width, buf.width);
    const auto* src = static_cast<const uint8_t*>(data);
    auto* dst = static_cast<uint8_t*>(buf.bits);

    if (is32) {
        // libretro XRGB8888 (bytes B,G,R,X) -> Android RGBX_8888 (bytes R,G,B,X)
        for (unsigned y = 0; y < rows; ++y) {
            const auto* s = reinterpret_cast<const uint32_t*>(src + y * pitch);
            auto* d = reinterpret_cast<uint32_t*>(dst + y * buf.stride * 4);
            for (unsigned x = 0; x < cols; ++x) {
                const uint32_t p = s[x];
                d[x] = (p & 0xFF00FF00u) | ((p >> 16) & 0xFFu) | ((p & 0xFFu) << 16);
            }
        }
    } else if (g_pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
        for (unsigned y = 0; y < rows; ++y) {
            memcpy(dst + y * buf.stride * 2, src + y * pitch, cols * 2);
        }
    } else {  // 0RGB1555
        for (unsigned y = 0; y < rows; ++y) {
            const auto* s = reinterpret_cast<const uint16_t*>(src + y * pitch);
            auto* d = reinterpret_cast<uint16_t*>(dst + y * buf.stride * 2);
            for (unsigned x = 0; x < cols; ++x) d[x] = rgb1555To565(s[x]);
        }
    }

    ANativeWindow_unlockAndPost(g_window);
}

// ---------------------------------------------------------------------------
// Audio: buffer circular (un productor, un consumidor) + AAudio
// ---------------------------------------------------------------------------

constexpr size_t kRingFrames = 16384;  // potencia de 2
int16_t g_ring[kRingFrames * 2];
std::atomic<size_t> g_ringRead{0};
std::atomic<size_t> g_ringWrite{0};
size_t g_targetFrames = 2400;  // latencia objetivo, se recalcula al abrir el stream

AAudioStream* g_stream = nullptr;
std::atomic<bool> g_audioOk{false};

size_t ringFill() {
    return g_ringWrite.load(std::memory_order_acquire) - g_ringRead.load(std::memory_order_acquire);
}

aaudio_data_callback_result_t audioCallback(AAudioStream*, void*, void* audioData, int32_t numFrames) {
    auto* out = static_cast<int16_t*>(audioData);
    size_t r = g_ringRead.load(std::memory_order_relaxed);
    const size_t available = g_ringWrite.load(std::memory_order_acquire) - r;
    const size_t n = std::min<size_t>(available, static_cast<size_t>(numFrames));
    for (size_t i = 0; i < n; ++i) {
        const size_t idx = ((r + i) & (kRingFrames - 1)) * 2;
        out[i * 2] = g_ring[idx];
        out[i * 2 + 1] = g_ring[idx + 1];
    }
    if (n < static_cast<size_t>(numFrames)) {
        memset(out + n * 2, 0, (numFrames - n) * 2 * sizeof(int16_t));
    }
    g_ringRead.store(r + n, std::memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void audioErrorCallback(AAudioStream*, void*, aaudio_result_t error) {
    // p. ej. se desconectan los auriculares Bluetooth. Pasamos a ritmo por reloj.
    LOGE("AAudio error %d, el audio se detiene", error);
    g_audioOk = false;
}

void openAudio(int sampleRate) {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return;
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(builder, audioCallback, nullptr);
    AAudioStreamBuilder_setErrorCallback(builder, audioErrorCallback, nullptr);

    if (AAudioStreamBuilder_openStream(builder, &g_stream) == AAUDIO_OK) {
        // ~50 ms de audio en cola: suficiente para que no haya cortes, poco retraso
        g_targetFrames = static_cast<size_t>(sampleRate / 20);
        if (AAudioStream_requestStart(g_stream) == AAUDIO_OK) {
            g_audioOk = true;
            LOGI("Audio: %d Hz, burst %d frames", AAudioStream_getSampleRate(g_stream),
                 AAudioStream_getFramesPerBurst(g_stream));
        }
    } else {
        g_stream = nullptr;
        LOGE("No se pudo abrir AAudio");
    }
    AAudioStreamBuilder_delete(builder);
}

void closeAudio() {
    g_audioOk = false;
    if (g_stream) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = nullptr;
    }
}

std::atomic<bool> g_running{false};

size_t audioSampleBatch(const int16_t* data, size_t frames) {
    // Sincronización por audio: si la cola ya tiene suficiente, esperamos a que la
    // tarjeta de sonido consuma. Así el juego va a su velocidad real y sin cortes.
    if (g_audioOk) {
        int waited = 0;
        while (ringFill() > g_targetFrames && g_running && g_audioOk && waited < 100) {
            std::this_thread::sleep_for(std::chrono::microseconds(500));
            ++waited;
        }
    }
    size_t w = g_ringWrite.load(std::memory_order_relaxed);
    const size_t space = kRingFrames - ringFill();
    const size_t n = std::min(frames, space);
    for (size_t i = 0; i < n; ++i) {
        const size_t idx = ((w + i) & (kRingFrames - 1)) * 2;
        g_ring[idx] = data[i * 2];
        g_ring[idx + 1] = data[i * 2 + 1];
    }
    g_ringWrite.store(w + n, std::memory_order_release);
    return frames;
}

void audioSample(int16_t left, int16_t right) {
    const int16_t frame[2] = {left, right};
    audioSampleBatch(frame, 1);
}

// ---------------------------------------------------------------------------
// Entrada: un bitmask de botones RetroPad por jugador, escrito desde Kotlin
// ---------------------------------------------------------------------------

constexpr unsigned kMaxPlayers = 2;
std::atomic<uint32_t> g_buttons[kMaxPlayers];

void inputPoll() {}

int16_t inputState(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port >= kMaxPlayers || (device & RETRO_DEVICE_MASK) != RETRO_DEVICE_JOYPAD) return 0;
    const uint32_t bits = g_buttons[port].load(std::memory_order_relaxed);
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(bits & 0xFFFF);
    return id < 16 ? static_cast<int16_t>((bits >> id) & 1) : 0;
}

// ---------------------------------------------------------------------------
// Entorno (preguntas del core al frontend)
// ---------------------------------------------------------------------------

void coreLog(enum retro_log_level level, const char* fmt, ...) {
    static const int prio[] = {ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR};
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(level <= RETRO_LOG_ERROR ? prio[level] : ANDROID_LOG_INFO, "FBNeo", fmt, args);
    va_end(args);
}

bool environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            const auto fmt = *static_cast<const retro_pixel_format*>(data);
            if (fmt > RETRO_PIXEL_FORMAT_RGB565) return false;
            g_pixelFormat = fmt;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
            *static_cast<const char**>(data) = g_systemDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = g_saveDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = coreLog;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE:
            // Sin menú de opciones: el core usa sus valores por defecto.
            static_cast<retro_variable*>(data)->value = nullptr;
            return false;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            g_avInfo.geometry = *static_cast<const retro_game_geometry*>(data);
            return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            g_avInfo = *static_cast<const retro_system_av_info*>(data);
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *static_cast<int*>(data) = 1 | 2;  // vídeo + audio
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE:
            LOGI("Core: %s", static_cast<const retro_message*>(data)->msg);
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
            return true;
        default:
            return false;
    }
}

// ---------------------------------------------------------------------------
// Bucle de emulación
// ---------------------------------------------------------------------------

std::thread g_thread;
std::atomic<bool> g_paused{false};

enum Action { kNone = 0, kSave, kLoad, kReset };
std::atomic<int> g_pendingAction{kNone};

void saveState() {
    const size_t size = g_core.serialize_size();
    if (size == 0) return;
    std::vector<uint8_t> buf(size);
    if (!g_core.serialize(buf.data(), size)) return;
    if (FILE* f = fopen(g_statePath.c_str(), "wb")) {
        fwrite(buf.data(), 1, size, f);
        fclose(f);
        LOGI("Estado guardado en %s", g_statePath.c_str());
    }
}

void loadState() {
    FILE* f = fopen(g_statePath.c_str(), "rb");
    if (!f) return;
    std::vector<uint8_t> buf;
    uint8_t chunk[65536];
    size_t n;
    while ((n = fread(chunk, 1, sizeof(chunk), f)) > 0) buf.insert(buf.end(), chunk, chunk + n);
    fclose(f);
    if (!g_core.unserialize(buf.data(), buf.size())) LOGE("No se pudo cargar el estado");
}

void emulationLoop() {
    using clock = std::chrono::steady_clock;
    auto next = clock::now();
    while (g_running) {
        if (g_paused) {
            std::this_thread::sleep_for(std::chrono::milliseconds(16));
            next = clock::now();
            continue;
        }

        switch (g_pendingAction.exchange(kNone)) {
            case kSave: saveState(); break;
            case kLoad: loadState(); break;
            case kReset: g_core.reset(); break;
            default: break;
        }

        g_core.run();

        // Sin audio (o si falla), marcamos el ritmo con el reloj.
        if (!g_audioOk) {
            const double fps = g_avInfo.timing.fps > 1.0 ? g_avInfo.timing.fps : 60.0;
            next += std::chrono::duration_cast<clock::duration>(std::chrono::duration<double>(1.0 / fps));
            const auto now = clock::now();
            if (next > now) std::this_thread::sleep_until(next);
            else if (now - next > std::chrono::milliseconds(100)) next = now;  // nos quedamos atrás: no acumular
        } else {
            next = clock::now();
        }
    }
}

template <typename T>
bool loadSym(T& fn, const char* name) {
    fn = reinterpret_cast<T>(dlsym(g_core.handle, name));
    if (!fn) LOGE("Falta el símbolo %s en el core", name);
    return fn != nullptr;
}

std::string jstr(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI (com.emuneo.app.NativeBridge)
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_emuneo_app_NativeBridge_loadCore(JNIEnv* env, jobject, jstring corePath, jstring systemDir, jstring saveDir) {
    const std::string path = jstr(env, corePath);
    g_systemDir = jstr(env, systemDir);
    g_saveDir = jstr(env, saveDir);

    g_core.handle = dlopen(path.c_str(), RTLD_LAZY | RTLD_LOCAL);
    if (!g_core.handle) {
        LOGE("dlopen falló: %s", dlerror());
        return JNI_FALSE;
    }
    bool ok = loadSym(g_core.init, "retro_init") && loadSym(g_core.deinit, "retro_deinit") &&
              loadSym(g_core.api_version, "retro_api_version") &&
              loadSym(g_core.get_system_info, "retro_get_system_info") &&
              loadSym(g_core.get_system_av_info, "retro_get_system_av_info") &&
              loadSym(g_core.set_environment, "retro_set_environment") &&
              loadSym(g_core.set_video_refresh, "retro_set_video_refresh") &&
              loadSym(g_core.set_audio_sample, "retro_set_audio_sample") &&
              loadSym(g_core.set_audio_sample_batch, "retro_set_audio_sample_batch") &&
              loadSym(g_core.set_input_poll, "retro_set_input_poll") &&
              loadSym(g_core.set_input_state, "retro_set_input_state") &&
              loadSym(g_core.reset, "retro_reset") && loadSym(g_core.run, "retro_run") &&
              loadSym(g_core.serialize_size, "retro_serialize_size") &&
              loadSym(g_core.serialize, "retro_serialize") && loadSym(g_core.unserialize, "retro_unserialize") &&
              loadSym(g_core.load_game, "retro_load_game") && loadSym(g_core.unload_game, "retro_unload_game");
    if (!ok) return JNI_FALSE;

    if (g_core.api_version() != RETRO_API_VERSION) {
        LOGE("Versión de API libretro incompatible");
        return JNI_FALSE;
    }

    g_core.set_environment(environment);
    g_core.init();
    g_coreInitialized = true;
    g_core.set_video_refresh(videoRefresh);
    g_core.set_audio_sample(audioSample);
    g_core.set_audio_sample_batch(audioSampleBatch);
    g_core.set_input_poll(inputPoll);
    g_core.set_input_state(inputState);

    retro_system_info info{};
    g_core.get_system_info(&info);
    LOGI("Core cargado: %s %s", info.library_name, info.library_version);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_emuneo_app_NativeBridge_loadGame(JNIEnv* env, jobject, jstring romPath) {
    const std::string path = jstr(env, romPath);
    retro_game_info game{};
    game.path = path.c_str();  // FBNeo usa need_fullpath: lee el zip él mismo

    if (!g_core.load_game(&game)) {
        LOGE("El core no pudo cargar %s", path.c_str());
        return JNI_FALSE;
    }
    g_gameLoaded = true;
    g_core.get_system_av_info(&g_avInfo);

    const size_t slash = path.find_last_of('/');
    std::string name = path.substr(slash == std::string::npos ? 0 : slash + 1);
    const size_t dot = name.find_last_of('.');
    if (dot != std::string::npos) name.resize(dot);
    g_statePath = g_saveDir + "/" + name + ".state";

    LOGI("Juego: %ux%u, %.2f fps, %.0f Hz", g_avInfo.geometry.base_width, g_avInfo.geometry.base_height,
         g_avInfo.timing.fps, g_avInfo.timing.sample_rate);
    return JNI_TRUE;
}

JNIEXPORT jfloat JNICALL
Java_com_emuneo_app_NativeBridge_getAspectRatio(JNIEnv*, jobject) {
    const auto& g = g_avInfo.geometry;
    if (g.aspect_ratio > 0.0f) return g.aspect_ratio;
    return g.base_height ? static_cast<float>(g.base_width) / g.base_height : 4.0f / 3.0f;
}

JNIEXPORT void JNICALL
Java_com_emuneo_app_NativeBridge_start(JNIEnv*, jobject) {
    if (!g_gameLoaded || g_running) return;
    const int rate = g_avInfo.timing.sample_rate > 0 ? static_cast<int>(g_avInfo.timing.sample_rate + 0.5) : 48000;
    openAudio(rate);
    g_running = true;
    g_thread = std::thread(emulationLoop);
}

JNIEXPORT void JNICALL
Java_com_emuneo_app_NativeBridge_setPaused(JNIEnv*, jobject, jboolean paused) {
    g_paused = paused;
    if (g_stream) {
        if (paused) AAudioStream_requestPause(g_stream);
        else if (g_audioOk) AAudioStream_requestStart(g_stream);
    }
}

JNIEXPORT void JNICALL
Java_com_emuneo_app_NativeBridge_stop(JNIEnv*, jobject) {
    g_running = false;
    if (g_thread.joinable()) g_thread.join();
    closeAudio();
    if (g_gameLoaded) g_core.unload_game();
    if (g_coreInitialized) g_core.deinit();
    g_gameLoaded = g_coreInitialized = false;
    // No hacemos dlclose: la actividad del emulador vive en su propio proceso y
    // éste se cierra al salir, lo que deja el core siempre limpio para el siguiente juego.
}

JNIEXPORT void JNICALL
Java_com_emuneo_app_NativeBridge_setSurface(JNIEnv* env, jobject, jobject surface) {
    std::lock_guard<std::mutex> lock(g_windowMutex);
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
    if (surface) g_window = ANativeWindow_fromSurface(env, surface);
    g_bufW = g_bufH = 0;
    g_bufFormat = 0;
}

JNIEXPORT void JNICALL
Java_com_emuneo_app_NativeBridge_setButtons(JNIEnv*, jobject, jint port, jint mask) {
    if (port >= 0 && port < static_cast<jint>(kMaxPlayers)) g_buttons[port] = static_cast<uint32_t>(mask);
}

JNIEXPORT void JNICALL
Java_com_emuneo_app_NativeBridge_saveState(JNIEnv*, jobject) { g_pendingAction = kSave; }

JNIEXPORT void JNICALL
Java_com_emuneo_app_NativeBridge_loadState(JNIEnv*, jobject) { g_pendingAction = kLoad; }

JNIEXPORT void JNICALL
Java_com_emuneo_app_NativeBridge_reset(JNIEnv*, jobject) { g_pendingAction = kReset; }

}  // extern "C"
