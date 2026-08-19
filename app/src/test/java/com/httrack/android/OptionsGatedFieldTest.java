package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * A widget greyed out because the engine would ignore it must still be saved: the profile
 * keeps the value either way.
 */
public class OptionsGatedFieldTest {
  /** Layout, controlling box, box state that enables, gated views. */
  private static final String[] EXPECTED = {
      "activity_options_build checkDosNames=false checkIso9660",
      "activity_options_spider checkAcceptCookies=true "
          + "textCookiesFile,editCookiesFile" };

  private static final Pattern GATE = Pattern
      .compile("enableWhile\\(view, R\\.id\\.(\\w+), (true|false),([^;]*)\\);");

  private static final Pattern TAB_LAYOUT = Pattern
      .compile("@ActivityId\\(R\\.layout\\.(\\w+)\\)");

  /** Layout of the tab class the call at OFFSET is in. */
  private static String enclosingTab(final String source, final int offset) {
    final Matcher m = TAB_LAYOUT.matcher(source.substring(0, offset));
    String layout = null;
    while (m.find()) {
      layout = m.group(1);
    }
    assertFalse("gate outside any tab", layout == null);
    return layout;
  }

  /** Every gate the options screen wires, in the EXPECTED notation. */
  private static Set<String> gates() throws IOException {
    final String source = TestSources.javaSource("OptionsActivity");
    final Set<String> gates = new LinkedHashSet<String>();
    final Matcher m = GATE.matcher(source);
    while (m.find()) {
      final List<String> gated = OptionsTabFieldsTest.ids(
          OptionsTabFieldsTest.FIELD_ID, m.group(3));
      assertFalse("gate on nothing: " + m.group(), gated.isEmpty());
      gates.add(enclosingTab(source, m.start()) + " " + m.group(1) + "="
          + m.group(2) + " " + String.join(",", gated));
    }
    return gates;
  }

  @Test
  public void theOptionsScreenWiresTheGatesItIsMeantTo() throws IOException {
    assertEquals(new LinkedHashSet<String>(Arrays.asList(EXPECTED)), gates());
  }

  @Test
  public void aGatedViewIsInItsTabsLayoutAndStillSaved() throws IOException {
    final Set<String> mapped = new HashSet<String>(OptionsTabFieldsTest.ids(
        OptionsTabFieldsTest.FIELD_ID, TestSources.javaSource("OptionsMapper")));
    final Map<String, List<String>> tabs = OptionsTabFieldsTest.tabs();
    for (final String gate : gates()) {
      final String[] parts = gate.split("[ =]");
      final String layout = parts[0];
      final List<String> views = new ArrayList<String>();
      views.add(parts[1]);
      views.addAll(Arrays.asList(parts[3].split(",")));
      final List<String> fields = tabs.get(layout);
      assertFalse("no tab for " + layout, fields == null);
      for (final File file : TestSources.layouts(layout)) {
        final Set<String> declared = new HashSet<String>(
            OptionsTabFieldsTest.ids(OptionsTabFieldsTest.LAYOUT_ID,
                TestSources.read(file)));
        for (final String view : views) {
          assertTrue(file.getName() + " has no " + view, declared.contains(view));
        }
      }
      for (final String view : views) {
        assertTrue(view + " is an option the tab does not save",
            !mapped.contains(view) || fields.contains(view));
      }
    }
  }
}
