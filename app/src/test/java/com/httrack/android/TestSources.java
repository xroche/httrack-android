package com.httrack.android;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Checked-in sources the tests read; they run with the app project as
 *  working directory. */
final class TestSources {
  private TestSources() {
  }

  private static File dir(final String path) {
    for (final String prefix : new String[] { "", "app/" }) {
      final File dir = new File(prefix + path);
      if (dir.isDirectory()) {
        return dir;
      }
    }
    throw new IllegalStateException("no " + path + " below "
        + new File(".").getAbsolutePath());
  }

  /** Every layout, qualified variants such as layout-land/ included. */
  static List<File> layouts() {
    return layouts(null);
  }

  /** Every variant of NAME, all of which may be inflated; every layout when
   *  NAME is null. */
  static List<File> layouts(final String name) {
    final List<File> files = new ArrayList<File>();
    for (final File res : dir("src/main/res").listFiles()) {
      if (!res.isDirectory() || !res.getName().startsWith("layout")) {
        continue;
      }
      for (final File file : res.listFiles()) {
        if (name == null ? file.getName().endsWith(".xml")
            : file.getName().equals(name + ".xml")) {
          files.add(file);
        }
      }
    }
    return files;
  }

  static String read(final File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), "UTF-8");
  }

  /** Source of the com.httrack.android class NAME. */
  static String javaSource(final String name) throws IOException {
    return read(new File(dir("src/main/java"), "com/httrack/android/" + name
        + ".java"));
  }
}
