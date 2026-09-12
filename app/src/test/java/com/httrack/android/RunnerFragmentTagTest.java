package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.Test;

/** The crawl fragment's tag once carried System.nanoTime(), and saveInstanceState() wrote it
 *  into both the saved bundle and the abort notification's extras. A cold launch from that
 *  notification therefore adopted a dead process's tag. These pin that the tag is a constant,
 *  that no bundle carries it, and that removing its key left the other keys alone. */
public class RunnerFragmentTagTest {
  private static final String TAG = "RUNNER_FRAGMENT_TAG";
  private static final String OLD_KEY = "com.httrack.android.sessionID";

  /** HTTrackActivity with comments and string literals blanked, so a commented-out call cannot
   *  pass for one and a brace in a literal is not counted. */
  private static String activity() throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
  }

  /** Body of the method whose declaration starts with SIGNATURE, braces balanced. */
  private static String body(final String source, final String signature) {
    final int at = source.indexOf(signature);
    assertTrue("no " + signature.trim(), at != -1);
    return TestSources.balancedBlock(source, at + signature.length());
  }

  @Test
  public void theTagIsAConstantWithNothingPerProcessInIt() throws IOException {
    assertTrue("the tag must be a compile-time constant", TestSources.javaSource("HTTrackActivity")
        .contains("protected static final String " + TAG + " = \"runner_task\";"));
    for (final String source : new String[] { "nanoTime", "currentTimeMillis", "randomUUID",
        "new Random" }) {
      assertFalse("a per-process tag outlives its process: " + source,
          TestSources.between(activity(), TAG, ";").contains(source));
    }
  }

  @Test
  public void everyUseOfTheTagIsAFragmentLookupOrAdd() throws IOException {
    final String source = activity();
    assertTrue("no lookup by tag",
        source.contains("findFragmentByTag(" + TAG + ")"));
    assertTrue("no add under the tag", source.contains("add(runner, " + TAG + ")"));
    // A use that is not a tag would need per-run uniqueness, which a constant cannot give.
    assertEquals("the tag has a use that is neither a lookup nor an add", 3,
        TestSources.occurrences(source, TAG) - 1);
  }

  @Test
  public void noBundleAndNoIntentCarriesTheTag() throws IOException {
    for (final File file : TestSources.javaSources()) {
      assertFalse(file.getName() + " still carries the old key",
          TestSources.read(file).contains(OLD_KEY));
    }
    final String source = activity();
    assertFalse("saved state must not carry the tag", body(source,
        "protected void saveInstanceState(final Bundle outState)").contains(TAG));
    // sendAbortNotification() puts this same bundle into the notification's extras.
    assertFalse("a restore must not adopt a tag from a bundle", body(source,
        "protected boolean restoreInstanceState(final Bundle savedInstanceState)")
        .contains(TAG));
  }

  /** Body of SIGNATURE as written. The block is located on the blanked source, so a brace in a
   *  literal cannot miscount, then sliced from the real one, which still holds the key names. */
  private static String rawBody(final String signature) throws IOException {
    final String blanked = activity();
    final int at = blanked.indexOf(signature);
    assertTrue("no " + signature.trim(), at != -1);
    final int from = blanked.indexOf('{', at + signature.length());
    assertTrue("no block for " + signature.trim(), from != -1);
    int depth = 0;
    for (int i = from; i < blanked.length(); i++) {
      if (blanked.charAt(i) == '{') {
        depth++;
      } else if (blanked.charAt(i) == '}' && --depth == 0) {
        return TestSources.javaSource("HTTrackActivity").substring(from + 1, i);
      }
    }
    throw new IllegalStateException(signature.trim() + " never closes");
  }

  @Test
  public void theOtherSavedKeysStillMakeTheRoundTrip() throws IOException {
    final String save = rawBody("protected void saveInstanceState(final Bundle outState)");
    final String restore = rawBody(
        "protected boolean restoreInstanceState(final Bundle savedInstanceState)");
    for (final String key : new String[] { "VERSION_CODE_NAME", "MAP_NAME", "PANE_NAME",
        "com.httrack.android.loadedProjectName", "com.httrack.android.focus_id" }) {
      assertTrue(key + " is no longer saved", save.contains(key));
      assertTrue(key + " is no longer restored", restore.contains(key));
    }
  }

  @Test
  public void theVersionGuardStillDecidesBeforeAnythingIsRead() throws IOException {
    final String restore = body(activity(),
        "protected boolean restoreInstanceState(final Bundle savedInstanceState)");
    // An older bundle still holds the removed key; the guard is what makes that harmless.
    final int guard = restore.indexOf("if (version != versionCode)");
    assertTrue("no version guard", guard != -1);
    assertTrue("the guard must refuse the bundle",
        TestSources.balancedBlock(restore, guard).contains("return false"));
    final int first = restore.indexOf("savedInstanceState.get");
    assertTrue("nothing reads the bundle", first != -1 && first < guard);
    assertEquals("the version must be the first thing read", first,
        restore.indexOf("savedInstanceState.getInt(VERSION_CODE_NAME)"));
    final int next = restore.indexOf("savedInstanceState.get", first + 1);
    assertTrue("nothing else reads the bundle", next != -1);
    assertTrue("a read ahead of the guard would act on a bundle it refuses", next > guard);
  }
}
