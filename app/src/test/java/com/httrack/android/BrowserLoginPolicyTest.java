package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Which address the login window opens, and what the hand-off changes about the crawl. */
public class BrowserLoginPolicyTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void aBareHostGainsTheSecureScheme() {
    assertEquals("https://www.example.com", BrowserLoginPolicy.startUrl("www.example.com"));
  }

  /** The app declares no cleartext permission, so a WebView given http would stay blank. */
  @Test
  public void httpIsRaisedToHttps() {
    assertEquals("https://example.com/in", BrowserLoginPolicy.startUrl("http://example.com/in"));
    assertEquals("https://example.com/in", BrowserLoginPolicy.startUrl("HTTP://example.com/in"));
  }

  @Test
  public void anHttpsAddressIsLeftAlone() {
    assertEquals("https://example.com/a?b=c",
        BrowserLoginPolicy.startUrl("https://example.com/a?b=c"));
  }

  @Test
  public void theFirstAddressOfTheFieldWins() {
    assertEquals("https://first.example",
        BrowserLoginPolicy.startUrl("first.example\nsecond.example"));
    assertEquals("https://first.example",
        BrowserLoginPolicy.startUrl("  first.example   second.example  "));
  }

  /** A tampered profile puts engine options in that field, and they are not addresses. */
  @Test
  public void skipsWhatIsNotAnAddress() {
    assertEquals("https://real.example", BrowserLoginPolicy.startUrl("-O /evil real.example"));
    assertNull(BrowserLoginPolicy.startUrl("ftp://files.example"));
    assertNull(BrowserLoginPolicy.startUrl("file:///etc/passwd"));
    assertNull(BrowserLoginPolicy.startUrl("javascript:alert(1)"));
    assertNull(BrowserLoginPolicy.startUrl("content://media/external"));
  }

  @Test
  public void anEmptyFieldOpensNothing() {
    assertNull(BrowserLoginPolicy.startUrl(null));
    assertNull(BrowserLoginPolicy.startUrl(""));
    assertNull(BrowserLoginPolicy.startUrl("   "));
  }

  /** The engine compares a cookie domain without case, and our file states one host per line. */
  @Test
  public void hostIsLowercasedAndStrippedOfTheRest() {
    assertEquals("www.example.com", BrowserLoginPolicy.hostOf("https://WWW.Example.COM/x"));
    assertEquals("example.com", BrowserLoginPolicy.hostOf("https://example.com:8443/x?y=z"));
    assertEquals("example.com", BrowserLoginPolicy.hostOf("https://user@example.com/x"));
  }

  @Test
  public void onlyHttpsNamesAHost() {
    assertNull(BrowserLoginPolicy.hostOf("http://example.com/"));
    assertNull(BrowserLoginPolicy.hostOf("file:///data/data/com.httrack.android/"));
    assertNull(BrowserLoginPolicy.hostOf("intent://scan#Intent;scheme=zxing;end"));
    assertNull(BrowserLoginPolicy.hostOf("https:///nohost"));
    assertNull(BrowserLoginPolicy.hostOf("not a url at all"));
    assertNull(BrowserLoginPolicy.hostOf(null));
  }

  @Test
  public void connectionsOnlyEverGoDown() {
    assertEquals("2", BrowserLoginPolicy.sockets(""));
    assertEquals("2", BrowserLoginPolicy.sockets(null));
    assertEquals("2", BrowserLoginPolicy.sockets("8"));
    assertEquals("2", BrowserLoginPolicy.sockets("2"));
    assertEquals("1", BrowserLoginPolicy.sockets("1"));
    assertEquals("1", BrowserLoginPolicy.sockets(" 1 "));
    // Neither a zero nor a word is a count the engine would honour.
    assertEquals("2", BrowserLoginPolicy.sockets("0"));
    assertEquals("2", BrowserLoginPolicy.sockets("00"));
    assertEquals("2", BrowserLoginPolicy.sockets("lots"));
    // A value too big for an int must not throw on the way to the ceiling.
    assertEquals("2", BrowserLoginPolicy.sockets("999999999999999999999"));
    /* setText() can put an Arabic-Indic digit in the field. Integer.parseInt reads it as two,
       but the option mapper only emits -c for ASCII digits, so echoing it back would drop -c
       and leave the crawl on the engine's own higher default. */
    assertEquals("2", BrowserLoginPolicy.sockets("\u0662"));
    // The value a project holds is handed on in the form the mapper accepts.
    assertEquals("1", BrowserLoginPolicy.sockets("01"));
  }

  @Test
  public void theLogoutFilterIsAppendedLastSoItWins() {
    assertEquals("-*logout*", BrowserLoginPolicy.rules(""));
    assertEquals("-*logout*", BrowserLoginPolicy.rules(null));
    final String kept = "+*.png -ad.example/*";
    assertEquals(kept + " -*logout*", BrowserLoginPolicy.rules(kept));
    assertTrue(BrowserLoginPolicy.rules(kept).endsWith(BrowserLoginPolicy.LOGOUT_FILTER));
  }

  @Test
  public void anExistingLogoutRuleIsLeftAsTheUserWroteIt() {
    assertEquals("-*/logout", BrowserLoginPolicy.rules("-*/logout"));
    assertEquals("+*/Logout", BrowserLoginPolicy.rules("+*/Logout"));
    // Only the word itself counts, so a rule about another name still gets the filter.
    assertEquals("-*signout* -*logout*", BrowserLoginPolicy.rules("-*signout*"));
  }

  /** The start URL the engine hands back, which already names the bundle it wrote. */
  private static final String WRITTEN = "https://a.example/members/" + BrowserLoginPolicy.BUNDLE_SUFFIX + "a.example";

  /** A login ends deeper than it began, and that page is the one the user came to copy. */
  @Test
  public void theEnginesStartUrlJoinsTheField() {
    assertEquals("https://a.example\n" + WRITTEN,
        BrowserLoginPolicy.withBundleUrl("https://a.example", WRITTEN));
    // The suffix is what makes the engine read the bundle, so it reaches an empty field too.
    assertEquals(WRITTEN, BrowserLoginPolicy.withBundleUrl("", WRITTEN));
    assertEquals(WRITTEN, BrowserLoginPolicy.withBundleUrl(null, WRITTEN));
    // Nothing to add when the engine refused the login.
    assertEquals("https://a.example", BrowserLoginPolicy.withBundleUrl("https://a.example", null));
    assertEquals("https://a.example", BrowserLoginPolicy.withBundleUrl("https://a.example", ""));
  }

  /** The field is the user's own text, so the answer must not disturb what is already in it. */
  @Test
  public void aFieldOfSeveralAddressesKeepsItsOrderAndItsOddTokens() {
    final String many = "https://a.example/one\n-foo\nhttps://b.example/two";
    assertEquals(many + "\n" + WRITTEN, BrowserLoginPolicy.withBundleUrl(many, WRITTEN));
  }

  /** A second login on the same page must not leave the same start URL twice. */
  @Test
  public void theSameStartUrlIsNotAddedTwice() {
    assertEquals(WRITTEN, BrowserLoginPolicy.withBundleUrl(WRITTEN, WRITTEN));
    assertEquals("https://a.example\n" + WRITTEN,
        BrowserLoginPolicy.withBundleUrl("https://a.example\n" + WRITTEN, WRITTEN));
  }

  /**
   * Forget deletes the bundles, and a start URL naming one that is gone stops the crawl at
   * startup by design, so the names have to go with the files.
   */
  @Test
  public void forgettingTheLoginTakesEveryBundleNameOutOfTheField() {
    assertEquals("https://a.example/members/",
        BrowserLoginPolicy.withoutBundleNames(WRITTEN));
    // Every line, because a project can name one bundle per site it copies.
    assertEquals("https://a.example/\nhttps://b.example/",
        BrowserLoginPolicy.withoutBundleNames("https://a.example/" + BrowserLoginPolicy.BUNDLE_SUFFIX
            + "a.example\nhttps://b.example/" + BrowserLoginPolicy.BUNDLE_SUFFIX + "b.example"));
    // The rest of the field is the user's text and comes back byte for byte.
    final String none = "https://a.example/one\n-foo\nhttps://b.example/two";
    assertEquals(none, BrowserLoginPolicy.withoutBundleNames(none));
    assertEquals("", BrowserLoginPolicy.withoutBundleNames(null));
    /* The field separates addresses on any whitespace, so only the token goes. Cutting to the
       end of the line ate the address after it. */
    assertEquals("https://a.example/ https://b.example/",
        BrowserLoginPolicy.withoutBundleNames("https://a.example/" + BrowserLoginPolicy.BUNDLE_SUFFIX
            + "a.example https://b.example/"));
    assertEquals("https://a.example/\thttps://b.example/",
        BrowserLoginPolicy.withoutBundleNames("https://a.example/" + BrowserLoginPolicy.BUNDLE_SUFFIX
            + "a.example\thttps://b.example/"));
  }

  /** The name is what the file is called, so the login can delete the bundles it replaces. */
  @Test
  public void theStartUrlSaysWhichBundleItNames() {
    assertEquals("a.example", BrowserLoginPolicy.bundleNameOf(WRITTEN));
    assertNull(BrowserLoginPolicy.bundleNameOf("https://a.example/"));
    assertNull(BrowserLoginPolicy.bundleNameOf(null));
  }

  /**
   * A login often ends on an identity provider. That address is a second crawl root with a live
   * session, so the user is told, and it is still filled in rather than left to be retyped.
   */
  @Test
  public void anAddressOnAnotherSiteIsFlagged() {
    final String field = "https://a.example";
    final String idp = "https://accounts.idp.example/signin/oauth"
        + BrowserLoginPolicy.BUNDLE_SUFFIX + "idp.example";
    assertTrue(BrowserLoginPolicy.landedElsewhere(field, idp));
    // A suffix that is not a label boundary is another site, whatever it looks like.
    assertTrue(BrowserLoginPolicy.landedElsewhere(field,
        "https://nota.example/x" + BrowserLoginPolicy.BUNDLE_SUFFIX + "nota.example"));
    // The usual login, which must not warn.
    assertFalse(BrowserLoginPolicy.landedElsewhere(field, WRITTEN));
    assertFalse(BrowserLoginPolicy.landedElsewhere("https://www.a.example", WRITTEN));
    assertFalse(BrowserLoginPolicy.landedElsewhere("https://login.a.example", WRITTEN));
    /* The field BEFORE the login, so a caller that asked after adding the address would get
       false for every login. The address is in the field here and the answer still stands. */
    assertTrue(BrowserLoginPolicy.landedElsewhere("https://a.example", idp));
    // Nothing to warn about when the engine wrote nothing.
    assertFalse(BrowserLoginPolicy.landedElsewhere(field, null));
    assertFalse(BrowserLoginPolicy.landedElsewhere(field, "about:blank"));
  }

  /**
   * The whole argv decision, which no grep over the activity could hold: -b0 voids a saved
   * login in silence, and without --bundle-dir the engine looks for the bundle beside the
   * mirror, in shared storage.
   */
  @Test
  public void aSavedLoginDecidesTwoThingsAboutTheArgv() throws IOException {
    final File dir = tmp.newFolder("logins", "P");
    final List<String> named = Arrays.asList("-b0", "-c2", WRITTEN);
    final List<String> plain = Arrays.asList("-b0", "-c2", "https://a.example/");

    // A start URL names a bundle, so the unticked cookie box loses.
    assertEquals(Arrays.asList("-c2", WRITTEN, "--bundle-dir", dir.getAbsolutePath()),
        BrowserLoginPolicy.withLogin(named, dir));
    // No bundle named, so the user's own choice stands.
    assertEquals(Arrays.asList("-b0", "-c2", "https://a.example/", "--bundle-dir",
        dir.getAbsolutePath()), BrowserLoginPolicy.withLogin(plain, dir));
    // No folder, so no option: a project that never saved a login has none.
    assertEquals(Arrays.asList("-c2", WRITTEN), BrowserLoginPolicy.withLogin(named, null));
    assertEquals(Arrays.asList("-c2", WRITTEN),
        BrowserLoginPolicy.withLogin(named, new File(dir, "absent")));
    // Nothing else is touched, and the caller's own list is left alone.
    final List<String> given = new ArrayList<String>(named);
    assertEquals(3, BrowserLoginPolicy.withLogin(given, null).size() + 1);
    assertEquals(named, given);
  }

  /** The hosts the crawl starts from, folded into the cookie sweep. */
  @Test
  public void theFieldsOwnHostsAreListedForTheCookieSweep() {
    assertEquals(Arrays.asList("a.example", "b.example"),
        BrowserLoginPolicy.fieldHosts("https://a.example/one\nb.example/two"));
    // Lowercased the way the engine compares a cookie domain, and listed once each.
    assertEquals(Arrays.asList("a.example"),
        BrowserLoginPolicy.fieldHosts("https://A.example/one https://a.example/two"));
    // A token that is not an address names no host, and neither does an empty field.
    assertEquals(Arrays.asList("a.example"),
        BrowserLoginPolicy.fieldHosts("-foo https://a.example/ *.gif"));
    assertTrue(BrowserLoginPolicy.fieldHosts("").isEmpty());
    assertTrue(BrowserLoginPolicy.fieldHosts(null).isEmpty());
  }
}
