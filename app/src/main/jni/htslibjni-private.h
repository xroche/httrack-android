/*
HTTrack Android JAVA Native Interface Stubs.

HTTrack Website Copier, Offline Browser for Windows and Unix
Copyright (C) Xavier Roche and other contributors

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
*/

#ifndef HTTRACK_HTSLIBJNI_PRIVATE_H
#define HTTRACK_HTSLIBJNI_PRIVATE_H

#include <android/log.h>

/* httrack-to-android error level dispatch */
static int get_android_prio(const int type) {
  switch(type & 0xff) {
  case LOG_PANIC:
    return ANDROID_LOG_ERROR;
  case LOG_ERROR:
    return ANDROID_LOG_INFO;
  case LOG_WARNING:
  case LOG_NOTICE:
  case LOG_INFO:
  case LOG_DEBUG:
    return ANDROID_LOG_DEBUG;
  case LOG_TRACE:
  default:
    return ANDROID_LOG_VERBOSE;
  }
}

/** Context for HTTrackLib. **/
typedef struct HTTrackLib_context {
  pthread_mutex_t lock;
  httrackp * opt;
  int stop;
} HTTrackLib_context;

#define MUTEX_LOCK(MUTEX) do {                  \
    if (pthread_mutex_lock(&MUTEX) != 0) {      \
      assert(! "pthread_mutex_lock failed");    \
    }                                           \
  } while(0)

#define MUTEX_UNLOCK(MUTEX) do {                \
    if (pthread_mutex_unlock(&MUTEX) != 0) {    \
      assert(! "pthread_mutex_unlock failed");  \
    }                                           \
  } while(0)

#define UNUSED(VAR) (void) VAR

/**
 * Thread-specific context.
 */
typedef struct thread_context_t {
  char *buffer;
  size_t capa;
} thread_context_t;

static void thread_variables_dtor(void *arg) {
  thread_context_t *const context = (thread_context_t*) arg;
  if (context != NULL) {
    if (context->buffer != NULL) {
      free(context->buffer);
      context->buffer = NULL;
    }
    free(context);
  }
}

static thread_context_t* thread_get_variables(pthread_key_t var) {
  void *arg = pthread_getspecific(var);
  if (arg == NULL) {
    arg = calloc(sizeof(thread_context_t), 1);
    if (pthread_setspecific(var, arg) != 0) {
      assert(! "pthread_setspecific() failed");
    }
  }
  return (thread_context_t*) arg;
}

static jclass findClass(JNIEnv *env, const char *name) {
  jclass localClass = (*env)->FindClass(env, name);
  /* "Note however that the jclass is a class reference and must be protected
   * with a call to NewGlobalRef " -- DARN! */
  if (localClass != NULL) {
    jclass globalClass = (*env)->NewGlobalRef(env, localClass);
    (*env)->DeleteLocalRef(env, localClass);
    return globalClass;
  }
  return NULL;
}

static void releaseClass(JNIEnv *env, jclass *cls) {
  if (cls != NULL) {
    (*env)->DeleteGlobalRef(env, *cls);
    *cls = NULL;
  }
}

typedef struct jni_context_t {
  JNIEnv *env;
  /* HTTrackCallbacks object */
  jobject callbacks;
  /* Context */
  HTTrackLib_context *context;
} jni_context_t;

typedef enum hts_state_id_t {
  STATE_NONE = 0,
  STATE_RECEIVE,
  STATE_CONNECTING,
  STATE_DNS,
  STATE_FTP,
  STATE_READY,
  STATE_MAX
} hts_state_id_t;

typedef struct hts_state_t {
  size_t index;
  hts_state_id_t state;
  int code;
  const char *message;
} hts_state_t;

/* Sanitize server-controlled bytes to well-formed modified UTF-8 (invalid sequences, including
   4-byte forms, become '?'), so hostile input cannot reach NewStringUTF, which aborts under
   CheckJNI. Caller frees the result. */
static char *sanitizeModifiedUtf8(const char *s) {
  const size_t len = strlen(s);
  char *const out = malloc(len + 1);
  if (out != NULL) {
    const unsigned char *const in = (const unsigned char *) s;
    size_t i = 0, j = 0;
    while (i < len) {
      const unsigned char c = in[i];
      unsigned int seq = 0;
      if (c <= 0x7F) {
        seq = 1;
      } else if (c >= 0xC2 && c <= 0xDF) {
        if (i + 1 < len && (in[i + 1] & 0xC0) == 0x80) {
          seq = 2;
        }
      } else if (c >= 0xE0 && c <= 0xEF) {
        /* Reject overlong E0 80..9F; surrogate halves (ED A0..BF) stay, they are valid here. */
        const unsigned char lo = (c == 0xE0) ? 0xA0 : 0x80;
        if (i + 2 < len && in[i + 1] >= lo && (in[i + 1] & 0xC0) == 0x80
            && (in[i + 2] & 0xC0) == 0x80) {
          seq = 3;
        }
      }
      if (seq != 0) {
        for (unsigned int k = 0; k < seq; k++) {
          out[j++] = (char) in[i++];
        }
      } else {
        out[j++] = '?';
        i++;
      }
    }
    out[j] = '\0';
  }
  return out;
}

/* NewStringUTF over sanitized bytes, so hostile input cannot reach it; NULL input yields NULL. */
static jobject newStringSafe(JNIEnv *env, const char *s) {
  if (s != NULL) {
    char *const safe = sanitizeModifiedUtf8(s);
    jobject str = (*env)->NewStringUTF(env, safe != NULL ? safe : s);
    free(safe);
    return str;
  }
  return NULL;
}

#endif //HTTRACK_HTSLIBJNI_PRIVATE_H
