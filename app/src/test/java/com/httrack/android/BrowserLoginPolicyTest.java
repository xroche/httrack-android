package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

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

  /** Host-only cookies reach one host each, so the capture has to cover every start host. */
  @Test
  public void everyAddressTheCrawlStartsFromIsAHostToCapture() {
    assertEquals(Arrays.asList("a.example", "b.example"),
        list(BrowserLoginPolicy.crawlHosts("https://a.example/x http://b.example/")));
    // A bare host and a duplicate, because the field is free text the user typed.
    assertEquals(Collections.singletonList("a.example"),
        list(BrowserLoginPolicy.crawlHosts("a.example https://a.example/deep")));
    // Lowercased, because the engine folds a cookie domain before comparing it.
    assertEquals(Collections.singletonList("a.example"),
        list(BrowserLoginPolicy.crawlHosts("https://A.Example/")));
    assertTrue(BrowserLoginPolicy.crawlHosts("").isEmpty());
    assertTrue(BrowserLoginPolicy.crawlHosts(null).isEmpty());
    // urlTokens drops an option, so a tampered profile cannot add a host through this field.
    assertTrue(BrowserLoginPolicy.crawlHosts("-O1 /tmp/x").isEmpty());
  }

  @Test
  public void anExpiredCookieNamesItsDomainOnlyWhenItHasOne() {
    assertEquals("sid=; Max-Age=0; Path=/", BrowserLoginPolicy.expiredCookie("sid", null));
    assertEquals("sid=; Max-Age=0; Path=/; Domain=example.com",
        BrowserLoginPolicy.expiredCookie("sid", "example.com"));
  }

  /** A session cookie is often set on a parent, so dropping it from the host alone leaves it. */
  @Test
  public void theParentsStopBeforeTheLastTwoLabels() {
    assertEquals(Arrays.asList("b.example.com", "example.com"),
        BrowserLoginPolicy.parentDomains("a.b.example.com"));
    assertEquals(Collections.singletonList("example.com"),
        BrowserLoginPolicy.parentDomains("www.example.com"));
    // Two labels are already the shortest a cookie may name, so there is no parent to try.
    assertTrue(BrowserLoginPolicy.parentDomains("example.com").isEmpty());
    assertTrue(BrowserLoginPolicy.parentDomains("localhost").isEmpty());
    assertTrue(BrowserLoginPolicy.parentDomains(null).isEmpty());
  }

  private static java.util.List<String> list(final java.util.Set<String> hosts) {
    return new java.util.ArrayList<String>(hosts);
  }
}
