/*
 * A stand-in for a memory card that will not rename over an existing file (the
 * camera's card already refuses what Linux's FAT drivers accept): LD_PRELOADed
 * into the writer's JVM by exif_check.py, it makes rename(2) fail as SHIM_RENAME
 * says, so the writer's fallbacks are exercised and the photograph must survive.
 *   over   rename onto an existing file fails (EEXIST)
 *   tmp    any rename of a .TMP fails (EPERM): the new file can never take the name
 *   old    any rename of a .OLD fails (EPERM): the original cannot come back either
 * The words combine ("over,tmp"). Host test only; never built into the APK.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static int ends(const char *s, const char *e) {
    size_t n = strlen(s), m = strlen(e);
    return n >= m && strcmp(s + n - m, e) == 0;
}

int rename(const char *from, const char *to) {
    static int (*real)(const char *, const char *) = 0;
    const char *mode = getenv("SHIM_RENAME");
    struct stat st;
    if (!real) real = dlsym(RTLD_NEXT, "rename");
    if (mode) {
        if (strstr(mode, "over") && stat(to, &st) == 0) { errno = EEXIST; return -1; }
        if (strstr(mode, "tmp") && ends(from, ".TMP")) { errno = EPERM; return -1; }
        if (strstr(mode, "old") && ends(from, ".OLD")) { errno = EPERM; return -1; }
    }
    return real(from, to);
}
