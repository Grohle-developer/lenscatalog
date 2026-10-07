#ifndef LENSCATALOG_STORE_H
#define LENSCATALOG_STORE_H

#define BACKUP_ERROR_READ_ONLY 3

int Backup_get_datasize(int id);
int Backup_get_attribute(int id);
int Backup_read(int id, void *addr);
int Backup_write(int subsystem_id, int id, void *addr);
void Backup_sync_all(void);

#endif
