package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** A lifecycle override that never chains throws SuperNotCalledException; onConfigurationChanged
 *  did not, and crashed the app on every rotation (#186). */
public class LifecycleSuperCallTest {
  /** Void callbacks whose base implementation the framework requires. The boolean menu callbacks
   *  are left out, since answering without chaining is how they are meant to be used. */
  private static final List<String> CHAINED = Arrays.asList("onCreate", "onStart", "onRestart",
      "onResume", "onPostCreate", "onPostResume", "onPause", "onStop", "onDestroy",
      "onSaveInstanceState", "onRestoreInstanceState", "onActivityResult",
      "onRequestPermissionsResult", "onAttach", "onDetach", "onConfigurationChanged",
      "onLowMemory", "onTrimMemory", "onNewIntent", "onViewCreated", "onDestroyView",
      "onTerminate");

  private static final Pattern OVERRIDE = Pattern.compile("(?m)^[ \t]*(?:public|protected)\\s+"
      + "(?:final\\s+)?void\\s+(" + join(CHAINED) + ")\\s*\\(");

  private static String join(final List<String> names) {
    final StringBuilder joined = new StringBuilder();
    for (final String name : names) {
      joined.append(joined.length() == 0 ? "" : "|").append(name);
    }
    return joined.toString();
  }

  /** Overrides of SOURCE that never chain, named LABEL.method; every override found is added to
   *  SEEN. Comments are blanked first, so a commented-out call cannot pass for one. */
  private static List<String> missingSuper(final String label, final String source,
      final List<String> seen) {
    final String code = TestSources.withoutCommentsAndStrings(source);
    final List<String> missing = new ArrayList<String>();
    final Matcher matcher = OVERRIDE.matcher(code);
    while (matcher.find()) {
      final String name = matcher.group(1);
      seen.add(label + "." + name);
      if (!TestSources.balancedBlock(code, matcher.end()).contains("super." + name + "(")) {
        missing.add(label + "." + name);
      }
    }
    return missing;
  }

  @Test
  public void everyLifecycleOverrideChains() throws IOException {
    final List<String> seen = new ArrayList<String>();
    final List<String> missing = new ArrayList<String>();
    for (final File file : TestSources.javaSources()) {
      missing.addAll(missingSuper(file.getName(), TestSources.read(file), seen));
    }
    // A scan that read nothing would pass, so name overrides the tree has today.
    assertTrue(seen.toString(), seen.containsAll(Arrays.asList(
        "HTTrackActivity.java.onCreate", "HTTrackActivity.java.onResume",
        "HTTrackActivity.java.onConfigurationChanged", "HTTrackActivity.java.onSaveInstanceState",
        "HTTrackActivity.java.onRequestPermissionsResult", "OptionsActivity.java.onCreate",
        "CleanupActivity.java.onCreate", "FileChooserActivity.java.onCreate",
        "HTTrackApplication.java.onCreate")));
    assertEquals(new TreeSet<String>(), new TreeSet<String>(missing));
  }

  /** The scan itself, against an override that chains and one that does not. */
  @Test
  public void scanTellsThemApart() {
    final String chains = "  public void onResume() {\n    super.onResume();\n  }\n";
    final String silent = "  public void onConfigurationChanged(final Configuration c) {\n"
        + "    // super.onConfigurationChanged(c);\n  }\n";
    final List<String> seen = new ArrayList<String>();
    assertEquals(Arrays.asList(), missingSuper("x", chains, seen));
    assertEquals(Arrays.asList("x.onConfigurationChanged"), missingSuper("x", silent, seen));
    assertEquals(Arrays.asList("x.onResume", "x.onConfigurationChanged"), seen);
  }
}
