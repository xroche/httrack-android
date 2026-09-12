package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** FLAG_KEEP_SCREEN_ON is honoured for as long as the window carries it, so one left behind
 *  keeps the display awake over a crawl that is already over. These prove the activity asks
 *  ScreenOnPolicy for every answer, and drops the flag on every way out. */
public class KeepScreenOnTest {
  private static final String KEY = "KeepScreenOnWhileMirroring";
  private static final String FLAG = "WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON";
  private static final String PREF = "getSharedPreferences(PREFS_NAME, 0)";

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

  /** Body of refreshKeepScreenOn, so a decoy call elsewhere in the file cannot stand in. */
  private static String refresh() throws IOException {
    return body(activity(), "private void refreshKeepScreenOn()");
  }

  /** BODY holds FIRST then SECOND. An absent one reads as -1, which compares as ordered. */
  private static void assertInOrder(final String message, final String body, final String first,
      final String second) {
    assertTrue("no " + first, body.contains(first));
    assertTrue("no " + second, body.contains(second));
    assertTrue(message, body.indexOf(first) < body.indexOf(second));
  }

  /** Brace depth of the first CALL inside BODY; zero means nothing conditions it. */
  private static int depthOf(final String body, final String call) {
    final int at = body.indexOf(call);
    assertTrue("no " + call, at != -1);
    int depth = 0;
    for (int i = 0; i < at; i++) {
      if (body.charAt(i) == '{') {
        depth++;
      } else if (body.charAt(i) == '}') {
        depth--;
      }
    }
    return depth;
  }

  /** ARGUMENTS split at top-level commas, whitespace collapsed. */
  private static List<String> split(final String arguments) {
    final List<String> parts = new ArrayList<String>();
    int depth = 0;
    int from = 0;
    for (int i = 0; i < arguments.length(); i++) {
      final char c = arguments.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(arguments.substring(from, i).trim().replaceAll("\\s+", " "));
        from = i + 1;
      }
    }
    parts.add(arguments.substring(from).trim().replaceAll("\\s+", " "));
    return parts;
  }

  @Test
  public void thePolicyIsAskedWithTheUserChoiceThePaneAndTheCrawlInThatOrder() throws IOException {
    // Naming the call is not enough: a constant folded into any of the three would pass that.
    assertEquals(Arrays.asList(PREF + ".getBoolean(KEEP_SCREEN_ON_NAME, false)",
        "pane_id == LAYOUT_MIRROR_PROGRESS", "runner != null && runner.hasLiveRunner()"),
        split(TestSources.arguments(refresh(), "ScreenOnPolicy.keepScreenOn")));
  }

  @Test
  public void theVerdictReachesTheWindowBothWays() throws IOException {
    final String refresh = refresh();
    assertTrue("the verdict must drive the window flag",
        refresh.contains("getWindow().addFlags(" + FLAG + ")"));
    assertTrue("a no must clear the flag, not merely skip setting it",
        refresh.contains("getWindow().clearFlags(" + FLAG + ")"));
    assertInOrder("the policy answer is what selects the branch", refresh,
        "ScreenOnPolicy.keepScreenOn", "if (keep)");
  }

  @Test
  public void everyPaneChangeReconsidersTheFlag() throws IOException {
    final String pane = body(activity(), "private void setPane(final int position)");
    // Inside the pane_id != position block it would never fire on the way out of the crawl.
    assertEquals("the refresh must not be conditional", 0,
        depthOf(pane, "refreshKeepScreenOn()"));
    // The refresh reads pane_id, so ahead of that assignment it would judge the pane being left.
    assertInOrder("the refresh must see the pane being entered", pane, "pane_id = position;",
        "refreshKeepScreenOn()");
  }

  @Test
  public void theProgressPaneShowsTheOption() throws IOException {
    final String pane = TestSources.between(activity(),
        "case R.layout.activity_mirror_progress:", "break;");
    assertTrue("nothing offers the option if the pane never wires it",
        pane.contains("wireKeepScreenOn()"));
    for (final File file : TestSources.layouts("activity_mirror_progress")) {
      assertTrue(file.getName() + " has no checkKeepScreenOn",
          TestSources.read(file).contains("android:id=\"@+id/checkKeepScreenOn\""));
    }
  }

  @Test
  public void tickingTheOptionPersistsItThenReappliesIt() throws IOException {
    final String wire = body(activity(), "private void wireKeepScreenOn()");
    assertTrue("the tick has to outlive the pane",
        wire.contains(PREF + ".edit()") && wire.contains("putBoolean(KEEP_SCREEN_ON_NAME,"));
    assertInOrder("the box must be set before the listener, or inflating writes the preference",
        wire, "setChecked(", "setOnCheckedChangeListener(");
    assertInOrder("a tick that changes nothing until the next pane change is not the option",
        wire, "putBoolean(KEEP_SCREEN_ON_NAME,", "refreshKeepScreenOn()");
  }

  @Test
  public void teardownDropsTheFlagWhateverTheCrawlIsDoing() throws IOException {
    // The runner fragment is torn down by super.onDestroy(), so a refresh here still reads live.
    final String destroy = body(activity(), "\n  public void onDestroy()");
    assertEquals("the clear must not be conditional", 0,
        depthOf(destroy, "getWindow().clearFlags(" + FLAG + ")"));
    assertFalse("a refresh would re-hold the flag on the way out",
        destroy.contains("refreshKeepScreenOn()"));
    assertInOrder("the window is what carries the flag, so clear it before it goes", destroy,
        "clearFlags(" + FLAG + ")", "super.onDestroy()");
  }

  @Test
  public void thePreferenceNeverReachesTheSharedProfile() throws IOException {
    // winprofile.ini is read by WinHTTrack, which has no such window.
    assertFalse("the key is app-local", TestSources.serializerKeys().contains(KEY));
    final String mapper = TestSources.javaSource("OptionsMapper");
    for (final String name : new String[] { KEY, "KEEP_SCREEN_ON_NAME", "checkKeepScreenOn" }) {
      assertFalse(name + " must stay out of OptionsMapper", mapper.contains(name));
    }
  }

  @Test
  public void theKeyLivesInTheAppPreferencesNotTheOptionDefaults() throws IOException {
    // resetDefaultPreferences() clears the option-defaults file whole, this one included.
    assertTrue(TestSources.javaSource("HTTrackActivity")
        .contains("String PREFS_NAME = \"HTTrackPreferences\""));
    assertTrue(TestSources.javaSource("OptionsMapper")
        .contains("String PREFS_NAME = \"HTTrackDefaultSettings\""));
  }

  @Test
  public void theLabelPromisesNothingAboutTheBackground() throws IOException {
    // The flag lapses the moment the window stops being the visible one.
    final String strings = TestSources.read(TestSources.resFile("values/strings.xml"));
    final int at = strings.indexOf("<string name=\"keep_screen_on\">");
    assertTrue("no keep_screen_on string", at != -1);
    final String label = strings.substring(at, strings.indexOf("</string>", at)).toLowerCase();
    for (final String promise : new String[] { "background", "even when", "continues" }) {
      assertFalse("the label must not promise " + promise, label.contains(promise));
    }
    assertTrue("the label has to say what stops the copy",
        label.contains("leave") || label.contains("stops"));
  }
}
