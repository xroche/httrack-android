package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The cookie limits against the pinned engine, which owns them.
 *
 * A record passes two gates, and only the tighter one decides whether the cookie is kept.
 * cookie_load reads each field into a buffer, then cookie_add refuses a pair the jar cannot
 * hold. Reading the buffer alone once wrote cookies the engine parsed and then discarded with
 * a warning no Android user sees.
 */
public class BrowserLoginEngineLimitsTest {
  private static String source(final String name) throws IOException {
    return TestSources.read(TestSources.engineFile("src/" + name));
  }

  /**
   * Just the cookies.txt branch of cookie_load. The Windows branch above it merges copied IE
   * cookies through buffers of its own, and one of them is spelled the same.
   */
  private static String parseBlock(final String htsbauth) {
    return TestSources.between(htsbauth, "// then cookies.txt", "-- basic auth --");
  }

  /** The one number a regex matched, so a changed engine reads as a failure, never as a pass. */
  private static int only(final String text, final String regex) {
    final Matcher match = Pattern.compile(regex).matcher(text);
    if (!match.find()) {
      throw new IllegalStateException("no " + regex);
    }
    final int value = Integer.parseInt(match.group(1));
    if (match.find()) {
      throw new IllegalStateException("more than one " + regex);
    }
    return value;
  }

  /** A buffer of SIZE bytes holds SIZE-1, because cookie_load_field refuses len >= dst_size. */
  @Test
  public void theDomainLimitIsTheLoadersBuffer() throws IOException {
    assertEquals(only(parseBlock(source("htsbauth.c")), "char domain\\[(\\d+)\\]") - 1,
        BrowserLoginCookies.MAX_DOMAIN);
  }

  /**
   * cookie_add caps name and value together against COOKIE_MAX_PAIR, so neither field has a
   * limit of its own and the writer must spend one budget across both.
   */
  @Test
  public void thePairLimitIsTheEnginesOwnConstant() throws IOException {
    assertEquals(only(source("htsbauth.h"), "COOKIE_MAX_PAIR (\\d+)"),
        BrowserLoginCookies.MAX_PAIR);
    // The guard really is joint: the value is measured against what the name leaves.
    final String guard = source("htsbauth.c");
    assertTrue("cookie_add no longer caps the pair jointly",
        guard.contains("COOKIE_MAX_PAIR - strlen(cook_name)"));
  }

  /**
   * cookie_load drops a whole line at line_max. The caps above have to keep every line we can
   * write under that by themselves, which is why line() carries no length check of its own.
   */
  @Test
  public void theCapsKeepEveryLineUnderTheEnginesLineMax() throws IOException {
    final int lineMax = only(parseBlock(source("htsbauth.c")), "line_max = (\\d+)");
    final int fixedFields = "\tTRUE\t/\tFALSE\t0\t".length() + "\t".length();
    final int longest = BrowserLoginCookies.MAX_DOMAIN + BrowserLoginCookies.MAX_PAIR
        + fixedFields;
    assertTrue(longest + " vs " + lineMax, longest < lineMax);
  }

  /** Our file has to leave the mirror's own cookies.txt room in the same jar. */
  @Test
  public void theByteBudgetFitsInsideTheJar() throws IOException {
    final int jar = only(source("htscore.c"), "cookie.max_len = (\\d+)");
    assertTrue(jar + " vs " + BrowserLoginCookies.MAX_BYTES,
        BrowserLoginCookies.MAX_BYTES < jar);
  }
}
