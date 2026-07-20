package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * A tampered winprofile.ini must not inject engine options through the URL field.
 * These exercise the actual token-production (urlTokens), which UrlSplit.emit
 * delegates to, not just the isOptionLike predicate.
 */
public class ImportProfileHardeningTest {
  @Test
  public void dropsInjectedOptionTokensButKeepsTheUrl() {
    // A CurrentUrl tampered to inject -O: the flag is dropped, the real URL survives.
    final List<String> tokens = CommandlineTokens.urlTokens("-O /evil http://real.example/");
    assertFalse(tokens.contains("-O"));
    assertTrue(tokens.contains("http://real.example/"));
  }

  @Test
  public void keepsNormalUrls() {
    final List<String> tokens =
        CommandlineTokens.urlTokens("http://example.com/ www.foo.org example.com/path?a=b");
    assertEquals(3, tokens.size());
    assertTrue(tokens.contains("www.foo.org"));
  }

  @Test
  public void dropsAllOptionAndPlusTokens() {
    assertTrue(CommandlineTokens.urlTokens("-%O +x").isEmpty());
  }

  /** Filter tokens read as option-like: why WildCardFilters must keep the plain split. */
  @Test
  public void filterTokensAreOptionLike() {
    assertTrue(CommandlineTokens.isOptionLike("+*.png"));
    assertTrue(CommandlineTokens.isOptionLike("-*.gif"));
    assertFalse(CommandlineTokens.isOptionLike("http://x/"));
  }
}
