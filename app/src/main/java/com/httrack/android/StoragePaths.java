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
    return isWritable(path, external, internal, null);
  }

  /**
   * As {@link #isWritable(File, File, File)}, plus {@code shared}: a public storage root the app
   * may write only when it holds all-files access. Kept a distinct root because a public path is
   * writable exactly when that access is held, which only the Context layer knows.
   *
   * @param shared
   *          the public root (e.g. getExternalStorageDirectory()) when access is held, else null.
   *          Does not affect the null-vs-false verdict, which stays keyed on external.
   */
  static Boolean isWritable(final File path, final File external, final File internal,
      final File shared) {
    try {
      // The separator on both sides is what keeps a sibling like "files2" from matching "files".
      final String target = path.getCanonicalPath() + File.separator;
      for (final File root : new File[] { external, internal, shared }) {
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
   * Documents-provider id ("primary:&lt;relative&gt;") to open {@code dir} in the system Files app via
   * ACTION_VIEW, or null when it lies outside the primary shared volume or inside the Android/ subtree
   * (both hidden from file managers on Android 11+), so the caller falls back.
   *
   * @param dir
   *          the folder to open, need not exist
   * @param shared
   *          the primary shared root (getExternalStorageDirectory()), or null while unmounted
   */
  static String externalStorageDocId(final File dir, final File shared) {
    if (dir == null || shared == null) {
      return null;
    }
    try {
      final String root = shared.getCanonicalPath();
      final String path = dir.getCanonicalPath();
      if (!path.startsWith(root + File.separator)) {
        return null;
      }
      final String rel = path.substring(root.length() + 1);
      if (rel.equals("Android") || rel.startsWith("Android" + File.separator)) {
        return null;
      }
      return "primary:" + rel;
    } catch (final IOException e) {
      return null;
    }
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
