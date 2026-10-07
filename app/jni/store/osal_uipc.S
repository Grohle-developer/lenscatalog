@ The symbols of Sony's libosal_uipc.so that the store driver calls, for the
@ linker only: the camera has the real library, and this stub is never packaged.
@ From OpenMemories-Platform (MIT, (c) 2017 ma1co), stubs/osal_uipc.S.
.eabi_attribute Tag_ABI_align_preserved, 1

.macro T name
  .global \name
  .type \name, "function"
  \name:
.endm

T osal_free_msg
T osal_snd_sync_msg
T osal_valloc_msg_wait
