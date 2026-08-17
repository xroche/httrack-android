package com.httrack.android;

import java.io.File;
import java.io.IOException;

/**
 * Where mirrors may be written: decides whether a persisted setting is honoured or destroyed.
 * Kept apart from the Context lookups so it can run off-device. Mostly path math, but
 * {@link #resolveRoot} also stats the base it is handed.
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
   * Default mirror root: the classic public HTTrack/Websites when shared storage is ours to
   * write, so other apps and upgraders find the mirrors, else our own private Websites.
   *
   * <p>Takes its roots in the same order as {@link #isWritable}, since a transposition between
   * same-typed neighbours would compile and still return a plausible directory.
   *
   * @param external
   *          getExternalFilesDir(null), null while the volume is unmounted
   * @param internal
   *          getFilesDir(), the fallback root
   * @param shared
   *          the public root (e.g. getExternalStorageDirectory()) when all-files access is held,
   *          else null
   */
  static File defaultRoot(final File external, final File internal, final File shared) {
    if (shared != null) {
      return new File(new File(shared, "HTTrack"), "Websites");
    }
    return new File(external != null ? external : internal, "Websites");
  }

  /**
   * Whether the freshly resolved root differs from the one in use, the first resolution counting
   * as a move. Gates the work that must happen once per move, not once per resume.
   *
   * @param previous
   *          the root in use, null before anything was resolved
   */
  static boolean rootMoved(final File previous, final File resolved) {
    return previous == null || !previous.equals(resolved);
  }

  /** The side effects of a move, behind an interface so a test can record them instead. */
  interface RootMoveActions {
    /** The persisted base named a directory we could not create; worth saying once. */
    void warnMissingDirectory();

    /** Point the native side at the new root, so its logs land under it. */
    void initNativeRoot(final File root);

    /** The listed projects are the ones under the root, so the suggestions went stale. */
    void refreshProjectSuggestions();
  }

  /**
   * Runs the work owed once per move, and nothing at all otherwise: initRootPath leaks a buffer
   * per call and the warning would loop as a toast, both of which a resume would repeat.
   *
   * @param previous
   *          the root in use, null before anything was resolved
   * @param resolved
   *          the freshly resolved root
   * @param missingDir
   *          whether the persisted base could not be created
   * @param actions
   *          the side effects to run
   * @return whether the root moved
   */
  static boolean applyRootMove(final File previous, final File resolved, final boolean missingDir,
      final RootMoveActions actions) {
    if (!rootMoved(previous, resolved)) {
      return false;
    }
    if (missingDir) {
      actions.warnMissingDirectory();
    }
    actions.initNativeRoot(resolved);
    if (previous != null) {
      // Nothing was listed before the first resolution.
      actions.refreshProjectSuggestions();
    }
    return true;
  }

  /**
   * Mirror root to use: the persisted base when it is vetted writable and present, else the
   * current default. Deliberately blind to the root in use, so that gaining or losing access
   * re-points the running session instead of waiting for the next cold start.
   *
   * @param base
   *          the persisted base path, or null when none is set; call after any mkdirs attempt,
   *          since a base that is not yet a directory is refused
   * @param writable
   *          {@link #isWritable} verdict for base; only a decided yes is honoured
   * @param defaultRoot
   *          the fallback root
   * @return base, or defaultRoot whenever base is unset, unvetted or not a directory
   */
  static File resolveRoot(final File base, final Boolean writable, final File defaultRoot) {
    if (base != null && Boolean.TRUE.equals(writable) && base.isDirectory()) {
      return base;
    }
    return defaultRoot;
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
