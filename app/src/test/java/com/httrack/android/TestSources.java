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

  /** Body of the OptionsMapper table NAME, comments dropped. */
  static String tableBody(final String name) throws IOException {
    final String source = javaSource("OptionsMapper");
    final int head = source.indexOf(name + "[] = new Pair[] {");
    if (head == -1) {
      throw new IllegalStateException("no " + name + " table");
    }
    final int from = source.indexOf('{', head) + 1;
    int depth = 1;
    int at = from;
    for (; at < source.length() && depth > 0; at++) {
      final char c = source.charAt(at);
      if (c == '"') {
        // A footer default holds {url}, which must not close the table.
        while (++at < source.length() && source.charAt(at) != '"') {
          if (source.charAt(at) == '\\') {
            at++;
          }
        }
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
      }
    }
    if (depth != 0) {
      throw new IllegalStateException(name + " table does not end");
    }
    return source.substring(from, at - 1).replaceAll("(?s)/\\*.*?\\*/", "")
        .replaceAll("(?m)^\\s*//.*$", "");
  }

  /** Keys the table NAME declares, in order; PATTERN captures one entry's key.
   *  Entries are counted by a "new Pair" no line break can split, so a
   *  declaration PATTERN cannot read fails the scrape instead of vanishing. */
  static List<String> tableKeys(final String name, final String pattern)
      throws IOException {
    final String body = tableBody(name);
    final Matcher m = Pattern.compile(pattern).matcher(body);
    final List<String> keys = new ArrayList<String>();
    while (m.find()) {
      keys.add(m.group(1));
    }
    final int declared = occurrences(body, "new Pair");
    if (keys.size() != declared) {
      throw new IllegalStateException("parsed " + keys.size() + " of "
          + declared + " " + name + " entries");
    }
    return keys;
  }

  /** The winprofile.ini keys fieldsSerializer declares, in order. The table
   *  pulls in R.id, which the stub android.jar cannot load. */
  static List<String> serializerKeys() throws IOException {
    return tableKeys("fieldsSerializer",
        "new Pair<Integer, String>\\(\\s*R\\.id\\.\\w+\\s*,\\s*\"([^\"]+)\"\\s*\\)");
  }
}
