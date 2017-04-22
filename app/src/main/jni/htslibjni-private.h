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

/* NewStringUTF, but ignore invalid UTF-8 or NULL input. */
static jobject newStringSafe(JNIEnv *env, const char *s) {
  if (s != NULL) {
    const int ne = ! (*env)->ExceptionOccurred(env);
    jobject str = (*env)->NewStringUTF(env, s);
    /* Silently ignore UTF-8 exception. */
    if (str == NULL && (*env)->ExceptionOccurred(env) && ne) {
      (*env)->ExceptionClear(env);
    }
    return str;
  }
  return NULL;
}

#endif //HTTRACK_HTSLIBJNI_PRIVATE_H
