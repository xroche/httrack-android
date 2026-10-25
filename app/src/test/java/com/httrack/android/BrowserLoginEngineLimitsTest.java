package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The cookie and bundle limits against the pinned engine, which owns them. A record passes two
 * gates, and only the tighter one decides whether the cookie is kept: cookie_load reads each
 * field into a buffer, and cookie_add then refuses a pair the jar cannot hold.
 */
public class BrowserLoginEngineLimitsTest {
  private static String source(final String name) throws IOException {
    return TestSources.read(TestSources.engineFile("src/" + name));
  }

  /**
   * Just the function that parses a Netscape record. Two other readers in this file declare
   * buffers spelled the same: the Windows branch that merges copied IE cookies, and the flat
   * header loader beside it.
   */
  private static String parseBlock(final String htsbauth) {
    return TestSources.balancedBlock(htsbauth,
        TestSources.indexOf(htsbauth, "static int cookie_load_records("));
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

  /** The value the engine gives macro NAME in htsbauth.h, where it owns every cookie cap. */
  private static int macro(final String name) throws IOException {
    return only(source("htsbauth.h"), name + " (\\d+)");
  }

  /** A buffer of SIZE bytes holds SIZE-1, because cookie_load_field refuses len >= dst_size. */
  @Test
  public void theDomainLimitIsTheLoadersBuffer() throws IOException {
    assertEquals(macro("COOKIE_MAX_DOMAIN") - 1, BrowserLoginCookies.MAX_DOMAIN);
    assertTrue("the loader no longer sizes its domain buffer from the cap",
        parseBlock(source("htsbauth.c")).contains("char domain[COOKIE_MAX_DOMAIN]"));
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
   * The loader reads a field before the jar caps it, so its buffers have to hold everything the
   * pair cap allows. A buffer shrunk below that would make cookie_load skip whole lines we
   * consider legal, which is a silent loss rather than a refusal.
   */
  @Test
  public void theLoadersBuffersHoldWhatThePairCapAllows() throws IOException {
    final String block = parseBlock(source("htsbauth.c"));
    // The name buffer is sized from the cap itself, so it cannot fall behind it.
    assertTrue("cook_name is no longer sized from the pair cap",
        block.contains("cook_name[COOKIE_MAX_PAIR + 1]"));
    assertTrue("the cook_value buffer is below the pair cap",
        only(block, "cook_value\\[(\\d+)\\]") - 1 >= BrowserLoginCookies.MAX_PAIR);
  }

  /**
   * cookie_load drops a whole line at line_max. The caps above have to keep every line we can
   * write under that by themselves, which is why line() carries no length check of its own.
   */
  @Test
  public void theCapsKeepEveryLineUnderTheEnginesLineMax() throws IOException {
    final int lineMax = macro("COOKIE_MAX_LINE");
    assertTrue("the loader no longer takes its line cap from the macro",
        parseBlock(source("htsbauth.c")).contains("line_max = COOKIE_MAX_LINE"));
    final int fixedFields = "\tTRUE\t/\tFALSE\t0\t".length() + "\t".length();
    final int longest = BrowserLoginCookies.MAX_DOMAIN + BrowserLoginCookies.MAX_PAIR
        + fixedFields;
    assertTrue(longest + " vs " + lineMax, longest < lineMax);
  }

  /**
   * The engine's paste reader drops every comment line, which is why the records we hand it
   * carry no Netscape header. A reader that kept comments would read ours as a cookie.
   */
  @Test
  public void thePasteReaderDropsEveryCommentLine() throws IOException {
    final String reader = TestSources.balancedBlock(source("htsbundle.c"),
        TestSources.indexOf(source("htsbundle.c"), "static void paste_records("));
    assertTrue(reader, reader.contains("s[0] != '#'"));
    // Except this one, which marks a cookie rather than a comment, and which we never write.
    assertTrue(reader, reader.contains("httponly"));
  }

  /** The whole bundle is the engine's to write, so the entry point has to be exported. */
  @Test
  public void theBundleWriterIsAnExportedEngineCall() throws IOException {
    assertTrue("hts_bundle_from_paste is no longer exported",
        source("httrack-library.h").contains("HTSEXT_API hts_boolean hts_bundle_from_paste("));
    // It reads the file back through the engine's own loader before keeping it.
    final String paste = source("htsbundle.c");
    assertTrue(paste, paste.contains("bundle_load_src(opt, &check, &src"));
  }

  /** Our file has to leave the mirror's own cookies.txt room in the same jar. */
  @Test
  public void theByteBudgetFitsInsideTheJar() throws IOException {
    final int jar = macro("COOKIE_MAX_JAR");
    assertTrue("httpmirror no longer takes the jar size from the macro",
        source("htscore.c").contains("cookie.max_len = COOKIE_MAX_JAR"));
    assertTrue(jar + " vs " + BrowserLoginCookies.MAX_BYTES,
        BrowserLoginCookies.MAX_BYTES < jar);
  }
}
