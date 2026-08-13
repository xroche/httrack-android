package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * A tab's declared field must be in that tab's own layout: leaving the tab
 * looks it up, and WidgetDataExchange throws on a view it cannot find.
 */
public class OptionsTabFieldsTest {
  /** @ActivityId + @Fields of one tab, as declared in the source. */
  private static final Pattern TAB = Pattern.compile(
      "@ActivityId\\(R\\.layout\\.(\\w+)\\)(.*?)public static class",
      Pattern.DOTALL);

  private static final Pattern FIELD_ID = Pattern.compile("R\\.id\\.(\\w+)");

  private static final Pattern LAYOUT_ID = Pattern
      .compile("android:id=\"@\\+?id/(\\w+)\"");

  private static List<String> ids(final Pattern pattern, final String text) {
    final List<String> ids = new ArrayList<String>();
    final Matcher m = pattern.matcher(text);
    while (m.find()) {
      ids.add(m.group(1));
    }
    return ids;
  }

  /** Layout of each option tab, with the field ids that tab declares. */
  private static Map<String, List<String>> tabs() throws IOException {
    final Map<String, List<String>> tabs = new LinkedHashMap<String, List<String>>();
    final Matcher tab = TAB.matcher(TestSources.javaSource("OptionsActivity"));
    while (tab.find()) {
      final List<String> fields = ids(FIELD_ID, tab.group(2));
      // An annotation order this pattern cannot read would yield none.
      assertFalse("no field parsed for " + tab.group(1), fields.isEmpty());
      assertFalse("twice: " + tab.group(1), tabs.containsKey(tab.group(1)));
      tabs.put(tab.group(1), fields);
    }
    assertTrue("parsed " + tabs.size() + " tabs", tabs.size() > 10);
    return tabs;
  }

  @Test
  public void everyTabFieldLivesInThatTabsLayout() throws IOException {
    for (final Map.Entry<String, List<String>> tab : tabs().entrySet()) {
      final List<File> files = TestSources.layouts(tab.getKey());
      assertFalse("no layout named " + tab.getKey(), files.isEmpty());
      for (final File file : files) {
        final Set<String> declared = new HashSet<String>(ids(LAYOUT_ID,
            TestSources.read(file)));
        for (final String field : tab.getValue()) {
          assertTrue(file.getName() + " has no " + field,
              declared.contains(field));
        }
      }
    }
  }

  /* The other direction: a view the mapper knows but no tab claims is never
     saved, and nothing at runtime says so. */
  @Test
  public void everyMappedOptionViewIsClaimedByATab() throws IOException {
    final Set<String> mapped = new HashSet<String>(ids(FIELD_ID,
        TestSources.javaSource("OptionsMapper")));
    assertTrue("parsed " + mapped.size() + " mapped ids", mapped.size() > 80);
    final Map<String, List<String>> tabs = tabs();
    int claimed = 0;
    for (final File file : TestSources.layouts()) {
      if (!file.getName().startsWith("activity_options_")) {
        continue;
      }
      final String layout = file.getName().replaceFirst("\\.xml$", "");
      for (final String id : ids(LAYOUT_ID, TestSources.read(file))) {
        if (!mapped.contains(id)) {
          continue;
        }
        final List<String> owners = new ArrayList<String>();
        for (final Map.Entry<String, List<String>> tab : tabs.entrySet()) {
          if (tab.getValue().contains(id)) {
            owners.add(tab.getKey());
          }
        }
        assertEquals(id + " in " + layout + " claimed by " + owners, 1,
            owners.size());
        assertEquals(id + " is claimed by " + owners.get(0), layout,
            owners.get(0));
        claimed++;
      }
    }
    assertTrue("checked " + claimed + " views", claimed > 50);
  }
}
