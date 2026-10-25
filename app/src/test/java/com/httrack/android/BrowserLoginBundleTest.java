package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** What the login writes for the engine to read. */
public class BrowserLoginBundleTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private static final List<String> NO_SCOPE = Collections.<String> emptyList();

  private static final String RECORDS = "a.example\tTRUE\t/\tFALSE\t0\tsess\tx\n";

  @Test
  public void theHeaderLeadsAndTheRecordsFollowTheCookiesLine() {
    final String text = BrowserLoginBundle.text("Mozilla/5.0", NO_SCOPE, RECORDS);
    assertEquals(BrowserLoginBundle.HEADER + "\nua Mozilla/5.0\ncookies\n" + RECORDS, text);
    // Every line after "cookies" is a record, so nothing of ours may follow them.
    assertTrue(text, text.endsWith(RECORDS));
  }

  /** The cookies are worth more than the identity, so a line the engine refuses is dropped. */
  @Test
  public void anIdentityTheEngineWouldRefuseCostsOnlyItself() {
    assertEquals(BrowserLoginBundle.HEADER + "\ncookies\n" + RECORDS,
        BrowserLoginBundle.text("Mozilla/5.0\tcurl", NO_SCOPE, RECORDS));
    // A control byte splits the line, or stops the load where the engine reads one.
    assertFalse(BrowserLoginBundle.fits("ua", "Mozilla\n5.0"));
    assertFalse(BrowserLoginBundle.fits("ua", "Mozilla\u00015.0"));
    assertFalse(BrowserLoginBundle.fits("ua", ""));
    assertFalse(BrowserLoginBundle.fits("ua", null));
    assertTrue(BrowserLoginBundle.fits("ua", "Mozilla/5.0 (Linux; Android 16) Chrome/133 wv"));
  }

  /** A line the engine cannot read stops the run, and the key counts towards its length. */
  @Test
  public void aLineAtTheEnginesCapIsRefused() {
    final StringBuilder agent = new StringBuilder();
    while (agent.length() < BrowserLoginBundle.MAX_LINE) {
      agent.append('a');
    }
    /* A line of MAX_LINE bytes is already too long: the engine reads into a buffer of
       MAX_LINE + 1 and refuses len >= MAX_LINE, so the longest it takes is one byte less. */
    final String longest =
        agent.substring(0, BrowserLoginBundle.MAX_LINE - "ua ".length() - 1);
    assertTrue(BrowserLoginBundle.fits("ua", longest));
    assertFalse(BrowserLoginBundle.fits("ua", longest + "a"));
    // Bytes, not characters: the engine measures every limit with strlen().
    assertFalse(BrowserLoginBundle.fits("ua", longest.substring(1) + "é"));
  }

  /**
   * A scope line replaces the engine's default scope rather than adding to it, so the usual
   * login of one site writes none and lets the start URL decide.
   */
  @Test
  public void theScopeIsWrittenOnlyWhenTheDefaultWouldBeWrong() {
    assertEquals(
        BrowserLoginBundle.HEADER + "\nua ua\nscope a.example\nscope b.example\ncookies\n",
        BrowserLoginBundle.text("ua", Arrays.asList("a.example", "b.example"), ""));
    assertEquals(BrowserLoginBundle.HEADER + "\nua ua\ncookies\n",
        BrowserLoginBundle.text("ua", NO_SCOPE, ""));
    // A domain the engine would refuse is left out rather than stopping the whole run.
    assertEquals(BrowserLoginBundle.HEADER + "\nua ua\nscope a.example\ncookies\n",
        BrowserLoginBundle.text("ua", Arrays.asList("a.example", "b example\n"), ""));
  }

  /**
   * The engine refuses to run at all on a bundle it cannot read, so an address naming one that
   * is gone loses the name. Forget deletes the files and leaves the address field alone, and
   * the user can clear the app's storage from outside the app at any time.
   */
  @Test
  public void anAddressNamingABundleThatIsGoneLosesTheName() throws IOException {
    final File dir = tmp.newFolder("logins", "P");
    assertTrue(new File(dir, "a.example.hts").createNewFile());
    final String named = "https://a.example/#httrack-bundle=a.example";
    assertEquals(named, BrowserLoginBundle.withoutMissingBundle(named, dir));
    final String gone = "https://a.example/#httrack-bundle=b.example";
    assertEquals("https://a.example/", BrowserLoginBundle.withoutMissingBundle(gone, dir));
    // A name that could leave the folder is treated as missing rather than resolved.
    assertEquals("https://a.example/", BrowserLoginBundle
        .withoutMissingBundle("https://a.example/#httrack-bundle=../../x", dir));
    assertEquals("https://a.example/", BrowserLoginBundle.withoutMissingBundle(named, null));
    // Everything else passes through, including the tokens that are not addresses at all.
    assertEquals("https://a.example/", BrowserLoginBundle
        .withoutMissingBundle("https://a.example/", dir));
    assertEquals("-*logout*", BrowserLoginBundle.withoutMissingBundle("-*logout*", dir));
    assertNull(BrowserLoginBundle.withoutMissingBundle(null, dir));
  }

  /** The engine cuts at the first suffix, so a second one would leave a '#' in the name. */
  @Test
  public void anAddressNamesOneBundleAtMost() {
    assertEquals("https://a.example/#httrack-bundle=a.example",
        BrowserLoginBundle.startUrl("https://a.example/", "a.example"));
    final String named = "https://a.example/#httrack-bundle=other";
    assertEquals(named, BrowserLoginBundle.startUrl(named, "a.example"));
    assertEquals("https://a.example/", BrowserLoginBundle.startUrl("https://a.example/", null));
    assertNull(BrowserLoginBundle.startUrl(null, "a.example"));
  }
}
