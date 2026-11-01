/*
 * MobileHorizon LWJGL Android memory shim.
 *
 * LWJGL's Java memory utilities expect liblwjgl.so even when Horizon supplies
 * rendering through WGPU. This small ARM64-compatible JNI library supplies the
 * core memory entry points used by LWJGL 3.4.x. It deliberately does not provide
 * desktop windowing, OpenGL, Vulkan, audio, or input backends.
 *
 * Built for Android with the NDK and loaded through org.lwjgl.librarypath.
 */

#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void *mh_pointer(jlong address) {
    return (void *)(uintptr_t)address;
}

static jlong mh_address(const void *pointer) {
    return (jlong)(uintptr_t)pointer;
}

static void *mh_aligned_alloc(size_t alignment, size_t size) {
    void *pointer = NULL;
    if (alignment < sizeof(void *) || (alignment & (alignment - 1U)) != 0U) {
        return NULL;
    }
    if (posix_memalign(&pointer, alignment, size == 0U ? alignment : size) != 0) {
        return NULL;
    }
    return pointer;
}

static void mh_aligned_free(void *pointer) {
    free(pointer);
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_system_MemoryUtil_ngetPageSize(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    long result = sysconf(_SC_PAGESIZE);
    return result > 0 ? (jint)result : (jint)4096;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_system_MemoryUtil_ngetCacheLineSize(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
#ifdef _SC_LEVEL1_DCACHE_LINESIZE
    long result = sysconf(_SC_LEVEL1_DCACHE_LINESIZE);
    if (result > 0 && result <= 4096 && (result & (result - 1)) == 0) {
        return (jint)result;
    }
#endif
    return (jint)64;
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_system_MemoryUtil_memGlobalRefToObject(JNIEnv *env, jclass type, jlong global_ref) {
    (void)env;
    (void)type;
    // Convert the persistent global reference to a local reference owned by this JNI call.
    return (*env)->NewLocalRef(env, (jobject)(uintptr_t)global_ref);
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_getPointerSize(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return (jint)sizeof(void *);
}

/* LWJGL asks for native allocator function pointers during MemoryManage setup. */
JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_malloc(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return mh_address((const void *)(uintptr_t)&malloc);
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_calloc(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return mh_address((const void *)(uintptr_t)&calloc);
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_realloc(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return mh_address((const void *)(uintptr_t)&realloc);
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_free(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return mh_address((const void *)(uintptr_t)&free);
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_aligned_1alloc(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return mh_address((const void *)(uintptr_t)&mh_aligned_alloc);
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_aligned_1free(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return mh_address((const void *)(uintptr_t)&mh_aligned_free);
}

JNIEXPORT jbyte JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_ngetByte(JNIEnv *env, jclass type, jlong address) {
    (void)env;
    (void)type;
    jbyte value;
    memcpy(&value, mh_pointer(address), sizeof(value));
    return value;
}

JNIEXPORT jshort JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_ngetShort(JNIEnv *env, jclass type, jlong address) {
    (void)env;
    (void)type;
    jshort value;
    memcpy(&value, mh_pointer(address), sizeof(value));
    return value;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_ngetInt(JNIEnv *env, jclass type, jlong address) {
    (void)env;
    (void)type;
    jint value;
    memcpy(&value, mh_pointer(address), sizeof(value));
    return value;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_ngetLong(JNIEnv *env, jclass type, jlong address) {
    (void)env;
    (void)type;
    jlong value;
    memcpy(&value, mh_pointer(address), sizeof(value));
    return value;
}

JNIEXPORT jfloat JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_ngetFloat(JNIEnv *env, jclass type, jlong address) {
    (void)env;
    (void)type;
    jfloat value;
    memcpy(&value, mh_pointer(address), sizeof(value));
    return value;
}

JNIEXPORT jdouble JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_ngetDouble(JNIEnv *env, jclass type, jlong address) {
    (void)env;
    (void)type;
    jdouble value;
    memcpy(&value, mh_pointer(address), sizeof(value));
    return value;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_ngetAddress(JNIEnv *env, jclass type, jlong address) {
    (void)env;
    (void)type;
    uintptr_t value;
    memcpy(&value, mh_pointer(address), sizeof(value));
    return (jlong)value;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_nputByte(JNIEnv *env, jclass type, jlong address, jbyte value) {
    (void)env;
    (void)type;
    memcpy(mh_pointer(address), &value, sizeof(value));
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_nputShort(JNIEnv *env, jclass type, jlong address, jshort value) {
    (void)env;
    (void)type;
    memcpy(mh_pointer(address), &value, sizeof(value));
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_nputInt(JNIEnv *env, jclass type, jlong address, jint value) {
    (void)env;
    (void)type;
    memcpy(mh_pointer(address), &value, sizeof(value));
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_nputLong(JNIEnv *env, jclass type, jlong address, jlong value) {
    (void)env;
    (void)type;
    memcpy(mh_pointer(address), &value, sizeof(value));
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_nputFloat(JNIEnv *env, jclass type, jlong address, jfloat value) {
    (void)env;
    (void)type;
    memcpy(mh_pointer(address), &value, sizeof(value));
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_nputDouble(JNIEnv *env, jclass type, jlong address, jdouble value) {
    (void)env;
    (void)type;
    memcpy(mh_pointer(address), &value, sizeof(value));
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_MemoryAccessJNI_nputAddress(JNIEnv *env, jclass type, jlong address, jlong value) {
    (void)env;
    (void)type;
    uintptr_t pointer = (uintptr_t)value;
    memcpy(mh_pointer(address), &pointer, sizeof(pointer));
}

/* libc allocator wrappers used by LWJGL's default MemoryAllocator. */
JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_libc_LibCStdlib_nmalloc(JNIEnv *env, jclass type, jlong size) {
    (void)env;
    (void)type;
    return mh_address(malloc(size > 0 ? (size_t)size : 1U));
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_libc_LibCStdlib_ncalloc(JNIEnv *env, jclass type, jlong count, jlong size) {
    (void)env;
    (void)type;
    if (count < 0 || size < 0 || (size != 0 && (uint64_t)count > SIZE_MAX / (uint64_t)size)) {
        return 0;
    }
    return mh_address(calloc(count > 0 ? (size_t)count : 1U, size > 0 ? (size_t)size : 1U));
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_libc_LibCStdlib_nrealloc(JNIEnv *env, jclass type, jlong address, jlong size) {
    (void)env;
    (void)type;
    if (size < 0) return 0;
    return mh_address(realloc(mh_pointer(address), size > 0 ? (size_t)size : 1U));
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_libc_LibCStdlib_nfree(JNIEnv *env, jclass type, jlong address) {
    (void)env;
    (void)type;
    free(mh_pointer(address));
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_libc_LibCStdlib_naligned_1alloc(JNIEnv *env, jclass type, jlong alignment, jlong size) {
    (void)env;
    (void)type;
    if (alignment <= 0 || size < 0) return 0;
    return mh_address(mh_aligned_alloc((size_t)alignment, (size_t)size));
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_libc_LibCStdlib_naligned_1free(JNIEnv *env, jclass type, jlong address) {
    (void)env;
    (void)type;
    mh_aligned_free(mh_pointer(address));
}

/* Safe fallbacks for the direct-memory C helpers used by some LWJGL core paths. */
JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_libc_LibCString_nmemset__JIJ(JNIEnv *env, jclass type,
                                                   jlong destination, jint value, jlong count) {
    (void)env;
    (void)type;
    if (count < 0) return 0;
    return mh_address(memset(mh_pointer(destination), value, (size_t)count));
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_libc_LibCString_nmemcpy__JJJ(JNIEnv *env, jclass type,
                                                   jlong destination, jlong source, jlong count) {
    (void)env;
    (void)type;
    if (count < 0) return 0;
    return mh_address(memcpy(mh_pointer(destination), mh_pointer(source), (size_t)count));
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_libc_LibCString_nmemmove__JJJ(JNIEnv *env, jclass type,
                                                    jlong destination, jlong source, jlong count) {
    (void)env;
    (void)type;
    if (count < 0) return 0;
    return mh_address(memmove(mh_pointer(destination), mh_pointer(source), (size_t)count));
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_system_libc_LibCString_nstrlen(JNIEnv *env, jclass type, jlong address) {
    (void)env;
    (void)type;
    return (jlong)strlen((const char *)mh_pointer(address));
}
