package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A tampered winprofile.ini must not inject engine options through the URL
 * field: OptionsMapper.UrlSplit drops tokens flagged here, while the filter
 * field keeps plain StringSplit so +/- rules still pass.
 */
public class ImportProfileHardeningTest {
  @Test
  public void dropsInjectedOptionTokens() {
    assertTrue(CommandlineTokens.isOptionLike("-O"));
    assertTrue(CommandlineTokens.isOptionLike("-%O"));
  }

  @Test
  public void passesNormalUrls() {
    assertFalse(CommandlineTokens.isOptionLike("http://example.com/"));
    assertFalse(CommandlineTokens.isOptionLike("www.foo.org"));
    assertFalse(CommandlineTokens.isOptionLike("example.com/path?a=b"));
  }

  // +/- filter tokens read as option-like: why the filter field must NOT apply this.
  @Test
  public void filterTokensAreOptionLike() {
    assertTrue(CommandlineTokens.isOptionLike("+*.png"));
    assertTrue(CommandlineTokens.isOptionLike("-*.gif"));
  }
}
