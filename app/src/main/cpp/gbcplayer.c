/*
 * GBC Player -- JNI shim.
 *
 * This file is marshalling only; every line of emulator logic lives in
 * gbcplayer_core.c, which the host test in tools/nativetest.c links directly.
 *
 * The Kotlin side is expected to declare (in an object, a class, or a companion
 * object -- all three work):
 *
 *   class NativeBridge {                       // package com.dklab.nes
 *     external fun nativeInit(rom: ByteArray, sampleRate: Int): Long
 *     external fun nativeDestroy(handle: Long)
 *     external fun nativeSetButtons(handle: Long, mask: Int)
 *     external fun nativeRunFrame(handle: Long, argbOut: IntArray?, pcmOut: ShortArray?): Int
 *     external fun nativeGetFrameBuffer(handle: Long): java.nio.ByteBuffer?
 *     external fun nativeGetSram(handle: Long): ByteArray?
 *     external fun nativeSetSram(handle: Long, sram: ByteArray): Boolean
 *     external fun nativeIsSramDirty(handle: Long): Boolean
 *     external fun nativeSaveState(handle: Long): ByteArray?
 *     external fun nativeLoadState(handle: Long, state: ByteArray): Boolean
 *   }
 *
 * com.dklab.nes.NativeBridge is the canonical name and is exported as
 * plain JNI symbols.  JNI_OnLoad additionally binds the same methods to a
 * handful of other likely class names (see kCandidateClasses) so a different
 * choice on the Kotlin side still links.
 *
 * Threading: one handle belongs to one thread -- the emulator thread.  Nothing
 * here is internally synchronised.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>

#include "gbcplayer.h"
#include "game.h"
#include <stdio.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "gbcplayer", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "gbcplayer", __VA_ARGS__)

static GbcEmu* from_handle(jlong handle) { return (GbcEmu*)(uintptr_t)handle; }

static jlong jni_init(JNIEnv* env, jobject thiz, jbyteArray rom,
                      jint sample_rate) {
  (void)thiz;
  if (!rom) {
    LOGE("nativeInit: rom is null");
    return 0;
  }
  jsize size = (*env)->GetArrayLength(env, rom);
  jbyte* bytes = (*env)->GetByteArrayElements(env, rom, NULL);
  if (!bytes) {
    LOGE("nativeInit: could not pin the %d byte rom array", (int)size);
    return 0;
  }

  char err[192];
  err[0] = '\0';
  GbcEmu* emu = gbc_open((const uint8_t*)bytes, (size_t)size, (int)sample_rate,
                         err, sizeof(err));
  (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);

  if (!emu) {
    LOGE("nativeInit failed: %s", err[0] ? err : "unknown error");
    return 0;
  }
  return (jlong)(uintptr_t)emu;
}

static void jni_destroy(JNIEnv* env, jobject thiz, jlong handle) {
  (void)env;
  (void)thiz;
  gbc_close(from_handle(handle));
}

static void jni_set_buttons(JNIEnv* env, jobject thiz, jlong handle,
                            jint mask) {
  (void)env;
  (void)thiz;
  gbc_set_buttons(from_handle(handle), (uint32_t)mask);
}

/* The hot path.  Both arrays are pinned with GetPrimitiveArrayCritical so the
 * pixels and samples are written straight into the Java heap: one copy, and
 * nothing is allocated per frame.  Either array may be null -- pass null for
 * argbOut when using the nativeGetFrameBuffer() zero-conversion path. */
static jint jni_run_frame(JNIEnv* env, jobject thiz, jlong handle,
                          jintArray argb_out, jshortArray pcm_out) {
  (void)thiz;
  GbcEmu* emu = from_handle(handle);
  if (!emu) return 0;

  if (argb_out &&
      (*env)->GetArrayLength(env, argb_out) < GBC_SCREEN_PIXELS) {
    LOGE("nativeRunFrame: argbOut holds %d ints, needs %d",
         (int)(*env)->GetArrayLength(env, argb_out), GBC_SCREEN_PIXELS);
    argb_out = NULL;
  }
  jint pcm_frames_cap = 0;
  if (pcm_out) pcm_frames_cap = (*env)->GetArrayLength(env, pcm_out) / 2;

  void* argb = argb_out
                   ? (*env)->GetPrimitiveArrayCritical(env, argb_out, NULL)
                   : NULL;
  void* pcm =
      pcm_out ? (*env)->GetPrimitiveArrayCritical(env, pcm_out, NULL) : NULL;

  jint written = (jint)gbc_run_frame(emu, (uint32_t*)argb, (int16_t*)pcm,
                                     (int)pcm_frames_cap);

  /* Released in reverse order of acquisition, as the JNI spec requires. */
  if (pcm) (*env)->ReleasePrimitiveArrayCritical(env, pcm_out, pcm, 0);
  if (argb) (*env)->ReleasePrimitiveArrayCritical(env, argb_out, argb, 0);
  return written;
}

