package com.httrack.android;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The cookie field limits against the pinned engine, which owns them.
 *
 * A record passes two gates, and only the tighter one decides whether the cookie is kept:
 * cookie_load() reads each field into a buffer, then cookie_add() refuses a field the jar
 * cannot hold. Taking the buffer alone wrote cookies the engine parsed and then discarded with
 * a warning no Android user sees.
 */
public class BrowserLoginEngineLimitsTest {
  private static String engineSource() throws IOException {
    return TestSources.read(TestSources.engineFile("src/htsbauth.c"));
  }

  /**
   * Just the cookies.txt branch of cookie_load. The Windows branch above it merges copied IE
   * cookies through buffers of its own, and one of them is spelled the same.
   */
  private static String parseBlock(final String source) {
    return TestSources.between(source, "// then cookies.txt", "-- basic auth --");
  }

  /** The one number a regex matched, so a changed engine reads as a failure, never as a pass. */
  private static int only(final String source, final String regex) {
    final Matcher match = Pattern.compile(regex).matcher(source);
    if (!match.find()) {
      throw new IllegalStateException("no " + regex);
    }
    final int value = Integer.parseInt(match.group(1));
    if (match.find()) {
      throw new IllegalStateException("more than one " + regex);
    }
    return value;
  }

  /** Buffer of SIZE bytes holds SIZE-1, because cookie_load_field refuses len >= dst_size. */
  private static int buffer(final String source, final String declaration) {
    return only(source, Pattern.quote(declaration) + "\\[(\\d+)\\]") - 1;
  }

  /** cookie_add returns -1 above its cap, so the cap itself is still accepted. */
  private static int jarCap(final String source, final String field) {
    return only(source, "strlen\\(" + Pattern.quote(field) + "\\) > (\\d+)");
  }

  @Test
  public void everyLimitIsTheTighterOfTheEnginesTwoGates() throws IOException {
    final String source = engineSource();
    final String block = parseBlock(source);
    assertEquals("domain", Math.min(buffer(block, "char domain"), jarCap(source, "domain")),
        BrowserLoginCookies.MAX_DOMAIN);
    assertEquals("name", Math.min(buffer(block, "char cook_name"),
        jarCap(source, "cook_name")), BrowserLoginCookies.MAX_NAME);
    assertEquals("value", Math.min(buffer(block, "char BIGSTK cook_value"),
        jarCap(source, "cook_value")), BrowserLoginCookies.MAX_VALUE);
  }

  /**
   * cookie_load drops a whole line at line_max. The three field caps have to keep every line we
   * can write under that by themselves, which is why line() carries no length check of its own.
   */
  @Test
  public void theFieldCapsKeepEveryLineUnderTheEnginesLineMax() throws IOException {
    final int lineMax = only(parseBlock(engineSource()), "line_max = (\\d+)");
    final int fixedFields = "\tTRUE\t/\tFALSE\t0\t".length() + "\t".length();
    final int longest = BrowserLoginCookies.MAX_DOMAIN + BrowserLoginCookies.MAX_NAME
        + BrowserLoginCookies.MAX_VALUE + fixedFields;
    org.junit.Assert.assertTrue(longest + " vs " + lineMax, longest < lineMax);
  }

  /** Our file has to leave the mirror's own cookies.txt room in the same jar. */
  @Test
  public void theByteBudgetFitsInsideTheJar() throws IOException {
    final int jar = only(TestSources.read(TestSources.engineFile("src/htscore.c")),
        "cookie.max_len = (\\d+)");
    org.junit.Assert.assertTrue(jar + " vs " + BrowserLoginCookies.MAX_BYTES,
        BrowserLoginCookies.MAX_BYTES < jar);
  }
}
