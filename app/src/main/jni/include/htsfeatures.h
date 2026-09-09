/* Satisfies htsglobal.h's unconditional include. Both native modules build with
   -DHTS_INTERNAL_BUILD, so config.h next to this file has already published every
   switch; repeating them here would let the two disagree about HTS_INET6 or
   HTS_USEOPENSSL, which changes SOCaddr and htsblk layout across libhttrack.so. */

#ifndef HTTRACK_FEATURES_DEFH
#define HTTRACK_FEATURES_DEFH
#endif
