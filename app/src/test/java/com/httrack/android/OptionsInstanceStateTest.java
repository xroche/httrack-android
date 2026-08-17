package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/**
 * OptionsActivity's instance-state hooks (issue #129): without them, any recreation reverted
 * every unsaved option edit. Only the pane decisions run here; neither Bundle nor SparseArray
 * is mocked by the stub android.jar, so the map round trip itself is read from the source.
 */
public class OptionsInstanceStateTest {
  /** Body of an OptionsActivity method, up to the closing brace at method indent. */
  private static String body(final String signature) throws IOException {
    final String source = TestSources.javaSource("OptionsActivity");
    final int start = source.indexOf(signature);
    assertTrue("not declared: " + signature, start >= 0);
    final int end = source.indexOf("\n  }", start);
    assertTrue("unterminated: " + signature, end > start);
    return source.substring(start, end);
  }

  @Test
  public void eachTabHasItsOwnPaneIndex() {
    for (int i = 0; i < OptionsActivity.tabClasses.length; i++) {
      // A tab listed twice would report the first occurrence for both.
      assertEquals(i, OptionsActivity.paneIndexOf(OptionsActivity.tabClasses[i]));
    }
  }

  @Test
  public void theMenuIsNotAPane() {
    // activityClass is null on the menu.
    assertEquals(-1, OptionsActivity.paneIndexOf(null));
    assertFalse(OptionsActivity.isPaneIndex(-1));
    assertFalse(OptionsActivity.isPaneIndex(OptionsActivity.tabClasses.length));
  }

  @Test
  public void restoreReopensTheTabTheUserWasOn() {
    for (int i = 0; i < OptionsActivity.tabClasses.length; i++) {
      assertEquals(i, OptionsActivity.paneToRestore(true, i));
    }
  }

  @Test
  public void aBundleWithoutAMapRestoresNothing() {
    // The map is what the pane's fields are loaded from, so a pane alone must not reopen a tab.
    assertEquals(-1, OptionsActivity.paneToRestore(false, 0));
    assertEquals(-1, OptionsActivity.paneToRestore(false,
        OptionsActivity.tabClasses.length - 1));
  }

  @Test
  public void aStalePaneIndexLeavesTheMenu() {
    assertEquals(-1, OptionsActivity.paneToRestore(true, -1));
    assertEquals(-1, OptionsActivity.paneToRestore(true,
        OptionsActivity.tabClasses.length));
    assertEquals(-1, OptionsActivity.paneToRestore(true, Integer.MAX_VALUE));
    assertEquals(-1, OptionsActivity.paneToRestore(true, Integer.MIN_VALUE));
  }

  @Test
  public void bothLifecycleHooksAreOverridden() throws IOException {
    assertTrue("no onSaveInstanceState",
        body("protected void onSaveInstanceState(").contains(
            "saveInstanceState(outState)"));
    assertTrue("no onRestoreInstanceState",
        body("protected void onRestoreInstanceState(").contains(
            "restoreInstanceState(savedInstanceState)"));
  }

  @Test
  public void theVisibleTabIsFlushedBeforeTheMapIsSerialized() throws IOException {
    // A tab's widgets only reach the map when that tab is left.
    final String saved = body("protected void saveInstanceState(");
    assertTrue("no flush", saved.contains("saveIfNeeded()"));
    assertTrue("flushed after serializing",
        saved.indexOf("saveIfNeeded()") < saved.indexOf("mapper.serialize()"));
  }

  @Test
  public void theRestoredMapIsInPlaceBeforeTheTabIsReopened() throws IOException {
    // setPane() loads the tab's fields from the map, so a reopen first would load the stale one.
    final String restored = body("protected void restoreInstanceState(");
    assertTrue("map not restored", restored.contains("mapper.unserialize("));
    assertTrue("tab reopened before the map was restored",
        restored.indexOf("mapper.unserialize(") < restored.indexOf("setPane("));
  }
}
