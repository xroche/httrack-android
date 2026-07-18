package com.httrack.android;

import java.io.File;
import java.io.IOException;

/**
 * Where mirrors may be written. Pure path math, kept apart from the Context lookups so that it
 * can be exercised off-device: this is the boundary that decides whether a persisted setting is
 * honoured or destroyed.
 */
final class StoragePaths {
  private StoragePaths() {
  }

  /**
   * Whether {@code path} lies inside one of our own storage roots, either of which may be null.
   *
   * @param path
   *          the candidate, need not exist
   * @param external
   *          getExternalFilesDir(null), null while the volume is unmounted
   * @param internal
   *          getFilesDir(), the fallback root
   * @return true inside a root, false provably outside, and null when the question cannot be
   *         answered: with the external root unresolved there is nothing to compare against, and
   *         a caller that reads that as a refusal would discard a setting it cannot vet.
   */
  static Boolean isWritable(final File path, final File external, final File internal) {
    try {
      // The separator on both sides is what keeps a sibling like "files2" from matching "files".
      final String target = path.getCanonicalPath() + File.separator;
      for (final File root : new File[] { external, internal }) {
        if (root != null
            && target.startsWith(root.getCanonicalPath() + File.separator)) {
          return true;
        }
      }
    } catch (final IOException e) {
      return null;
    }
    return external != null ? Boolean.FALSE : null;
  }

  /**
   * Whether {@code name} is safe to append to a root as one directory. File does not fold "..", so
   * a crafted winprofile.ini name like "../../sdcard/evil" would otherwise steer the engine's -O
   * outside the Websites tree. Rejects empty, ".", ".." and anything carrying a path separator.
   *
   * @param name
   *          the project name, already whitespace-collapsed
   * @return true if it denotes a single, in-root directory segment
   */
  static boolean isValidProjectName(final String name) {
    if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      final char c = name.charAt(i);
      if (c == '/' || c == '\\' || c == '\0') {
        return false;
      }
    }
    return true;
  }
}
