package com.httrack.android;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * A tab looks its declared fields up in its own inflated layout, and
 * WidgetDataExchange throws on a view it cannot find, so an id left behind by a
 * field that moved to another tab crashes that tab as soon as it is left.
 */
public class OptionsTabFieldsTest {
  /** @ActivityId + @Fields of one tab, as declared in the source. */
  private static final Pattern TAB = Pattern.compile(
      "@ActivityId\\(R\\.layout\\.(\\w+)\\)(.*?)public static class",
      Pattern.DOTALL);

  private static final Pattern FIELD_ID = Pattern.compile("R\\.id\\.(\\w+)");

  /* Unit tests run with the app project as working directory. */
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

  /* Every qualified variant of LAYOUT, all of which may be inflated. */
  private static List<File> layouts(final String layout) {
    final List<File> files = new ArrayList<File>();
    for (final File res : dir("src/main/res").listFiles()) {
      if (!res.isDirectory() || !res.getName().startsWith("layout")) {
        continue;
      }
      final File file = new File(res, layout + ".xml");
      if (file.isFile()) {
        files.add(file);
      }
    }
    return files;
  }

  private static Set<String> declaredIds(final File layout) throws IOException {
    final Set<String> ids = new HashSet<String>();
    final Matcher m = Pattern.compile("android:id=\"@\\+?id/(\\w+)\"").matcher(
        new String(Files.readAllBytes(layout.toPath()), "UTF-8"));
    while (m.find()) {
      ids.add(m.group(1));
    }
    return ids;
  }

  @Test
  public void everyTabFieldLivesInThatTabsLayout() throws IOException {
    final String src = new String(Files.readAllBytes(Paths
        .get(dir("src/main/java").getPath(),
            "com/httrack/android/OptionsActivity.java")), "UTF-8");
    final Matcher tab = TAB.matcher(src);
    int tabs = 0;
    int fields = 0;
    while (tab.find()) {
      final String layout = tab.group(1);
      final List<File> files = layouts(layout);
      assertTrue("no layout named " + layout, !files.isEmpty());
      final Matcher id = FIELD_ID.matcher(tab.group(2));
      tabs++;
      while (id.find()) {
        fields++;
        for (final File file : files) {
          assertTrue(file.getName() + " has no " + id.group(1),
              declaredIds(file).contains(id.group(1)));
        }
      }
    }
    assertTrue("parsed " + tabs + " tabs", tabs > 10);
    assertTrue("parsed " + fields + " fields", fields > 80);
  }
}