/* Direct view of binjgb's frame buffer, 160*144*4 bytes in R,G,B,A byte order.
 * That is byte-for-byte what an ARGB_8888 Bitmap wants, so
 *     bitmap.copyPixelsFromBuffer(buf.also { it.rewind() })
 * needs no conversion at all.  The buffer stays valid until nativeDestroy. */
static jobject jni_get_frame_buffer(JNIEnv* env, jobject thiz, jlong handle) {
  (void)thiz;
  GbcEmu* emu = from_handle(handle);
  if (!emu) return NULL;
  return (*env)->NewDirectByteBuffer(env, (void*)gbc_framebuffer(emu),
                                     (jlong)GBC_SCREEN_PIXELS * 4);
}

static jbyteArray jni_get_sram(JNIEnv* env, jobject thiz, jlong handle) {
  (void)thiz;
  GbcEmu* emu = from_handle(handle);
  if (!emu) return NULL;
  size_t size = gbc_sram_size(emu);
  if (size == 0) return NULL; /* Cartridge has no battery: nothing to save. */

  jbyteArray out = (*env)->NewByteArray(env, (jsize)size);
  if (!out) return NULL;
  jbyte* dst = (*env)->GetByteArrayElements(env, out, NULL);
  if (!dst) return NULL;
  int ok = gbc_sram_read(emu, (uint8_t*)dst, size);
  (*env)->ReleaseByteArrayElements(env, out, dst, ok ? 0 : JNI_ABORT);
  if (!ok) {
    LOGE("nativeGetSram: emulator refused to hand over %zu bytes", size);
    return NULL;
  }
  return out;
}

static jboolean jni_set_sram(JNIEnv* env, jobject thiz, jlong handle,
                             jbyteArray sram) {
  (void)thiz;
  GbcEmu* emu = from_handle(handle);
  if (!emu || !sram) return JNI_FALSE;
  size_t want = gbc_sram_size(emu);
  jsize size = (*env)->GetArrayLength(env, sram);
  if (want == 0 || (size_t)size != want) {
    LOGE("nativeSetSram: save is %d bytes, cartridge wants %zu", (int)size,
         want);
    return JNI_FALSE;
  }
  jbyte* src = (*env)->GetByteArrayElements(env, sram, NULL);
  if (!src) return JNI_FALSE;
  int ok = gbc_sram_write(emu, (const uint8_t*)src, (size_t)size);
  (*env)->ReleaseByteArrayElements(env, sram, src, JNI_ABORT);
  return ok ? JNI_TRUE : JNI_FALSE;
}

/* True if the game wrote to cartridge RAM since the last call -- the cue to
 * flush the .sav file. */
