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
 *  did not, and crashed the app on every rotation (#186).
 *  Two things this cannot see: super.onCreate(null), or a chain on another overload, sets the
 *  flag the framework checks while dropping the argument; and the scan reads .java under
 *  src/main/java, so a Kotlin source or another source set would need widening. */
public class LifecycleSuperCallTest {
  /** Void callbacks whose base implementation the framework requires. The boolean menu callbacks
   *  are left out, since answering without chaining is how they are meant to be used. */
  private static final List<String> CHAINED = Arrays.asList("onCreate", "onStart", "onRestart",
      "onResume", "onPostCreate", "onPostResume", "onPause", "onStop", "onDestroy",
      "onSaveInstanceState", "onRestoreInstanceState", "onActivityResult",
      "onRequestPermissionsResult", "onAttach", "onDetach", "onConfigurationChanged",
      "onLowMemory", "onTrimMemory", "onNewIntent", "onViewCreated", "onDestroyView",
      "onTerminate");

  /** Any declaration of one of them, whatever its modifiers and same-line annotations, so that a
   *  style the scan cannot read fails the coverage check below instead of vanishing. */
  private static final Pattern DECLARATION = Pattern.compile("(?m)^[ \t]*"
      + "((?:@\\w+(?:\\([^)\n]*\\))?[ \t]+)*"
      + "(?:(?:public|protected|private|final|static|synchronized|strictfp)[ \t]+)*)"
      + "void[ \t]+(" + join(CHAINED) + ")[ \t]*\\(");

  /** The name used other than as a call on a receiver, which a declaration is. */
  private static final Pattern MENTION = Pattern.compile("(?<![.\\w])(" + join(CHAINED)
      + ")\\s*\\(");

  private static String join(final List<String> names) {
    final StringBuilder joined = new StringBuilder();
    for (final String name : names) {
      joined.append(joined.length() == 0 ? "" : "|").append(name);
    }
    return joined.toString();
  }

  /** What one source yields: the overrides read, those of them that never chain, and the
   *  declarations no pattern here could read. */
  private static final class Scan {
    final List<String> seen = new ArrayList<String>();
    final List<String> missing = new ArrayList<String>();
    final List<String> unread = new ArrayList<String>();
  }

  /** Reads SOURCE into RESULT, naming everything LABEL.method. Comments and strings are blanked
   *  first, so a commented-out call cannot pass for one. */
  private static void scan(final String label, final String source, final Scan result) {
    final String code = TestSources.withoutCommentsAndStrings(source);
    final List<int[]> declarations = new ArrayList<int[]>();
    final Matcher declaration = DECLARATION.matcher(code);
    while (declaration.find()) {
      declarations.add(new int[] { declaration.start(), declaration.end() });
      final String name = declaration.group(2);
      // A private helper of the same name is not a framework callback.
      if (!declaration.group(1).contains("public")
          && !declaration.group(1).contains("protected")) {
        continue;
      }
      result.seen.add(label + "." + name);
      if (!TestSources.balancedBlock(code, declaration.end()).contains("super." + name + "(")) {
        result.missing.add(label + "." + name);
      }
    }
    final Matcher mention = MENTION.matcher(code);
    while (mention.find()) {
      boolean declared = false;
      for (final int[] span : declarations) {
        declared |= span[0] <= mention.start() && mention.start() < span[1];
      }
      if (!declared) {
        result.unread.add(label + "." + mention.group(1));
      }
    }
  }

  @Test
  public void everyLifecycleOverrideChains() throws IOException {
    final Scan result = new Scan();
    for (final File file : TestSources.javaSources()) {
      scan(file.getName(), TestSources.read(file), result);
    }
    // A scan that read nothing would pass, so name overrides the tree has today.
    assertTrue(result.seen.toString(), result.seen.containsAll(Arrays.asList(
        "HTTrackActivity.java.onCreate", "HTTrackActivity.java.onResume",
        "HTTrackActivity.java.onConfigurationChanged", "HTTrackActivity.java.onSaveInstanceState",
        "HTTrackActivity.java.onRequestPermissionsResult", "OptionsActivity.java.onCreate",
        "CleanupActivity.java.onCreate", "FileChooserActivity.java.onCreate",
        "HTTrackApplication.java.onCreate")));
    assertEquals(new TreeSet<String>(), new TreeSet<String>(result.unread));
    assertEquals(new TreeSet<String>(), new TreeSet<String>(result.missing));
  }

  private static Scan scanOf(final String source) {
    final Scan result = new Scan();
    scan("x", source, result);
    return result;
  }

  @Test
  public void chainingIsToldFromSilence() {
    assertEquals(Arrays.asList(),
        scanOf("  public void onResume() {\n    super.onResume();\n  }\n").missing);
    assertEquals(Arrays.asList("x.onConfigurationChanged"),
        scanOf("  public void onConfigurationChanged(final Configuration c) {\n"
            + "    // super.onConfigurationChanged(c);\n  }\n").missing);
  }

  @Test
  public void unusualDeclarationStylesAreRead() {
    for (final String head : Arrays.asList("  public synchronized void onDestroy()",
        "  @Override public void onDestroy()", "  @SuppressWarnings(\"x\") protected void"
            + " onDestroy()", "  public final void onDestroy()")) {
      final Scan result = scanOf(head + " {\n  }\n");
      assertEquals(head, Arrays.asList("x.onDestroy"), result.missing);
      assertEquals(head, Arrays.asList(), result.unread);
    }
  }

  /** A declaration no pattern reads must fail the run, not drop out of it. */
  @Test
  public void anUnreadableDeclarationIsLoud() {
    assertEquals(Arrays.asList("x.onStop"),
        scanOf("  public void\n  onStop() {\n  }\n").unread);
    // A private helper of that name is neither a callback nor a miss.
    final Scan helper = scanOf("  private void onStop() {\n  }\n");
    assertEquals(Arrays.asList(), helper.missing);
    assertEquals(Arrays.asList(), helper.unread);
  }
}
