package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** BrowseActivity keeps local content in the WebView and pushes remote links to an external browser. */
public class BrowseSchemeTest {
  @Test
  public void keepsLocalSchemesInApp() {
    assertTrue(BrowseActivity.isLocalScheme("file"));
    assertTrue(BrowseActivity.isLocalScheme("data"));
    assertTrue(BrowseActivity.isLocalScheme("about"));
  }

  @Test
  public void sendsRemoteSchemesOut() {
    assertFalse(BrowseActivity.isLocalScheme("http"));
    assertFalse(BrowseActivity.isLocalScheme("https"));
    assertFalse(BrowseActivity.isLocalScheme("ftp"));
    assertFalse(BrowseActivity.isLocalScheme("content"));
    assertFalse(BrowseActivity.isLocalScheme(null));
  }
}
