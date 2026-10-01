#include <grp.h>
#include <stddef.h>
#include <wchar.h>

int mblen(const char *s, size_t n) {
    if (s == 0) return 0;               
    if (n == 0) return -1;
    size_t r = mbrlen(s, n, 0);
    if (r == (size_t)-1 || r == (size_t)-2) return -1;
    return (int)r;
}
void setgrent(void) {}
struct group *getgrent(void) { return 0; }
void endgrent(void) {}
