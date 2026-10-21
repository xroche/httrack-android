package com.httrack.android;

import static org.junit.Assert.assertArrayEquals;
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
    /* Host-only. The browser told us what it sends to this host, so TRUE here would hand the
       session to every subdomain the crawl reaches, which cookie_is_host_only would allow. */
    assertEquals("FALSE", fields[1]);
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
    final List<String[]> cookies = BrowserLoginCookies.split("a=1; b=2");
    assertEquals(2, cookies.size());
    assertArrayEquals(new String[] { "a", "1" }, cookies.get(0));
    assertArrayEquals(new String[] { "b", "2" }, cookies.get(1));
  }

  /** A tab would add a field and a newline a record, so neither may reach the file. */
  @Test
  public void refusesAFieldThatWouldForgeARecord() {
    assertNull(BrowserLoginCookies.line("h.example", "a\tb", "v"));
    assertNull(BrowserLoginCookies.line("h.example", "n", "v\nevil.example\tTRUE\t/"));
    assertNull(BrowserLoginCookies.line("h.example", "n", "v\rx"));
    assertNull(BrowserLoginCookies.line("evil\t.example", "n", "v"));
    assertNull(BrowserLoginCookies.line("h.example", "n", "v\u0000x"));
    assertNull(BrowserLoginCookies.line("h.example", "n", "v\u007fx"));
  }

  /** The jar spends one budget across the pair, so neither field has a limit of its own. */
  @Test
  public void refusesAPairTheJarWouldNotHold() {
    final String host = "h.example";
    final int pair = BrowserLoginCookies.MAX_PAIR;
    // Exactly the budget fits, whichever side of the pair carries it.
    assertNotNull(BrowserLoginCookies.line(host, "n", repeat("v", pair - 1)));
    assertNotNull(BrowserLoginCookies.line(host, repeat("n", pair), ""));
    // Both sides loaded and summing to the budget, which no other case covers.
    assertNotNull(BrowserLoginCookies.line(host, repeat("n", pair / 2), repeat("v", pair / 2)));
    // One byte more does not, and a long name now costs the value its room.
    assertNull(BrowserLoginCookies.line(host, "n", repeat("v", pair)));
    assertNull(BrowserLoginCookies.line(host, repeat("n", pair + 1), ""));
    assertNull(BrowserLoginCookies.line(host, repeat("n", pair / 2), repeat("v", pair / 2 + 1)));
    assertNull(BrowserLoginCookies.line(repeat("d", BrowserLoginCookies.MAX_DOMAIN + 1), "n", "v"));
  }

  /** strlen() counts bytes, so a two-byte character must count twice against every limit. */
  @Test
  public void measuresLimitsInBytesNotCharacters() {
    /* Half as many characters as the limit, so exactly the limit in bytes. Java's count says
       it is half spent and the engine's says it is all spent, which is the divergence. */
    final String twoByte = repeat("é", BrowserLoginCookies.MAX_PAIR / 2);
    assertEquals(BrowserLoginCookies.MAX_PAIR / 2, twoByte.length());
    assertNull(BrowserLoginCookies.line("h.example", twoByte, "v"));
    // One character shorter leaves one byte, which the value then fills exactly.
    assertNotNull(BrowserLoginCookies.line("h.example",
        repeat("é", BrowserLoginCookies.MAX_PAIR / 2 - 1), "v"));
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

  /**
   * A cookie the jar will not keep must not be reported as saved. cookie_add refuses an
   * over-budget pair even though the line parses, so counting it would tell the user a session
   * was saved while the crawl runs logged out.
   */
  @Test
  public void aCookieTheJarWouldRefuseCountsAsDropped() {
    final BrowserLoginCookies.Result result = BrowserLoginCookies.file(one("h.example",
        "small=ok; big=" + repeat("v", BrowserLoginCookies.MAX_PAIR)));
    assertEquals(1, result.cookies);
    assertEquals(1, result.dropped);
    assertFalse(result.text, result.text.contains("big"));
  }

  /** One line per name, holding the last value, which is what the engine would have kept. */
  @Test
  public void aRepeatedNameKeepsTheLastValue() {
    final BrowserLoginCookies.Result result =
        BrowserLoginCookies.file(one("www.example.com", "sid=older; sid=newer; other=1"));
    assertEquals(2, result.cookies);
    // Superseded rather than lost, so it is not reported to the user as left out.
    assertEquals(0, result.dropped);
    assertTrue(result.text, result.text.contains("\tsid\tnewer\n"));
    assertFalse(result.text, result.text.contains("older"));
  }

  /** The same name on two hosts is two cookies, so the key has to carry the host. */
  @Test
  public void aNameOnTwoHostsSurvivesOnBoth() {
    final Map<String, String> byHost = new LinkedHashMap<String, String>();
    byHost.put("a.example", "sid=one");
    byHost.put("b.example", "sid=two");
    final BrowserLoginCookies.Result result = BrowserLoginCookies.file(byHost);
    assertEquals(2, result.cookies);
    assertEquals(2, result.hosts);
    assertTrue(result.text, result.text.contains("a.example\tFALSE\t/\tFALSE\t0\tsid\tone\n"));
    assertTrue(result.text, result.text.contains("b.example\tFALSE\t/\tFALSE\t0\tsid\ttwo\n"));
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
