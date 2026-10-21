package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

  /** A login ends deeper than it began, and that page is the one the user came to copy. */
  @Test
  public void theLandedAddressJoinsTheField() {
    assertEquals("https://a.example\nhttps://a.example/members/",
        BrowserLoginPolicy.withLandedUrl("https://a.example", "https://a.example/members/"));
    // An empty field takes it alone, with no leading blank line.
    assertEquals("https://a.example/members/",
        BrowserLoginPolicy.withLandedUrl("", "https://a.example/members/"));
    assertEquals("https://a.example/members/",
        BrowserLoginPolicy.withLandedUrl(null, "https://a.example/members/"));
  }

  @Test
  public void anAddressAlreadyInTheFieldIsNotRepeated() {
    final String landed = "https://a.example/members/";
    assertEquals(landed, BrowserLoginPolicy.withLandedUrl(landed, landed));
    /* The field holds what the user typed, and startUrl raised it to https before opening it,
       so the landed address is compared against the same raising. */
    assertEquals("a.example/members/",
        BrowserLoginPolicy.withLandedUrl("a.example/members/", landed));
    assertEquals("http://a.example/members/",
        BrowserLoginPolicy.withLandedUrl("http://a.example/members/", landed));
  }

  /** The field is the crawl's own input, so nothing the login reports may be put in unchecked. */
  @Test
  public void onlyAnHttpsAddressReachesTheField() {
    assertEquals("https://a.example", BrowserLoginPolicy.withLandedUrl("https://a.example", null));
    // A WebView can end on these, and none of them names a host the crawl may start from.
    assertEquals("https://a.example",
        BrowserLoginPolicy.withLandedUrl("https://a.example", "about:blank"));
    assertEquals("https://a.example",
        BrowserLoginPolicy.withLandedUrl("https://a.example", "file:///etc/passwd"));
    assertEquals("https://a.example",
        BrowserLoginPolicy.withLandedUrl("https://a.example", "http://a.example/"));
    assertEquals("https://a.example", BrowserLoginPolicy.withLandedUrl("https://a.example", ""));
  }
}
