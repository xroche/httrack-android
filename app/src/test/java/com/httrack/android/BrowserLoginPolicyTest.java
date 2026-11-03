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

  /** The engine hands this back for a login that landed on a host the field never named. */
  private static final String WRITTEN = "https://forum.a.example/" + BrowserLoginPolicy.BUNDLE_SUFFIX + "forum.a.example";

  /** The engine hands this back when the login stayed on the host the field named. */
  private static final String SAME_HOST = "https://a.example/members/" + BrowserLoginPolicy.BUNDLE_SUFFIX + "a.example";

  /** withBundleUrl as the activity calls it, names stripped first and the token read from that. */
  private static String named(final String field, final String bundleUrl) {
    final String stripped = BrowserLoginPolicy.withoutBundleNames(field);

    return BrowserLoginPolicy.withBundleUrl(stripped, bundleUrl,
        BrowserLoginPolicy.openedToken(stripped));
  }

  /**
   * Keeping both gave the crawl a root nobody asked for, and keeping the opened one alone
   * dropped the page the login was for.
   */
  @Test
  public void theLandedAddressReplacesTheOneTheLoginWasOpenedFor() {
    assertEquals(WRITTEN, named("https://a.example/", WRITTEN));
    // The raising startUrl applied before opening the window, so the typed form matches too.
    assertEquals(WRITTEN, named("a.example/", WRITTEN));
    assertEquals(WRITTEN, named("http://a.example/", WRITTEN));
    // A second login replaces the first one's address, rather than adding to it.
    assertEquals(WRITTEN, named(WRITTEN, WRITTEN));
    // A login that goes nowhere names the address it opened, and the field keeps that one.
    assertEquals(SAME_HOST, named("https://a.example/members/", SAME_HOST));
    // A bare token on the host the login stayed on still reaches the userinfo merge.
    assertEquals(SAME_HOST, named("a.example/members/", SAME_HOST));
  }

  /** The other addresses are the user's own text, and only one of them is replaced. */
  @Test
  public void everyOtherAddressIsLeftExactlyAsItWas() {
    /* The login opens the field's FIRST address, so that is the token the engine was given
       and the one that is replaced. The others are not touched. */
    assertEquals(WRITTEN + "\nhttps://b.example/one\nhttps://c.example/two",
        named("https://a.example/\nhttps://b.example/one\nhttps://c.example/two", WRITTEN));
    // Any whitespace separates addresses, and the user's own spacing survives.
    assertEquals(WRITTEN + " \thttps://b.example/one",
        named("https://a.example/ \thttps://b.example/one", WRITTEN));
    // A token that is not an address is not touched, even one urlTokens would refuse.
    assertEquals("-foo " + WRITTEN, named("-foo https://a.example/", WRITTEN));
    /* The same address typed twice is replaced once. Two names for one site would leave the
       engine with two bundles whose cookie values clash, and it then refuses to start the
       crawl. */
    assertEquals(WRITTEN + " https://a.example/",
        named("https://a.example/ https://a.example/", WRITTEN));
  }

  /** The engine is handed no credentials, so the crawl needs the ones the user typed. */
  @Test
  public void theTypedCredentialsSurviveALoginOnTheirOwnHost() {
    final String merged = "https://user:pw@a.example/members/" + BrowserLoginPolicy.BUNDLE_SUFFIX
        + "a.example";

    assertEquals(merged, named("https://user:pw@a.example/", SAME_HOST));
    assertEquals("https://user@a.example/members/" + BrowserLoginPolicy.BUNDLE_SUFFIX
        + "a.example", named("https://user@a.example/", SAME_HOST));
    // The engine trims a trailing host dot, so the two sides still name the same host.
    assertEquals(merged, named("https://user:pw@a.example./", SAME_HOST));
    // The window raised the token to https, and the field still holds the http one.
    assertEquals(merged, named("http://user:pw@a.example/", SAME_HOST));
    // Nothing is invented where the user typed none.
    assertEquals(SAME_HOST, named("https://a.example/", SAME_HOST));
    // An '@' in the path is not userinfo, so nothing moves.
    assertEquals("https://a.example/u@x" + BrowserLoginPolicy.BUNDLE_SUFFIX + "a.example",
        named("https://a.example/u@x",
            "https://a.example/u@x" + BrowserLoginPolicy.BUNDLE_SUFFIX + "a.example"));
  }

  /** A login can end on a sign-on provider that must not receive typed credentials. */
  @Test
  public void theTypedCredentialsDoNotTravelToAnotherHost() {
    assertEquals(WRITTEN, named("https://user:pw@a.example/", WRITTEN));
    assertEquals(WRITTEN, named("https://user@a.example/", WRITTEN));
    // Nor back up to the apex from a subdomain the user typed them for.
    assertEquals(SAME_HOST, named("https://user:pw@forum.a.example/", SAME_HOST));
    // No https address means no host to match, so there is nothing to merge them onto.
    final String cleartext = "http://a.example/x" + BrowserLoginPolicy.BUNDLE_SUFFIX + "a.example";
    assertEquals(cleartext, named("https://user:pw@a.example/", cleartext));
  }

  /**
   * The login keeps the cookies of every host that answered with one, so a crawl can
   * authenticate on a site the login passed through.
   */
  @Test
  public void everyHostThatAnsweredWithACookieIsNamed() {
    assertEquals("a.example,idp.example",
        BrowserLoginPolicy.keptHosts(Arrays.asList("a.example", "idp.example")));
    // The engine's separator is a comma, and nothing else.
    assertEquals("a.example", BrowserLoginPolicy.keptHosts(Arrays.asList("a.example")));
    // The order the hosts were asked in, which puts the login's first host first.
    assertEquals("b.example,a.example",
        BrowserLoginPolicy.keptHosts(Arrays.asList("b.example", "a.example")));
  }

  /** Naming no host asks the engine for the address's own cookies alone, as it did before. */
  @Test
  public void namingNoHostKeepsTheEnginesOlderBehaviour() {
    assertNull(BrowserLoginPolicy.keptHosts(new ArrayList<String>()));
    // A host the cookie query answered for with an empty name carries nothing to keep.
    assertNull(BrowserLoginPolicy.keptHosts(Arrays.asList("", null)));
    assertEquals("a.example", BrowserLoginPolicy.keptHosts(Arrays.asList("", "a.example")));
  }

  /**
   * The landed address leads, because that is the page to copy. The opened one follows, so a
   * landed address the engine refuses still leaves a login to save.
   */
  @Test
  public void theLandedAddressIsOfferedBeforeTheOpenedOne() {
    final String opened = "https://a.example/members/";

    assertEquals(Arrays.asList("https://forum.a.example/x", opened),
        BrowserLoginPolicy.addressesToTry("https://forum.a.example/x", opened));
    // One address, offered once.
    assertEquals(Arrays.asList(opened), BrowserLoginPolicy.addressesToTry(opened, opened));
    // The engine refuses an address holding credentials, so they go before it sees one.
    assertEquals(Arrays.asList("https://a.example/x", opened),
        BrowserLoginPolicy.addressesToTry("https://user:pw@a.example/x", opened));
    /* Only the engine can judge an IPv6 literal, a public suffix or the length cap, which it
       works out from the name. A screen here would answer differently from the engine. */
    assertEquals(Arrays.asList("https://[::1]/x", opened),
        BrowserLoginPolicy.addressesToTry("https://[::1]/x", opened));
  }

  /** An address the window cannot have landed on is not offered, so the opened one stands. */
  @Test
  public void onlyAnHttpsAddressNamingAHostIsOffered() {
    final String opened = "https://a.example/members/";

    // The positive control, because every other row here expects the opened address alone.
    assertEquals(Arrays.asList("https://a.example/x", opened),
        BrowserLoginPolicy.addressesToTry("https://a.example/x", opened));
    // getUrl answers for no page until one commits.
    assertEquals(Arrays.asList(opened), BrowserLoginPolicy.addressesToTry(null, opened));
    assertEquals(Arrays.asList(opened), BrowserLoginPolicy.addressesToTry("about:blank", opened));
    assertEquals(Arrays.asList(opened),
        BrowserLoginPolicy.addressesToTry("data:text/html,x", opened));
    assertEquals(Arrays.asList(opened),
        BrowserLoginPolicy.addressesToTry("http://a.example/x", opened));
    // Nothing to offer stops the write, which is what the window reports as a refusal.
    assertTrue(BrowserLoginPolicy.addressesToTry(null, null).isEmpty());
  }

  /** A field of nothing but whitespace holds no address, so the name takes the whole field. */
  @Test
  public void aFieldOfWhitespaceComesBackAsTheNameAlone() {
    assertEquals(WRITTEN, named("   ", WRITTEN));
    assertEquals(WRITTEN, named("\n\t ", WRITTEN));
  }

  /** A '#' or a '?' ends the authority too, so an '@' after either is not userinfo. */
  @Test
  public void anAtSignOutsideTheAuthorityIsNotAPassword() {
    assertEquals("https://a.example#u@h", BrowserLoginPolicy.withoutUserInfo("https://a.example#u@h"));
    assertEquals("https://a.example?to=u@h",
        BrowserLoginPolicy.withoutUserInfo("https://a.example?to=u@h"));
    assertEquals("https://a.example", BrowserLoginPolicy.withoutUserInfo("https://a.example"));
  }

  /** The engine refuses an address holding a password, so the login hands it none. */
  @Test
  public void theAddressGivenToTheEngineCarriesNoPassword() {
    assertEquals("https://a.example/x",
        BrowserLoginPolicy.withoutUserInfo("https://user:pass@a.example/x"));
    assertEquals("https://a.example/x", BrowserLoginPolicy.withoutUserInfo("https://user@a.example/x"));
    // The LAST '@' of the authority ends it, and one later in the path is not userinfo.
    assertEquals("https://a.example/x",
        BrowserLoginPolicy.withoutUserInfo("https://u:p@w@a.example/x"));
    assertEquals("https://a.example/u@x", BrowserLoginPolicy.withoutUserInfo("https://a.example/u@x"));
    assertEquals("https://a.example/x?to=u@h",
        BrowserLoginPolicy.withoutUserInfo("https://a.example/x?to=u@h"));
    // Nothing to cut.
    assertEquals("https://a.example/", BrowserLoginPolicy.withoutUserInfo("https://a.example/"));
    assertEquals("https://a.example", BrowserLoginPolicy.withoutUserInfo("https://a.example"));
    assertNull(BrowserLoginPolicy.withoutUserInfo(null));
  }

  /**
   * The engine's own address need not equal the field's. It cuts a fragment, trims a trailing
   * host dot and puts an international host into ASCII, so matching its text appended a second
   * root for every address holding a fragment. The token is matched by identity instead.
   */
  @Test
  public void theEnginesOwnRewritingOfTheAddressStillNamesOneToken() {
    // The engine cut the fragment, so its address does not equal the token the field holds.
    assertEquals(WRITTEN,
        named("https://a.example/members/#section2", WRITTEN));
    assertEquals(WRITTEN,
        named("https://a.example/members/?q=1#f", WRITTEN));
    // The engine trims a trailing host dot, and lowercases the name but not the address.
    assertEquals(WRITTEN,
        named("https://a.example./members/", WRITTEN));
    assertEquals(WRITTEN,
        named("https://A.Example/MEMBERS/", WRITTEN));
    // A different page of the same site is still the token the login was opened for.
    assertEquals(WRITTEN, named("https://a.example/", WRITTEN));
  }

  /**
   * A login that ends somewhere the field does not name leaves the engine naming a bundle for
   * an address that is gone, so the name takes a line of its own rather than being dropped.
   */
  @Test
  public void aNameWithNoAddressToSitOnGetsItsOwnLine() {
    // Only tokens that name no host at all, because any address would take the name instead.
    assertEquals("-foo *.gif\n" + WRITTEN,
        named("-foo *.gif", WRITTEN));
    assertEquals(WRITTEN, named("", WRITTEN));
    assertEquals(WRITTEN, named(null, WRITTEN));
  }

  /** Nothing is written when the engine refused the login. */
  @Test
  public void anEngineThatWroteNothingChangesNothing() {
    assertEquals("https://a.example", named("https://a.example", null));
    assertEquals("https://a.example", named("https://a.example", ""));
    assertEquals("https://a.example",
        named("https://a.example", "https://a.example/no-suffix"));
  }

  /**
   * Forget deletes the bundles, and a start URL naming one that is gone stops the crawl at
   * startup by design, so the names have to go with the files.
   */
  @Test
  public void forgettingTheLoginTakesEveryBundleNameOutOfTheField() {
    assertEquals("https://a.example/members/",
        BrowserLoginPolicy.withoutBundleNames(SAME_HOST));
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
    assertEquals("a.example", BrowserLoginPolicy.bundleNameOf(SAME_HOST));
    assertNull(BrowserLoginPolicy.bundleNameOf("https://a.example/"));
    assertNull(BrowserLoginPolicy.bundleNameOf(null));
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
