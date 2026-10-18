package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The cookies.txt handed to -%K, against what cookie_load() in the pinned engine reads: fields
 * 0, 2, 5 and 6 of a tab-separated line, any line starting with '#' skipped.
 */
public class BrowserLoginCookiesTest {
  private static Map<String, String> one(final String host, final String header) {
    final Map<String, String> byHost = new LinkedHashMap<String, String>();
    byHost.put(host, header);
    return byHost;
  }

  /** Records only, header lines dropped. */
  private static List<String> records(final String text) {
    final java.util.List<String> lines = new java.util.ArrayList<String>();
    for (final String line : text.split("\n")) {
      if (line.length() != 0 && line.charAt(0) != '#') {
        lines.add(line);
      }
    }
    return lines;
  }

  @Test
  public void writesTheSevenFieldsTheEngineReads() {
    final BrowserLoginCookies.Result result =
        BrowserLoginCookies.file(one("www.example.com", "sess=abc123; theme=dark"));
    final List<String> lines = records(result.text);
    assertEquals(2, lines.size());
    final String[] fields = lines.get(0).split("\t", -1);
    assertEquals(7, fields.length);
    assertEquals("www.example.com", fields[0]);
    assertEquals("TRUE", fields[1]);
    assertEquals("/", fields[2]);
    assertEquals("FALSE", fields[3]);
    assertEquals("0", fields[4]);
    assertEquals("sess", fields[5]);
    assertEquals("abc123", fields[6]);
    assertEquals(2, result.cookies);
    assertEquals(1, result.hosts);
    assertEquals(0, result.dropped);
  }

  /** cookie_load() skips every line starting with '#', so no record may begin with one. */
  @Test
  public void noRecordStartsWithAComment() {
    // A '#' is legal inside a name, and the host leads the record, so the line still parses.
    final BrowserLoginCookies.Result result =
        BrowserLoginCookies.file(one("x.example", "#odd=abc; ok=1"));
    assertEquals(2, result.cookies);
    for (final String line : records(result.text)) {
      assertFalse(line, line.startsWith("#"));
    }
    // A host is the one field that could start the line with one, so it is refused there.
    assertNull(BrowserLoginCookies.line("#x.example", "n", "v"));
  }

  @Test
  public void keepsTheWholeValuePastItsFirstEquals() {
    final String value = BrowserLoginCookies.file(one("h.example", "jwt=aa=bb==")).text;
    assertTrue(value, value.contains("\tjwt\taa=bb==\n"));
  }

  @Test
  public void keepsAnEmptyValueButDropsAnEmptyName() {
    assertNotNull(BrowserLoginCookies.line("h.example", "sess", ""));
    assertNull(BrowserLoginCookies.line("h.example", "", "v"));
    // "=v" has no name, so split() never offers it as a cookie.
    assertTrue(BrowserLoginCookies.split("=v").isEmpty());
  }

  @Test
  public void splitReadsNameThenValueInOrder() {
    final List<String> pairs = BrowserLoginCookies.split("a=1; b=2");
    assertEquals(4, pairs.size());
    assertEquals("a", pairs.get(0));
    assertEquals("1", pairs.get(1));
    assertEquals("b", pairs.get(2));
    assertEquals("2", pairs.get(3));
  }

  /** A tab would add a field and a newline a record, so neither may reach the file. */
  @Test
  public void refusesAFieldThatWouldForgeARecord() {
    assertNull(BrowserLoginCookies.line("h.example", "a\tb", "v"));
    assertNull(BrowserLoginCookies.line("h.example", "n", "v\nevil.example\tTRUE\t/"));
    assertNull(BrowserLoginCookies.line("h.example", "n", "v\rx"));
    assertNull(BrowserLoginCookies.line("evil\t.example", "n", "v"));
  }

  @Test
  public void refusesAFieldLongerThanTheEnginesBuffer() {
    final String host = "h.example";
    assertNull(BrowserLoginCookies.line(host, repeat("n", BrowserLoginCookies.MAX_NAME + 1), "v"));
    assertNotNull(BrowserLoginCookies.line(host, repeat("n", BrowserLoginCookies.MAX_NAME), "v"));
    assertNull(BrowserLoginCookies.line(host, "n", repeat("v", BrowserLoginCookies.MAX_VALUE + 1)));
    assertNull(BrowserLoginCookies.line(repeat("d", BrowserLoginCookies.MAX_DOMAIN + 1), "n", "v"));
  }

  /** The engine drops a whole line at line_max, so a line that long never gets written. */
  @Test
  public void refusesALineTheEngineWouldDropWhole() {
    final String host = "h.example";
    final int overhead = (host + "\tTRUE\t/\tFALSE\t0\t" + "n" + "\t").length();
    final String fits = repeat("v", BrowserLoginCookies.MAX_LINE - overhead);
    assertNotNull(BrowserLoginCookies.line(host, "n", fits));
    assertNull(BrowserLoginCookies.line(host, "n", fits + "v"));
  }

  /** strlen() counts bytes, so a two-byte character must count twice against every limit. */
  @Test
  public void measuresLimitsInBytesNotCharacters() {
    final String twoByte = repeat("é", BrowserLoginCookies.MAX_NAME / 2 + 1);
    assertTrue(twoByte.length() <= BrowserLoginCookies.MAX_NAME);
    assertNull(BrowserLoginCookies.line("h.example", twoByte, "v"));
  }

  @Test
  public void stopsAtTheByteBudgetAndCountsTheRest() {
    final StringBuilder header = new StringBuilder();
    final int cookies = 60;
    for (int i = 0; i < cookies; i++) {
      header.append("n").append(i).append("=").append(repeat("v", 500)).append("; ");
    }
    final BrowserLoginCookies.Result result =
        BrowserLoginCookies.file(one("h.example", header.toString()));
    assertTrue(result.text.getBytes(java.nio.charset.Charset.forName("UTF-8")).length
        <= BrowserLoginCookies.MAX_BYTES);
    assertTrue(result.dropped > 0);
    assertEquals(cookies, result.cookies + result.dropped);
  }

  @Test
  public void countsOnlyHostsThatGaveACookie() {
    final Map<String, String> byHost = new LinkedHashMap<String, String>();
    byHost.put("a.example", "one=1");
    byHost.put("b.example", "");
    byHost.put("c.example", "two=2");
    final BrowserLoginCookies.Result result = BrowserLoginCookies.file(byHost);
    assertEquals(2, result.hosts);
    assertEquals(2, result.cookies);
    // Visit order is kept, so the login's own host leads the file.
    assertTrue(result.text.indexOf("a.example") < result.text.indexOf("c.example"));
  }

  @Test
  public void anEmptyLoginWritesNoRecord() {
    final BrowserLoginCookies.Result result =
        BrowserLoginCookies.file(new LinkedHashMap<String, String>());
    assertEquals(0, result.cookies);
    assertTrue(records(result.text).isEmpty());
  }

  private static String repeat(final String unit, final int times) {
    final StringBuilder text = new StringBuilder();
    for (int i = 0; i < times; i++) {
      text.append(unit);
    }
    return text.toString();
  }
}
