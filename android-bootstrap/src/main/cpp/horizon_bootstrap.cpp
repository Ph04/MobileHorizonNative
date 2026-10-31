#include <android/log.h>
#include <android/native_activity.h>
#include <jni.h>

#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <string>

#define LOG_TAG "HorizonBootstrap"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using NativeActivityEntry = void (*)(ANativeActivity *, void *, size_t);
void *g_engine_handle = nullptr;
std::string g_engine_path;

void write_status(const char *status_path, const char *message) {
    if (status_path == nullptr || *status_path == '\0' ||
        message == nullptr || *message == '\0') {
        return;
    }
    FILE *file = fopen(status_path, "w");
    if (file == nullptr) {
        return;
    }
    fputs(message, file);
    fputc('\n', file);
    fflush(file);
    fclose(file);
}

bool canonical_runtime_path(const char *root, const char *candidate,
                            std::string *canonical_out) {
    if (root == nullptr || candidate == nullptr ||
        *root == '\0' || *candidate == '\0') {
        return false;
    }

    char canonical_root[PATH_MAX];
    char canonical_candidate[PATH_MAX];
    if (realpath(root, canonical_root) == nullptr ||
        realpath(candidate, canonical_candidate) == nullptr) {
        return false;
    }

    const std::string root_prefix =
        std::string(canonical_root) + "/versions/";
    const std::string full_path(canonical_candidate);
    if (full_path.compare(0, root_prefix.size(), root_prefix) != 0 ||
        full_path.size() < strlen("/libhorizon_engine.so") ||
        full_path.compare(full_path.size() - strlen("/libhorizon_engine.so"),
                          strlen("/libhorizon_engine.so"),
                          "/libhorizon_engine.so") != 0) {
        return false;
    }

    if (canonical_out != nullptr) {
        *canonical_out = full_path;
    }
    return true;
}

bool start_engine(const std::string &path, ANativeActivity *activity,
                  void *saved_state, size_t saved_state_size,
                  const char *status_path, bool fallback) {
    dlerror();
    void *handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char *error = dlerror();
        LOGE("dlopen falhou para %s: %s", path.c_str(),
             error == nullptr ? "erro sem detalhe" : error);
        return false;
    }

    dlerror();
    auto entry = reinterpret_cast<NativeActivityEntry>(
        dlsym(handle, "ANativeActivity_onCreate"));
    const char *symbol_error = dlerror();
    if (entry == nullptr || symbol_error != nullptr) {
        LOGE("A biblioteca %s não exporta ANativeActivity_onCreate: %s",
             path.c_str(),
             symbol_error == nullptr ? "símbolo ausente" : symbol_error);
        dlclose(handle);
        return false;
    }

    // Keep the handle alive for the complete game session: Winit and JNI callbacks
    // execute code from this shared object after ANativeActivity_onCreate returns.
    g_engine_handle = handle;
    g_engine_path = path;
    char state[PATH_MAX + 32];
    snprintf(state, sizeof(state), "%s:%s", fallback ? "FALLBACK" : "STARTING",
             path.c_str());
    write_status(status_path, state);
    if (fallback) {
        setenv("HORIZON_RUNTIME_USING_FALLBACK", "1", 1);
    } else {
        unsetenv("HORIZON_RUNTIME_USING_FALLBACK");
    }
    LOGI("Iniciando Horizon runtime: %s%s", path.c_str(),
         fallback ? " (fallback da runtime anterior)" : "");
    entry(activity, saved_state, saved_state_size);
    return true;
}

template <typename Function>
Function engine_symbol(const char *name) {
    if (g_engine_handle == nullptr) {
        return nullptr;
    }
    dlerror();
    auto function = reinterpret_cast<Function>(dlsym(g_engine_handle, name));
    const char *error = dlerror();
    if (error != nullptr) {
        LOGE("Símbolo JNI ausente em %s: %s", name, error);
        return nullptr;
    }
    return function;
}

}  // namespace

