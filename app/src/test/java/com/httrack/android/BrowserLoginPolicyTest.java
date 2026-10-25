package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/** Which address the login window opens, and what the hand-off changes about the crawl. */
public class BrowserLoginPolicyTest {
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

  /** The name the bundle written for these tests would carry. */
  private static final String NAME = "a.example";

  private static final String SUFFIX = BrowserLoginBundle.SUFFIX + NAME;

  /** A login ends deeper than it began, and that page is the one the user came to copy. */
  @Test
  public void theLandedAddressJoinsTheFieldNamingItsBundle() {
    assertEquals("https://a.example\nhttps://a.example/members/" + SUFFIX,
        BrowserLoginPolicy.withLandedUrl("https://a.example", "https://a.example/members/", NAME));
    // Either direction, because a login moves between a site and its subdomains both ways.
    assertEquals("https://a.example\nhttps://login.a.example/home" + SUFFIX, BrowserLoginPolicy
        .withLandedUrl("https://a.example", "https://login.a.example/home", NAME));
    assertEquals("https://www.a.example\nhttps://a.example/members/" + SUFFIX, BrowserLoginPolicy
        .withLandedUrl("https://www.a.example", "https://a.example/members/", NAME));
    // The suffix is what makes the engine read the bundle, so it reaches an empty field too.
    assertEquals("https://a.example/members/" + SUFFIX,
        BrowserLoginPolicy.withLandedUrl("", "https://a.example/members/", NAME));
    assertEquals("https://a.example/members/" + SUFFIX,
        BrowserLoginPolicy.withLandedUrl(null, "https://a.example/members/", NAME));
  }

  /** The engine cuts at the FIRST suffix, so a second one would leave a '#' in the name. */
  @Test
  public void anAddressNamingABundleIsNotNamedTwice() {
    final String named = "https://a.example/members/" + SUFFIX;
    assertEquals("https://a.example\n" + named,
        BrowserLoginPolicy.withLandedUrl("https://a.example", named, NAME));
    assertEquals(named, BrowserLoginPolicy.withLandedUrl(named, named, NAME));
  }

  /** The field is the user's own text, so the answer must not disturb what is already in it. */
  @Test
  public void aFieldOfSeveralAddressesSurvivesEitherAnswer() {
    final String many = "https://a.example/one\n-foo\nhttps://b.example/two";
    // Appended last, the rest in order, including a token urlTokens would reject.
    assertEquals(many + "\nhttps://b.example/deep" + SUFFIX,
        BrowserLoginPolicy.withLandedUrl(many, "https://b.example/deep", NAME));
    // Refused: byte for byte what went in.
    assertEquals(many, BrowserLoginPolicy.withLandedUrl(many, "https://u:p@a.example/x", NAME));
  }

  /**
   * A login often ends on an identity provider. That address is a second crawl root with a live
   * session, so the user is told, and it is still filled in rather than left to be retyped.
   */
  @Test
  public void anAddressOnAnotherSiteIsAddedAndFlagged() {
    final String field = "https://a.example";
    final String idp = "https://accounts.idp.example/signin/oauth";
    assertEquals(field + "\n" + idp + SUFFIX,
        BrowserLoginPolicy.withLandedUrl(field, idp, NAME));
    assertTrue(BrowserLoginPolicy.landedElsewhere(field, idp));
    // A suffix that is not a label boundary is another site, whatever it looks like.
    assertTrue(BrowserLoginPolicy.landedElsewhere(field, "https://nota.example/x"));
    // The usual login, which must not warn.
    assertFalse(BrowserLoginPolicy.landedElsewhere(field, "https://a.example/members/"));
    assertFalse(BrowserLoginPolicy.landedElsewhere(field, "https://login.a.example/home"));
    assertFalse(BrowserLoginPolicy.landedElsewhere("https://www.a.example", "https://a.example/"));
    // Nothing to warn about when there is no address to add.
    assertFalse(BrowserLoginPolicy.landedElsewhere(field, "https://u:p@a.example/x"));
    assertFalse(BrowserLoginPolicy.landedElsewhere(field, "about:blank"));
  }

  /** The address field is saved into the project profile, which lives in shared storage. */
  @Test
  public void theQueryStaysAndTheFragmentGoes() {
    final String field = "https://a.example";
    // The query can be the page the user asked for, and both front ends keep it.
    assertEquals(field + "\nhttps://a.example/cb?code=AQAB&state=xyz" + SUFFIX, BrowserLoginPolicy
        .withLandedUrl(field, "https://a.example/cb?code=AQAB&state=xyz", NAME));
    // A sign-in returns its token in the fragment, no server ever reads it, and our name goes there.
    assertEquals(field + "\nhttps://a.example/cb" + SUFFIX,
        BrowserLoginPolicy.withLandedUrl(field, "https://a.example/cb#access_token=eyJ.P.S", NAME));
    assertEquals(field + "\nhttps://a.example/cb?a=1" + SUFFIX,
        BrowserLoginPolicy.withLandedUrl(field, "https://a.example/cb?a=1#b=2", NAME));
    // A '#' before the '?' ends the address there, because the fragment runs to the end.
    assertEquals(field + "\nhttps://a.example/cb" + SUFFIX,
        BrowserLoginPolicy.withLandedUrl(field, "https://a.example/cb#b=2?a=1", NAME));
  }

  /** Userinfo is dropped rather than stripped: the engine would authenticate with it. */
  @Test
  public void noPasswordRidesInOnTheLandedAddress() {
    final String field = "https://a.example";
    assertEquals(field,
        BrowserLoginPolicy.withLandedUrl(field, "https://user:s3cr3t@a.example/home", NAME));
    assertEquals(field, BrowserLoginPolicy.withLandedUrl(field, "https://:pw@a.example/home", NAME));
  }

  @Test
  public void anAddressAlreadyInTheFieldIsNotRepeated() {
    final String named = "https://a.example/members/" + SUFFIX;
    assertEquals(named, BrowserLoginPolicy.withLandedUrl(named, "https://a.example/members/", NAME));
  }

  /** The field is the crawl's own input, so nothing the login reports may be put in unchecked. */
  @Test
  public void onlyAnHttpsAddressReachesTheField() {
    final String field = "https://a.example";
    assertEquals(field, BrowserLoginPolicy.withLandedUrl(field, null, NAME));
    // A WebView can end on these, and none of them names a host the crawl may start from.
    assertEquals(field, BrowserLoginPolicy.withLandedUrl(field, "about:blank", NAME));
    assertEquals(field, BrowserLoginPolicy.withLandedUrl(field, "file:///etc/passwd", NAME));
    assertEquals(field, BrowserLoginPolicy.withLandedUrl(field, "http://a.example/", NAME));
    assertEquals(field, BrowserLoginPolicy.withLandedUrl(field, "", NAME));
  }

  /**
   * The hosts the crawl starts from are asked for cookies too: getCookie answers for any host,
   * and one that shares a parent domain with a visited host inherits its cookies.
   */
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
