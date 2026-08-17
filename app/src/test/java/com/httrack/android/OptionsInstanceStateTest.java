package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * OptionsActivity's instance state (issue #129): without it, any recreation reverted every
 * unsaved option edit. Both directions run here, over the seams the activity itself uses.
 */
public class OptionsInstanceStateTest {
  private static final int VERSION = 95;

  /** Stands in for the serialized map; only its identity is looked at. */
  private static class FakeMap implements Parcelable {
    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
    }
  }

  private static class FakeStore implements OptionsInstanceState.Store {
    private final Map<String, Object> values = new HashMap<String, Object>();

    @Override
    public void putInt(final String key, final int value) {
      values.put(key, value);
    }

    // Like Bundle, answer the default when the slot holds another type: that is how a getter
    // reaching for the wrong key stays silent.
    @Override
    public int getInt(final String key, final int defaultValue) {
      final Object value = values.get(key);
      return value instanceof Integer ? Integer.class.cast(value)
          : defaultValue;
    }

    @Override
    public void putParcelable(final String key, final Parcelable value) {
      values.put(key, value);
    }

    @Override
    public Parcelable getParcelable(final String key) {
      final Object value = values.get(key);
      return value instanceof Parcelable ? Parcelable.class.cast(value) : null;
    }
  }

  /** Screen recording what was asked of it, in order. */
  private static class FakeScreen implements OptionsInstanceState.Screen {
    private final List<String> calls = new ArrayList<String>();
    private Parcelable map;
    private int pane;
    private boolean restored;

    FakeScreen(final Parcelable map, final int pane) {
      this.map = map;
      this.pane = pane;
    }

    @Override
    public void flushVisibleTab() {
      calls.add("flush");
    }

    @Override
    public Parcelable serializeMap() {
      calls.add("serialize");
      return map;
    }

    @Override
    public void unserializeMap(final Parcelable map) {
      calls.add("unserialize");
      this.map = map;
    }

    @Override
    public int paneCount() {
      return OptionsActivity.tabClasses.length;
    }

    @Override
    public int visiblePane() {
      calls.add("visiblePane");
      return pane;
    }

    @Override
    public void openPane(final int index) {
      calls.add("openPane");
      this.pane = index;
    }
  }

  /** The fresh screen SAVED's state lands in, restoring as build VERSION. */
  private static FakeScreen restore(final FakeScreen saved, final int version) {
    final FakeStore store = new FakeStore();
    OptionsInstanceState.save(saved, store, VERSION);
    final FakeScreen restored = new FakeScreen(null,
        OptionsInstanceState.NO_PANE);
    restored.restored = OptionsInstanceState.restore(restored, store, version);
    return restored;
  }

  /** The screen a bundle naming PANE restores onto. */
  private static FakeScreen restorePane(final int pane) {
    final FakeStore store = new FakeStore();
    store.putInt(HTTrackActivity.VERSION_CODE_NAME, VERSION);
    store.putParcelable(HTTrackActivity.MAP_NAME, new FakeMap());
    store.putInt(HTTrackActivity.PANE_NAME, pane);
    final FakeScreen restored = new FakeScreen(null,
        OptionsInstanceState.NO_PANE);
    restored.restored = OptionsInstanceState.restore(restored, store, VERSION);
    return restored;
  }

  @Test
  public void theMapAndTheOpenTabSurviveTheRoundTrip() {
    for (int i = 0; i < OptionsActivity.tabClasses.length; i++) {
      final Parcelable map = new FakeMap();
      final FakeScreen restored = restore(new FakeScreen(map, i), VERSION);
      assertTrue(restored.restored);
      assertSame(map, restored.map);
      assertEquals(i, restored.pane);
    }
  }

  @Test
  public void theMenuComesBackAsTheMenu() {
    // No tab was open, so the map is restored but nothing is re-opened over the menu.
    final Parcelable map = new FakeMap();
    final FakeScreen restored = restore(
        new FakeScreen(map, OptionsInstanceState.NO_PANE), VERSION);
    assertSame(map, restored.map);
    assertFalse(restored.calls.contains("openPane"));
  }

  @Test
  public void aBundleFromAnotherBuildRestoresNothing() {
    // R.id values are renumbered by any layout change, so that build's keys name other fields.
    final FakeScreen restored = restore(new FakeScreen(new FakeMap(), 0),
        VERSION + 1);
    assertFalse(restored.restored);
    assertNull(restored.map);
    assertEquals(OptionsInstanceState.NO_PANE, restored.pane);
  }

  @Test
  public void aBundleWithoutAMapRestoresNothing() {
    final FakeStore store = new FakeStore();
    store.putInt(HTTrackActivity.VERSION_CODE_NAME, VERSION);
    store.putInt(HTTrackActivity.PANE_NAME, 0);
    final FakeScreen restored = new FakeScreen(null,
        OptionsInstanceState.NO_PANE);
    assertFalse(OptionsInstanceState.restore(restored, store, VERSION));
    assertFalse(restored.calls.contains("openPane"));
  }

  @Test
  public void theVisibleTabIsFlushedBeforeTheMapIsRead() {
    // A tab's widgets only reach the map when that tab is left.
    final FakeScreen saved = new FakeScreen(new FakeMap(), 0);
    OptionsInstanceState.save(saved, new FakeStore(), VERSION);
    assertTrue(saved.calls.indexOf("flush") < saved.calls.indexOf("serialize"));
  }

  @Test
  public void theMapIsRestoredBeforeTheTabIsReopened() {
    // Opening a tab loads its fields from the map, so a reopen first would load the stale one.
    final FakeScreen restored = restore(new FakeScreen(new FakeMap(), 2),
        VERSION);
    assertTrue(restored.calls.indexOf("unserialize") < restored.calls
        .indexOf("openPane"));
  }

  @Test
  public void everyTabHasItsOwnPaneIndex() {
    // A duplicate would make two menu entries share one index, and reopen the wrong tab.
    for (int i = 0; i < OptionsActivity.tabClasses.length; i++) {
      assertEquals(OptionsActivity.tabClasses[i].getName(), i,
          OptionsActivity.paneIndexOf(OptionsActivity.tabClasses[i]));
    }
  }

  @Test
  public void whatIsNotATabIsNotAPane() {
    // activityClass is null on the menu, and never a class outside the list.
    assertEquals(OptionsInstanceState.NO_PANE,
        OptionsActivity.paneIndexOf(null));
    assertEquals(OptionsInstanceState.NO_PANE,
        OptionsActivity.paneIndexOf(OptionsActivity.class));
  }

  @Test
  public void aPaneIndexNamingNoTabLeavesTheMenu() {
    // A rebuild at the same versionCode passes the version stamp, so the index reaches setPane.
    for (final int pane : new int[] { OptionsActivity.tabClasses.length, -2 }) {
      final FakeScreen restored = restorePane(pane);
      assertTrue("bundle refused for pane " + pane, restored.restored);
      assertFalse("reopened pane " + pane,
          restored.calls.contains("openPane"));
    }
  }

  @Test
  public void theSavedIndexIsTheVisibleTabs() throws IOException {
    // The fake screen answers visiblePane() itself, so only the source pins the real one.
    assertTrue("visiblePane() does not use paneIndexOf",
        body("public int visiblePane(").contains("paneIndexOf(activityClass)"));
  }

  @Test
  public void bothLifecycleHooksAreOverridden() throws IOException {
    // The Activity overrides are the one thing above no seam reaches.
    assertTrue("no onSaveInstanceState",
        body("protected void onSaveInstanceState(").contains(
            "OptionsInstanceState.save(this,"));
    assertTrue("no onRestoreInstanceState",
        body("protected void onRestoreInstanceState(").contains(
            "OptionsInstanceState.restore(this,"));
  }

  /** Body of an OptionsActivity method, up to the closing brace at method indent. */
  private static String body(final String signature) throws IOException {
    final String source = TestSources.javaSource("OptionsActivity");
    final int start = source.indexOf(signature);
    assertTrue("not declared: " + signature, start >= 0);
    final int end = source.indexOf("\n  }", start);
    assertTrue("unterminated: " + signature, end > start);
    return source.substring(start, end);
  }
}
