package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards HTTrackActivity.shouldReloadProfile, the decision that loads a selected project's
 * saved URL/options. Regression: Select project -> Back -> Next -> Next left the URL blank
 * because the guard trusted the map name key, which Back had already written.
 */
public class ProjectReloadTest {
  @Test
  public void reloadsWhenNothingLoadedYet() {
    // Fresh selection, no profile loaded: must load, even though the map name key already
    // holds the name (written by Back navigation). This is the reported bug.
    assertTrue(HTTrackActivity.shouldReloadProfile("Foo", null, false));
  }

  @Test
  public void reloadsWhenSelectionChanged() {
    assertTrue(HTTrackActivity.shouldReloadProfile("Bar", "Foo", false));
  }

  @Test
  public void reloadsWhenDirtyAfterRestore() {
    assertTrue(HTTrackActivity.shouldReloadProfile("Foo", "Foo", true));
  }

  @Test
  public void skipsWhenSameProfileAlreadyLoaded() {
    // The one case that must NOT reload: reloading here would wipe URL/options the user
    // typed on the setup pane then navigated back past.
    assertFalse(HTTrackActivity.shouldReloadProfile("Foo", "Foo", false));
  }
}
