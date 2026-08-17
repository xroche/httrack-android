package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * OptionsActivity's instance-state hooks (issue #129): without them, any recreation reverted
 * every unsaved option edit. The Bundle half is read from the source, since neither Bundle nor
 * the activity can be instantiated against the stub android.jar.
 */
public class OptionsInstanceStateTest {
  private static final Pattern BUNDLE_KEY = Pattern
      .compile("\"(com\\.httrack\\.android\\.\\w+)\"");

  /** Body of an OptionsActivity method, up to the closing brace at method indent. */
  private static String body(final String signature) throws IOException {
    final String source = TestSources.javaSource("OptionsActivity");
    final int start = source.indexOf(signature);
    assertTrue("not declared: " + signature, start >= 0);
    final int end = source.indexOf("\n  }", start);
    assertTrue("unterminated: " + signature, end > start);
    return source.substring(start, end);
  }

  private static Set<String> bundleKeys(final String body) {
    final Set<String> keys = new LinkedHashSet<String>();
    final Matcher m = BUNDLE_KEY.matcher(body);
    while (m.find()) {
      keys.add(m.group(1));
    }
    return keys;
  }

  @Test
  public void everyTabRoundTripsThroughItsPaneIndex() {
    for (final Class<?> cls : OptionsActivity.tabClasses) {
      final int index = OptionsActivity.paneIndexOf(cls);
      assertTrue("no pane index: " + cls.getSimpleName(),
          OptionsActivity.isPaneIndex(index));
      assertEquals(cls, OptionsActivity.tabClasses[index]);
    }
  }

  @Test
  public void theMenuIsNotAPane() {
    // activityClass is null on the menu, and an index from another build must not reopen a tab.
    assertEquals(-1, OptionsActivity.paneIndexOf(null));
    assertFalse(OptionsActivity.isPaneIndex(-1));
    assertFalse(OptionsActivity.isPaneIndex(OptionsActivity.tabClasses.length));
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
  public void restoreReloadsTheMapAndTheOpenTab() throws IOException {
    final String restored = body("protected void restoreInstanceState(");
    assertTrue("map not restored", restored.contains("mapper.unserialize("));
    assertTrue("open tab not restored", restored.contains("setPane("));
  }

  @Test
  public void saveAndRestoreAgreeOnEveryBundleKey() throws IOException {
    // A key written but never read loses that state silently; nothing else pairs them up.
    final Set<String> written = bundleKeys(body("protected void saveInstanceState("));
    assertFalse("nothing saved", written.isEmpty());
    assertEquals(written, bundleKeys(body("protected void restoreInstanceState(")));
  }
}
