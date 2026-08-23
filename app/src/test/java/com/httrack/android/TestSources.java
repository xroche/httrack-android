package com.httrack.android;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  /** A file below res/, such as "values/strings.xml". */
  static File resFile(final String name) {
    return new File(dir("src/main/res"), name);
  }

  static String read(final File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), "UTF-8");
  }

  /** Source of the com.httrack.android class NAME. */
  static String javaSource(final String name) throws IOException {
    return read(new File(dir("src/main/java"), "com/httrack/android/" + name
        + ".java"));
  }

  /** Source of the JNI glue, for contracts no JUnit test can reach at runtime. */
  static String jniSource() throws IOException {
    return read(new File(dir("src/main/jni"), "htslibjni.c"));
  }

  /** A file of the pinned engine submodule, such as "winprofile-keys.tsv". */
  static File engineFile(final String name) {
    return new File(dir("src/main/jni/httrack"), name);
  }

  static int occurrences(final String source, final String text) {
    int count = 0;
    for (int at = source.indexOf(text); at != -1; at = source.indexOf(text,
        at + 1)) {
      count++;
    }
    return count;
  }

  /** The winprofile.ini keys fieldsSerializer declares, in order. The table
   *  pulls in R.id, which the stub android.jar cannot load, and three of its
   *  entries wrap across two lines. */
  static List<String> serializerKeys() throws IOException {
    final String source = javaSource("OptionsMapper");
    final String declaration = "new Pair<Integer, String>(R.id.";
    final Matcher m = Pattern.compile(
        "new Pair<Integer, String>\\(R\\.id\\.\\w+,\\s*\"([^\"]+)\"\\)")
        .matcher(source);
    final List<String> keys = new ArrayList<String>();
    while (m.find()) {
      keys.add(m.group(1));
    }
    // A regex that quietly stopped matching would shorten every list built here.
    if (keys.size() != occurrences(source, declaration)) {
      throw new IllegalStateException("parsed " + keys.size() + " of "
          + occurrences(source, declaration) + " fieldsSerializer entries");
    }
    return keys;
  }
}
