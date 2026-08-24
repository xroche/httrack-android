/*
HTTrack Android Java Interface.

Copyright (C) 2026 Xavier Roche and other contributors

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/

package com.httrack.android;

/**
 * What a finished crawl actually was. Kept free of android.* so the choice can be tested; the
 * wording that goes with each case belongs to the caller.
 */
enum MirrorOutcome {
  /** main() itself failed, so nothing below applies. */
  ERROR,
  /** The user asked for the stop. */
  INTERRUPTED,
  /** A write failed: exit_xh -1, in practice a full disk. */
  ABORTED_IO,
  /** Nothing arrived, so the engine restored the previous session: exit_xh 2. */
  ABORTED_ROLLBACK,
  /** The engine gave up for a reason it does not name. */
  ABORTED,
  SUCCESS,
  SUCCESS_WITH_ERRORS,
  /** Errors, and no file written. */
  FAILED;

  /**
   * Weigh the engine's ABORTCODE against the return code. INTERRUPTED must come first: a user stop
   * sets exit_xh two ways of its own, so the engine's verdict alone reads it as an abort nobody
   * asked for.
   */
  static MirrorOutcome of(final int code, final boolean interrupted, final int abortCode,
      final long errorsCount, final long filesWritten) {
    if (code != 0) {
      return ERROR;
    }
    if (interrupted) {
      return INTERRUPTED;
    }
    switch (abortCode) {
      case 0:
        break;
      case -1:
        return ABORTED_IO;
      case 2:
        return ABORTED_ROLLBACK;
      default:
        return ABORTED;
    }
    if (errorsCount == 0) {
      return SUCCESS;
    }
    return filesWritten != 0 ? SUCCESS_WITH_ERRORS : FAILED;
  }
}
