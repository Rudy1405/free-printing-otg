/* bionic declares iconv() only from API 28; packs target API 26. Charset conversion is only needed for
 * non-UTF-8 translation catalogs, so these stubs just report "unsupported" and callers fall back. */
#include <stddef.h>
#include <iconv.h>
iconv_t iconv_open(const char *to, const char *from);
size_t iconv(iconv_t cd, char **in, size_t *inleft, char **out, size_t *outleft);
int iconv_close(iconv_t cd);
