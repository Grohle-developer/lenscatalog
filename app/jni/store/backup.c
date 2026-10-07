/*
 * The camera's settings store, through its backup service: the driver of
 * OpenMemories-Platform (MIT, (c) 2017 ma1co, drivers/backup.c), the way
 * OpenMemories-Tweak and Recipe Lab reach it. A request is a message to the
 * service's queue, answered synchronously, over Sony's libosal_uipc.so.
 */
#include <stdarg.h>
#include <string.h>
#include "store.h"

#define OSAL_MSG_BACKUP 0x3E014D

int osal_free_msg(int type, void *addr);
int osal_snd_sync_msg(int type, void *addr);
int osal_valloc_msg_wait(int type, void **addr, int len, int flag);

struct backup_msg {
    int function;
    int result;
    int arg_count;
    int type;
    int padding[2];
    int args[10];
};

static int backup_sync_msg(int function, int arg_count, ...) {
    struct backup_msg *msg;
    va_list ap;
    int i, result;
    if (osal_valloc_msg_wait(OSAL_MSG_BACKUP, (void **)&msg, sizeof(struct backup_msg), 1)) return -1;
    memset(msg, 0, sizeof(struct backup_msg));
    msg->function = function;
    msg->arg_count = arg_count;
    msg->type = OSAL_MSG_BACKUP;
    va_start(ap, arg_count);
    for (i = 0; i < arg_count; i++) msg->args[i] = va_arg(ap, int);
    va_end(ap);
    if (osal_snd_sync_msg(OSAL_MSG_BACKUP, msg)) return -1;
    result = msg->result;
    if (osal_free_msg(OSAL_MSG_BACKUP, msg)) return -1;
    return result;
}

int Backup_get_datasize(int id) { return backup_sync_msg(0, 1, id); }

int Backup_get_attribute(int id) { return backup_sync_msg(2, 1, id); }

int Backup_read(int id, void *addr) { return backup_sync_msg(3, 2, id, (int)addr); }

int Backup_write(int subsystem_id, int id, void *addr) { return backup_sync_msg(8, 3, subsystem_id, id, (int)addr); }

void Backup_sync_all(void) { backup_sync_msg(15, 0); }