static jboolean jni_is_sram_dirty(JNIEnv* env, jobject thiz, jlong handle) {
  (void)env;
  (void)thiz;
  return gbc_sram_dirty(from_handle(handle)) ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray jni_save_state(JNIEnv* env, jobject thiz, jlong handle) {
  (void)thiz;
  GbcEmu* emu = from_handle(handle);
  if (!emu) return NULL;
  size_t size = gbc_state_size();
  jbyteArray out = (*env)->NewByteArray(env, (jsize)size);
  if (!out) return NULL;
  jbyte* dst = (*env)->GetByteArrayElements(env, out, NULL);
  if (!dst) return NULL;
  int ok = gbc_state_save(emu, (uint8_t*)dst, size);
  (*env)->ReleaseByteArrayElements(env, out, dst, ok ? 0 : JNI_ABORT);
  if (!ok) {
    LOGE("nativeSaveState failed");
    return NULL;
  }
  return out;
}

static jboolean jni_load_state(JNIEnv* env, jobject thiz, jlong handle,
                               jbyteArray state) {
  (void)thiz;
  GbcEmu* emu = from_handle(handle);
  if (!emu || !state) return JNI_FALSE;
  jsize size = (*env)->GetArrayLength(env, state);
  if ((size_t)size != gbc_state_size()) {
    LOGE("nativeLoadState: state is %d bytes, this build uses %zu", (int)size,
         gbc_state_size());
    return JNI_FALSE;
  }
  jbyte* src = (*env)->GetByteArrayElements(env, state, NULL);
  if (!src) return JNI_FALSE;
  int ok = gbc_state_load(emu, (const uint8_t*)src, (size_t)size);
  (*env)->ReleaseByteArrayElements(env, state, src, JNI_ABORT);
  if (!ok) LOGE("nativeLoadState: rejected (wrong rom or a different build?)");
  return ok ? JNI_TRUE : JNI_FALSE;
}

/* ------------------------------------------------------------------------- */
/* Canonical exports: com.dklab.nes.NativeBridge                         */
/* ------------------------------------------------------------------------- */

#define GBC_EXPORT(ret, name) \
  JNIEXPORT ret JNICALL Java_com_dklab_nes_NativeBridge_##name

GBC_EXPORT(jlong, nativeInit)(JNIEnv* env, jobject thiz, jbyteArray rom,
                              jint sample_rate) {
  return jni_init(env, thiz, rom, sample_rate);
}
GBC_EXPORT(void, nativeDestroy)(JNIEnv* env, jobject thiz, jlong handle) {
  jni_destroy(env, thiz, handle);
}
GBC_EXPORT(void, nativeSetButtons)(JNIEnv* env, jobject thiz, jlong handle,
                                   jint mask) {
  jni_set_buttons(env, thiz, handle, mask);
}
GBC_EXPORT(jint, nativeRunFrame)(JNIEnv* env, jobject thiz, jlong handle,
                                 jintArray argb, jshortArray pcm) {
  return jni_run_frame(env, thiz, handle, argb, pcm);
}
static jlong jni_game_frames(JNIEnv* env, jobject thiz, jlong handle) {
  (void)env; (void)thiz;
  return handle ? (jlong)gbc_game_frames(from_handle(handle)) : 0;
}
static void jni_reset(JNIEnv* env, jobject thiz, jlong h) {
  (void)env; (void)thiz; if (h) gbc_reset(from_handle(h));
}
static void jni_set_buttons2(JNIEnv* env, jobject thiz, jlong h, jint mask) {
  (void)env; (void)thiz; if (h) gbc_set_buttons2(from_handle(h), (uint32_t)mask);
}
static void jni_set_pointer(JNIEnv* env, jobject thiz, jlong h, jint x, jint y, jboolean pressed) {
  (void)env; (void)thiz; if (h) gbc_set_pointer(from_handle(h), x, y, pressed ? 1 : 0);
}
static jboolean jni_uses_zapper(JNIEnv* env, jobject thiz) { (void)env; (void)thiz; return gbc_uses_zapper() ? JNI_TRUE : JNI_FALSE; }
static void jni_set_rewind(JNIEnv* env, jobject thiz, jlong h, jboolean on) {
  (void)env; (void)thiz; if (h) gbc_set_rewind(from_handle(h), on ? 1 : 0);
}
static void jni_set_option(JNIEnv* env, jobject thiz, jstring key, jstring value) {
  (void)thiz;
  const char* k = (*env)->GetStringUTFChars(env, key, NULL);
  const char* v = (*env)->GetStringUTFChars(env, value, NULL);
  if (k && v) gbc_set_option(k, v);
  if (k) (*env)->ReleaseStringUTFChars(env, key, k);
  if (v) (*env)->ReleaseStringUTFChars(env, value, v);
}
static jint jni_wide_frame(JNIEnv* env, jobject thiz, jlong h, jintArray out) {
  (void)thiz;
  if (!h) return 0;
  GbcEmu* e = from_handle(h);
  unsigned w = gbc_wide_frame(e, NULL);
  if (!w || !out || (*env)->GetArrayLength(env, out) < (jsize)(w * GBC_SCREEN_HEIGHT)) return (jint)w;
  jint* dst = (*env)->GetIntArrayElements(env, out, NULL);
  if (dst) { gbc_wide_frame(e, (uint32_t*)dst); (*env)->ReleaseIntArrayElements(env, out, dst, 0); }
  return (jint)w;
}
static jint jni_ram_hash(JNIEnv* env, jobject thiz, jlong h) {
  (void)env; (void)thiz; return h ? (jint)gbc_ram_hash(from_handle(h)) : 0;
}
static jint jni_mod_count(JNIEnv* env, jobject thiz) { (void)env; (void)thiz; return gbc_mod_count(); }
/* "key\ttype\tmin\tmax\tpersist\tlabel\tcaption" */
static jstring jni_mod_info(JNIEnv* env, jobject thiz, jint i) {
  (void)thiz;
  const GameMod* m = gbc_mod(i);
  if (!m) return NULL;
  char buf[512];
  snprintf(buf, sizeof buf, "%s\t%d\t%d\t%d\t%d\t%s\t%s", m->key, m->type, m->min, m->max,
           m->persist, m->label, m->caption ? m->caption : "");
  return (*env)->NewStringUTF(env, buf);
}
static jint jni_mod_get(JNIEnv* env, jobject thiz, jint i) { (void)env; (void)thiz; return gbc_mod_get(i); }
static void jni_mod_set(JNIEnv* env, jobject thiz, jint i, jint v) { (void)env; (void)thiz; gbc_mod_set(i, v); }
GBC_EXPORT(void, nativeReset)(JNIEnv* env, jobject thiz, jlong h) { jni_reset(env, thiz, h); }
GBC_EXPORT(void, nativeSetButtons2)(JNIEnv* env, jobject thiz, jlong h, jint mask) { jni_set_buttons2(env, thiz, h, mask); }
GBC_EXPORT(void, nativeSetRewind)(JNIEnv* env, jobject thiz, jlong h, jboolean on) { jni_set_rewind(env, thiz, h, on); }
static jboolean jni_set_game(JNIEnv* env, jobject thiz, jstring id) {
  (void)thiz;
#ifdef NES_HUB
  const char* c = (*env)->GetStringUTFChars(env, id, NULL);
  int ok = c && hub_select(c);
  if (c) (*env)->ReleaseStringUTFChars(env, id, c);
  return ok ? JNI_TRUE : JNI_FALSE;
#else
  (void)env; (void)id; return JNI_TRUE;   /* single-game build: the one game */
#endif
}
/* Hub: choose the plug-in from the ROM itself; returns its id or "" (generic). */
static jstring jni_detect_game(JNIEnv* env, jobject thiz, jbyteArray rom) {
  (void)thiz;
#ifdef NES_HUB
  jsize n = (*env)->GetArrayLength(env, rom);
  jbyte* b = (*env)->GetByteArrayElements(env, rom, NULL);
  const char* id = b ? hub_detect((const uint8_t*)b, (size_t)n) : NULL;
  if (b) (*env)->ReleaseByteArrayElements(env, rom, b, JNI_ABORT);
  return (*env)->NewStringUTF(env, id ? id : "");
#else
  (void)rom; return (*env)->NewStringUTF(env, "");
#endif
}
GBC_EXPORT(jstring, nativeDetectGame)(JNIEnv* env, jobject thiz, jbyteArray rom) { return jni_detect_game(env, thiz, rom); }
GBC_EXPORT(jboolean, nativeSetGame)(JNIEnv* env, jobject thiz, jstring id) { return jni_set_game(env, thiz, id); }
GBC_EXPORT(jint, nativeModCount)(JNIEnv* env, jobject thiz) { return jni_mod_count(env, thiz); }
GBC_EXPORT(jstring, nativeModInfo)(JNIEnv* env, jobject thiz, jint i) { return jni_mod_info(env, thiz, i); }
GBC_EXPORT(jint, nativeModGet)(JNIEnv* env, jobject thiz, jint i) { return jni_mod_get(env, thiz, i); }
GBC_EXPORT(void, nativeModSet)(JNIEnv* env, jobject thiz, jint i, jint v) { jni_mod_set(env, thiz, i, v); }
GBC_EXPORT(jlong, nativeGameFrames)(JNIEnv* env, jobject thiz, jlong handle) {
  return jni_game_frames(env, thiz, handle);
}
GBC_EXPORT(jobject, nativeGetFrameBuffer)(JNIEnv* env, jobject thiz,
                                          jlong handle) {
  return jni_get_frame_buffer(env, thiz, handle);
}
GBC_EXPORT(jbyteArray, nativeGetSram)(JNIEnv* env, jobject thiz, jlong handle) {
  return jni_get_sram(env, thiz, handle);
}
GBC_EXPORT(jboolean, nativeSetSram)(JNIEnv* env, jobject thiz, jlong handle,
                                    jbyteArray sram) {
  return jni_set_sram(env, thiz, handle, sram);
}
GBC_EXPORT(jboolean, nativeIsSramDirty)(JNIEnv* env, jobject thiz,
                                        jlong handle) {
  return jni_is_sram_dirty(env, thiz, handle);
}
GBC_EXPORT(jbyteArray, nativeSaveState)(JNIEnv* env, jobject thiz,
                                        jlong handle) {
  return jni_save_state(env, thiz, handle);
}
GBC_EXPORT(jboolean, nativeLoadState)(JNIEnv* env, jobject thiz, jlong handle,
                                      jbyteArray state) {
  return jni_load_state(env, thiz, handle, state);
}

/* ------------------------------------------------------------------------- */
/* Late binding for other plausible holder classes                            */
/* ------------------------------------------------------------------------- */

static const JNINativeMethod kMethods[] = {
    {"nativeInit", "([BI)J", (void*)jni_init},
    {"nativeDestroy", "(J)V", (void*)jni_destroy},
    {"nativeSetButtons", "(JI)V", (void*)jni_set_buttons},
    {"nativeRunFrame", "(J[I[S)I", (void*)jni_run_frame},
    {"nativeGetFrameBuffer", "(J)Ljava/nio/ByteBuffer;",
     (void*)jni_get_frame_buffer},
    {"nativeGetSram", "(J)[B", (void*)jni_get_sram},
    {"nativeSetSram", "(J[B)Z", (void*)jni_set_sram},
    {"nativeIsSramDirty", "(J)Z", (void*)jni_is_sram_dirty},
    {"nativeSaveState", "(J)[B", (void*)jni_save_state},
    {"nativeLoadState", "(J[B)Z", (void*)jni_load_state},
    {"nativeGameFrames", "(J)J", (void*)jni_game_frames},
    {"nativeReset", "(J)V", (void*)jni_reset},
    {"nativeSetButtons2", "(JI)V", (void*)jni_set_buttons2},
    {"nativeSetRewind", "(JZ)V", (void*)jni_set_rewind},
    {"nativeModCount", "()I", (void*)jni_mod_count},
    {"nativeModInfo", "(I)Ljava/lang/String;", (void*)jni_mod_info},
    {"nativeModGet", "(I)I", (void*)jni_mod_get},
    {"nativeModSet", "(II)V", (void*)jni_mod_set},
    {"nativeSetGame", "(Ljava/lang/String;)Z", (void*)jni_set_game},
    {"nativeDetectGame", "([B)Ljava/lang/String;", (void*)jni_detect_game},
    {"nativeSetPointer", "(JIIZ)V", (void*)jni_set_pointer},
    {"nativeUsesZapper", "()Z", (void*)jni_uses_zapper},
    {"nativeRamHash", "(J)I", (void*)jni_ram_hash},
    {"nativeSetOption", "(Ljava/lang/String;Ljava/lang/String;)V", (void*)jni_set_option},
    {"nativeWideFrame", "(J[I)I", (void*)jni_wide_frame},
};

/* Registering by hand costs nothing and means the Kotlin side is free to hold
 * the externals in any of these, in a plain class, an object or a companion
 * object.  Anything not found here still resolves through the exported
 * Java_com_dklab_nes_NativeBridge_* symbols above. */
static const char* const kCandidateClasses[] = {
    "com/dklab/nes/NativeBridge",
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  (void)reserved;
  JNIEnv* env = NULL;
  if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: no JNI 1.6 environment");
    return JNI_ERR;
  }

  int bound = 0;
  for (size_t i = 0; i < sizeof(kCandidateClasses) / sizeof(kCandidateClasses[0]);
       ++i) {
    jclass cls = (*env)->FindClass(env, kCandidateClasses[i]);
    if (!cls) {
      /* Not present in this build; FindClass left a NoClassDefFoundError
       * pending that must not escape into the caller's frame. */
      (*env)->ExceptionClear(env);
      continue;
    }
    if ((*env)->RegisterNatives(env, cls, kMethods,
                                (jint)(sizeof(kMethods) /
                                       sizeof(kMethods[0]))) == JNI_OK) {
      LOGI("bound natives to %s", kCandidateClasses[i]);
      ++bound;
    } else {
      /* The class exists but does not declare this exact set; leave it alone. */
      (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, cls);
  }
  if (bound == 0) {
    LOGI("no candidate class matched; relying on exported "
         "Java_com_dklab_nes_NativeBridge_* symbols");
  }
  return JNI_VERSION_1_6;
}
