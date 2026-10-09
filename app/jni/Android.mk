# ndk-build: liblcstore.so = the camera's settings store (store/, the driver of
# OpenMemories-Platform, MIT, (c) 2017 ma1co): reading and writing the camera's own settings,
# which outlive the app (Camera.Parameters do not). Camera only: it links Sony's
# libosal_uipc.so, which the simulator has not got. The stub built here is for the
# linker; the APK carries liblcstore.so alone.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := osal_uipc
LOCAL_SRC_FILES := store/osal_uipc.S
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := lcstore
LOCAL_SRC_FILES := store/store_jni.c store/backup.c
LOCAL_CFLAGS := -O2 -std=gnu99 -fvisibility=hidden -Wall -Wno-unused-parameter
LOCAL_SHARED_LIBRARIES := osal_uipc
include $(BUILD_SHARED_LIBRARY)
