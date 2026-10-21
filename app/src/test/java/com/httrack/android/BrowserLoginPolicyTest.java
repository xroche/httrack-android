package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

  /** Chromium keys a cookie on name, domain and path, so each domain it may hold needs one. */
  @Test
  public void everyDomainTheCookieCouldSitOnIsExpired() {
    final List<String> out = BrowserLoginPolicy.expiryHeaders("sid=abc", "www.a.example.com");
    assertEquals(Arrays.asList("sid=; Max-Age=0; Path=/; Secure",
        "sid=; Max-Age=0; Path=/; Secure; Domain=www.a.example.com",
        "sid=; Max-Age=0; Path=/; Secure; Domain=a.example.com",
        "sid=; Max-Age=0; Path=/; Secure; Domain=example.com"), out);
  }

  /** A two-label host has no parent, so its own domain form is the only one that reaches it. */
  @Test
  public void theHostsOwnDomainFormIsAlwaysTried() {
    assertEquals(Arrays.asList("sid=; Max-Age=0; Path=/; Secure",
        "sid=; Max-Age=0; Path=/; Secure; Domain=a.example"),
        BrowserLoginPolicy.expiryHeaders("sid=abc", "a.example"));
  }

  /** Chromium refuses to set a __Host- or __Secure- cookie without Secure, and those carry
      the session on most login sites. */
  @Test
  public void everyValueCarriesSecure() {
    for (final String value : BrowserLoginPolicy.expiryHeaders("__Host-sid=a; __Secure-t=b",
        "a.example")) {
      assertTrue(value, value.contains("; Secure"));
    }
  }

  @Test
  public void nothingToExpireWhenTheHostAnswersWithNothing() {
    assertTrue(BrowserLoginPolicy.expiryHeaders(null, "a.example").isEmpty());
    assertTrue(BrowserLoginPolicy.expiryHeaders("", "a.example").isEmpty());
    assertTrue(BrowserLoginPolicy.expiryHeaders("sid=abc", null).isEmpty());
  }

  /** Only the names we tried count, or an unrelated consent cookie clears every other site. */
  @Test
  public void aSurvivingNameWeTriedMeansStillSignedIn() {
    final Set<String> tried = new LinkedHashSet<String>(Arrays.asList("sid", "csrf"));
    assertTrue(BrowserLoginPolicy.stillSignedIn("sid=abc", tried));
    assertTrue(BrowserLoginPolicy.stillSignedIn("lang=fr; csrf=x", tried));
    assertFalse(BrowserLoginPolicy.stillSignedIn("lang=fr; consent=1", tried));
    assertFalse(BrowserLoginPolicy.stillSignedIn("", tried));
    assertFalse(BrowserLoginPolicy.stillSignedIn(null, tried));
    assertFalse(BrowserLoginPolicy.stillSignedIn("sid=abc", new LinkedHashSet<String>()));
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

}
