/*
 * com.lenscatalog.NativeStore: the camera's settings store, one slot at a
 * time. Only on the camera (armeabi); the simulator has no such store, and the
 * app keeps its own stand-in there. Nothing here throws: a failed call returns
 * null or a negative number, and the Java side says so in the log.
 */
#include <jni.h>
#include <stdlib.h>
#include "store.h"

#define FN(name) Java_com_lenscatalog_NativeStore_##name
#define EXPORT __attribute__((visibility("default")))

/* The slot's bytes, or null when the camera will not give them. */
EXPORT JNIEXPORT jbyteArray JNICALL FN(read)(JNIEnv *env, jclass cls, jint id) {
    int size = Backup_get_datasize(id);
    jbyteArray arr;
    char *buf;
    if (size <= 0 || size > 4096) return NULL;
    buf = (char *)malloc((size_t)size);
    if (!buf) return NULL;
    if (Backup_read(id, buf) != size) {
        free(buf);
        return NULL;
    }
    arr = (*env)->NewByteArray(env, size);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, size, (const jbyte *)buf);
    free(buf);
    return arr;
}

/* Bytes written; -1 the slot is another size, -2 read-only, -3 any other refusal. */
EXPORT JNIEXPORT jint JNICALL FN(write)(JNIEnv *env, jclass cls, jint id, jbyteArray data) {
    jsize n = (*env)->GetArrayLength(env, data);
    int size = Backup_get_datasize(id), res;
    jbyte *buf;
    if (size <= 0 || n != size) return -1;
    buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return -3;
    res = Backup_write(id >> 16, id, buf);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    if (res == -BACKUP_ERROR_READ_ONLY) return -2;
    return res == size ? res : -3;
}

/* The slot's attribute word (bit 0: read-only), or a negative number. */
EXPORT JNIEXPORT jint JNICALL FN(attr)(JNIEnv *env, jclass cls, jint id) { return Backup_get_attribute(id); }

/* Commit what was written, so it outlives a power cycle. */
EXPORT JNIEXPORT void JNICALL FN(sync)(JNIEnv *env, jclass cls) { Backup_sync_all(); }
