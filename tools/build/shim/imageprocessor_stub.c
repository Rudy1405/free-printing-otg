/* HP ships libImageProcessor only as a closed x86/x86_64 binary. On Android it is not available, so these
 * stubs report "library missing". Printers that need it (newer HP models, see docs/COVERAGE.md) stay unsupported. */
#include "ImageProcessor.h"

image_processor_t *imageProcessorCreate(void) { return 0; }
void imageProcessorDestroy(image_processor_t *p) { (void)p; }
IMAGE_PROCESSOR_ERROR imageProcessorStartPage(image_processor_t *p, cups_page_header2_t *h) { (void)p; (void)h; return IPE_LIBRARY_MISSING; }
IMAGE_PROCESSOR_ERROR imageProcessorProcessLine(image_processor_t *p, BYTE *b, unsigned n) { (void)p; (void)b; (void)n; return IPE_LIBRARY_MISSING; }
IMAGE_PROCESSOR_ERROR imageProcessorEndPage(image_processor_t *p) { (void)p; return IPE_LIBRARY_MISSING; }