extern "C" __attribute__((visibility("default")))
void ANativeActivity_onCreate(ANativeActivity *activity, void *saved_state,
                              size_t saved_state_size) {
    const char *root = getenv("HORIZON_RUNTIME_HOME");
    const char *selected = getenv("HORIZON_RUNTIME_LIBRARY");
    const char *fallback = getenv("HORIZON_RUNTIME_FALLBACK_LIBRARY");
    const char *status_path = getenv("HORIZON_RUNTIME_STATUS_FILE");

    // NativeGameActivity sets this before NativeActivity loads the engine when an
    // instance selected a compatibility loader but its Java 25/API setup failed.
    // Do not silently fall through to Vanilla: that would start the wrong profile.
    const char *startup_blocked = getenv("HORIZON_NATIVE_START_BLOCKED");
    if (startup_blocked != nullptr && *startup_blocked != '\0') {
        write_status(status_path, startup_blocked);
        LOGE("Inicialização nativa bloqueada pelo launcher: %s", startup_blocked);
        ANativeActivity_finish(activity);
        return;
    }

    // iaxivers/horizon resolves mods-java/ beside options.txt using process cwd.
    // This is specific to the Fabric Java Bridge: changing cwd for Vanilla breaks
    // the regular Horizon launch layout and caused the launcher to return to Home.
    const char *loader_id = getenv("HORIZON_LOADER_ID");
    if (loader_id != nullptr && strcmp(loader_id, "fabric") == 0) {
        const char *instance_home = getenv("HORIZON_HOME");
        char canonical_home[PATH_MAX];
        if (instance_home == nullptr || *instance_home == '\0' ||
            realpath(instance_home, canonical_home) == nullptr) {
            const int error_code = errno;
            char message[PATH_MAX + 160];
            snprintf(message, sizeof(message),
                     "ERROR: diretório da instância Java Bridge inválido (errno %d)", error_code);
            write_status(status_path, message);
            LOGE("%s", message);
            ANativeActivity_finish(activity);
            return;
        }
        if (chdir(canonical_home) != 0) {
            const int error_code = errno;
            char message[PATH_MAX + 160];
            snprintf(message, sizeof(message),
                     "ERROR: não foi possível acessar a pasta da instância Java Bridge (errno %d): %s",
                     error_code, canonical_home);
            write_status(status_path, message);
            LOGE("%s", message);
            ANativeActivity_finish(activity);
            return;
        }
        char actual_cwd[PATH_MAX];
        if (getcwd(actual_cwd, sizeof(actual_cwd)) == nullptr ||
            strcmp(actual_cwd, canonical_home) != 0) {
            const int error_code = errno;
            char message[PATH_MAX + 160];
            snprintf(message, sizeof(message),
                     "ERROR: o diretório ativo não corresponde à instância Java Bridge (errno %d)",
                     error_code);
            write_status(status_path, message);
            LOGE("%s", message);
            ANativeActivity_finish(activity);
            return;
        }
        LOGI("Java Bridge By:iaxivers: working directory da instância: %s", actual_cwd);
    }

    std::string active_path;
    std::string fallback_path;
    if (!canonical_runtime_path(root, selected, &active_path)) {
        write_status(status_path, "ERROR: caminho do runtime ativo inválido");
        LOGE("HORIZON_RUNTIME_LIBRARY ausente ou fora do diretório privado permitido");
        ANativeActivity_finish(activity);
        return;
    }

    // A fallback is optional. On first installation, only the active downloaded runtime exists.
    // On upgrades, Java supplies the previous verified runtime as a rollback candidate.
    if (fallback != nullptr && *fallback != '\0') {
        if (!canonical_runtime_path(root, fallback, &fallback_path)) {
            LOGE("O caminho da runtime anterior é inválido; continuando apenas com a ativa");
            fallback_path.clear();
        }
    }

    if (start_engine(active_path, activity, saved_state, saved_state_size,
                     status_path, false)) {
        return;
    }

    if (!fallback_path.empty() && fallback_path != active_path &&
        start_engine(fallback_path, activity, saved_state, saved_state_size,
                     status_path, true)) {
        return;
    }

    write_status(status_path, "ERROR: não foi possível carregar a runtime ativa nem a anterior");
    LOGE("Não foi possível iniciar Horizon com a runtime ativa ou a anterior");
    ANativeActivity_finish(activity);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mobilehorizon_app_NativeGameActivity_nativeChangeWorkingDirectory(
    JNIEnv *env, jclass, jstring path) {
    if (path == nullptr) {
        return static_cast<jint>(EINVAL);
    }
    const char *native_path = env->GetStringUTFChars(path, nullptr);
    if (native_path == nullptr) {
        return static_cast<jint>(ENOMEM);
    }
    const int result = chdir(native_path);
    const int error_code = result == 0 ? 0 : errno;
    env->ReleaseStringUTFChars(path, native_path);
    return static_cast<jint>(error_code);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectCursorMove(
    JNIEnv *env, jclass clazz, jfloat x, jfloat y) {
    using Fn = void (*)(void *, void *, float, float);
    if (auto fn = engine_symbol<Fn>("Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectCursorMove")) {
        fn(env, clazz, x, y);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectMouseMotion(
    JNIEnv *env, jclass clazz, jfloat dx, jfloat dy) {
    using Fn = void (*)(void *, void *, float, float);
    if (auto fn = engine_symbol<Fn>("Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectMouseMotion")) {
        fn(env, clazz, dx, dy);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectMouseButton(
    JNIEnv *env, jclass clazz, jint button, jboolean pressed, jfloat x, jfloat y) {
    using Fn = void (*)(void *, void *, int, unsigned char, float, float);
    if (auto fn = engine_symbol<Fn>("Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectMouseButton")) {
        fn(env, clazz, button, pressed, x, y);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectMouseScroll(
    JNIEnv *env, jclass clazz, jfloat amount, jfloat x, jfloat y) {
    using Fn = void (*)(void *, void *, float, float, float);
    if (auto fn = engine_symbol<Fn>("Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectMouseScroll")) {
        fn(env, clazz, amount, x, y);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectKeyCode(
    JNIEnv *env, jclass clazz, jint key_code, jboolean pressed) {
    using Fn = void (*)(void *, void *, int, unsigned char);
    if (auto fn = engine_symbol<Fn>("Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectKeyCode")) {
        fn(env, clazz, key_code, pressed);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectKeyboardText(
    JNIEnv *env, jclass clazz, jstring value, jboolean submit) {
    using Fn = void (*)(void *, void *, void *, unsigned char);
    if (auto fn = engine_symbol<Fn>("Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectKeyboardText")) {
        fn(env, clazz, value, submit);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mobilehorizon_app_NativeGameActivity_nativeIsGameplay(
    JNIEnv *env, jclass clazz) {
    using Fn = unsigned char (*)(void *, void *);
    if (auto fn = engine_symbol<Fn>("Java_dev_mobilehorizon_app_NativeGameActivity_nativeIsGameplay")) {
        return static_cast<jboolean>(fn(env, clazz));
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mobilehorizon_app_NativeGameActivity_nativeHasLoadedWorld(
    JNIEnv *env, jclass clazz) {
    using Fn = unsigned char (*)(void *, void *);
    if (auto fn = engine_symbol<Fn>("Java_dev_mobilehorizon_app_NativeGameActivity_nativeHasLoadedWorld")) {
        return static_cast<jboolean>(fn(env, clazz));
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_mobilehorizon_app_NativeGameActivity_nativeGetGuiMetrics(
    JNIEnv *env, jclass clazz) {
    using Fn = jintArray (*)(void *, void *);
    if (auto fn = engine_symbol<Fn>("Java_dev_mobilehorizon_app_NativeGameActivity_nativeGetGuiMetrics")) {
        return fn(env, clazz);
    }
    return nullptr;
}
