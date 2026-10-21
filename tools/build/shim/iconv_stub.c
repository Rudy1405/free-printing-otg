#include <errno.h>
#include "iconv_stub.h"
iconv_t iconv_open(const char *to, const char *from) { (void)to; (void)from; errno = EINVAL; return (iconv_t)-1; }
size_t iconv(iconv_t cd, char **in, size_t *inleft, char **out, size_t *outleft) { (void)cd; (void)in; (void)inleft; (void)out; (void)outleft; errno = EINVAL; return (size_t)-1; }
int iconv_close(iconv_t cd) { (void)cd; return 0; }
