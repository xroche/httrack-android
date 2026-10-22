package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** Which extra headers the user is told the crawl will not send. */
public class HeaderWarningsTest {
  /** The names in an engine array like {@code static const char *const refused[] = {...}}. */
  private static List<String> engineList(final String name) throws IOException {
    final String source = TestSources.read(TestSources.engineFile("src/htslib.c"));
    final Matcher array = Pattern.compile(
        "const char \\*const " + name + "\\[\\]\\s*=\\s*\\{([^}]*)\\}").matcher(source);
    assertTrue("no engine array named " + name, array.find());
    final List<String> out = new ArrayList<String>();
    final Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(array.group(1));
    while (quoted.find()) {
      out.add(quoted.group(1));
    }
    assertTrue("the engine array " + name + " did not parse", out.size() > 1);
    return out;
  }

  /** Our copy has to be the engine's, or the dialog promises what the crawl does not do. */
  @Test
  public void theRefusedNamesAreTheEnginesOwn() throws IOException {
    assertEquals(engineList("refused"), HeaderWarnings.FRAMING);
  }

  @Test
  public void theSingleFieldNamesAreTheEnginesOwn() throws IOException {
    assertEquals(engineList("single"), HeaderWarnings.SINGLE);
  }

  @Test
  public void aFramingHeaderIsReportedWithItsLineNumber() {
    assertEquals(Arrays.asList("Line 2: HTTrack sets this header itself"),
        HeaderWarnings.refused("X-A: 1\nConnection: close\nX-B: 2"));
  }

  /** A header name is case-insensitive, so the warning cannot depend on how it was typed. */
  @Test
  public void theNameIsMatchedWhateverItsCase() {
    assertEquals(1, HeaderWarnings.refused("content-LENGTH: 10").size());
    assertEquals(1, HeaderWarnings.refused("tE: trailers").size());
  }

  @Test
  public void nothingIsReportedWhenEveryLineWouldBeSent() {
    assertTrue(HeaderWarnings.refused("X-A: 1\nX-B: 2").isEmpty());
    assertTrue(HeaderWarnings.refused("").isEmpty());
    assertTrue(HeaderWarnings.refused(null).isEmpty());
    // A single-field name is fine until a second line repeats it.
    assertTrue(HeaderWarnings.refused("Host: a.example").isEmpty());
  }

  /** The engine keeps the LAST of these, so the user has to learn the earlier one is gone. */
  @Test
  public void aRepeatedSingleFieldNamesTheLineThatLoses() {
    assertEquals(Arrays.asList("Line 2: an earlier line already set this header"),
        HeaderWarnings.refused("Host: a.example\nHost: b.example"));
    assertEquals(Arrays.asList("Line 3: an earlier line already set this header"),
        HeaderWarnings.refused("Cookie: a=1\nX-A: 1\ncookie: b=2"));
    // Not a single-field name, so a repeat is legitimate and goes out twice.
    assertTrue(HeaderWarnings.refused("X-A: 1\nX-A: 2").isEmpty());
  }

  @Test
  public void aLineThatNamesNoHeaderIsReported() {
    final String why = "the line is not a header";
    assertEquals(Arrays.asList("Line 1: " + why), HeaderWarnings.refused("no colon here"));
    assertEquals(Arrays.asList("Line 1: " + why), HeaderWarnings.refused(": empty name"));
    // A space is not a token character, and RFC 7230 allows no space before the colon.
    assertEquals(Arrays.asList("Line 1: " + why), HeaderWarnings.refused("X A: 1"));
    assertEquals(Arrays.asList("Line 1: " + why), HeaderWarnings.refused("X-é: 1"));
    // These punctuation marks ARE token characters, so the line is a header.
    assertTrue(HeaderWarnings.refused("X-a!#$%&'*+-.^_`|~9: 1").isEmpty());
  }

  /** Blank lines never reach the engine, so they must not shift the numbering either. */
  @Test
  public void blankLinesAreNotCounted() {
    assertEquals(Arrays.asList("Line 2: HTTrack sets this header itself"),
        HeaderWarnings.refused("X-A: 1\n\n   \nConnection: close"));
  }

  /** Every dropped line is named, or the user fixes one and walks into the next. */
  @Test
  public void everyDroppedLineIsNamed() {
    assertEquals(Arrays.asList("Line 1: HTTrack sets this header itself",
        "Line 3: HTTrack sets this header itself"),
        HeaderWarnings.refused("TE: trailers\nX-A: 1\nUpgrade: h2c"));
  }
}
