/* ------------------------------------------------------------ */
/*
HTTrack Website Copier, Offline Browser for Windows and Unix
Copyright (C) Xavier Roche and other contributors

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.

Important notes:

- We hereby ask people using this source NOT to use it in purpose of grabbing
emails addresses, or collecting any other private information on persons.
This would disgrace our work, and spoil the many hours we spent on it.

Please visit our Website: http://www.httrack.com
*/

/* ------------------------------------------------------------ */
/* File: httrack.c subroutines:                                 */
/*       wrapper system (for shell                              */
/* Author: Xavier Roche                                         */
/* ------------------------------------------------------------ */

#ifndef HTSWRAP_DEFH
#define HTSWRAP_DEFH

/* Library internal definictions */
#ifdef HTS_INTERNAL_BYTECODE

#include "htsglobal.h"
#include "htsinthash.h"

/* Forward definitions */
#ifndef HTS_DEF_FWSTRUCT_httrackp
#define HTS_DEF_FWSTRUCT_httrackp
typedef struct httrackp httrackp;
#endif

HTSEXT_API int htswrap_init(void);      // LEGACY
HTSEXT_API int htswrap_free(void);      // LEGACY

HTSEXT_API int htswrap_add(httrackp * opt, const char *name, void *fct);
HTSEXT_API uintptr_t htswrap_read(httrackp * opt, const char *name);

#endif

#endif
