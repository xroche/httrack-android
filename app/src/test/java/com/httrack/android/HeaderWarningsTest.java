package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.httrack.android.jni.HTTrackLib;

/** Which extra headers the user is told the crawl will not send as typed. */
public class HeaderWarningsTest {
  /** Answers canned verdicts and records what it was asked, so a wrong question fails too. */
  private static class Fake implements HeaderWarnings.Verdicts {
    private final Map<String, Integer> answers = new HashMap<String, Integer>();
    final List<String> asked = new ArrayList<String>();

    /** Answer VERDICT for LINE asked with HEADERS before it, null meaning the first line. */
    Fake on(final String headers, final String line, final int verdict) {
      answers.put(key(headers, line), Integer.valueOf(verdict));
      return this;
    }

    private static String key(final String headers, final String line) {
      return (headers == null ? "<null>" : headers) + " => " + line;
    }

    @Override
    public int of(final String headers, final String line) {
      final String k = key(headers, line);
      asked.add(k);
      final Integer verdict = answers.get(k);
      return verdict == null ? HTTrackLib.HEADER_SENT : verdict.intValue();
    }
  }

  /** "line:reason" per reported line, so one assertion reads as the dialog does. */
  private static List<String> report(final String field, final Fake verdicts) {
    final List<String> out = new ArrayList<String>();
    for (final HeaderWarnings.Dropped dropped : HeaderWarnings.dropped(field, verdicts)) {
      out.add(dropped.line + ":" + dropped.reason);
    }
    return out;
  }

  /** The Java copy of the verdict codes is ABI, so the engine's own values decide it. */
  @Test
  public void theVerdictCodesAreTheEnginesOwn() throws IOException {
    final String source = TestSources.read(TestSources.engineFile("src/httrack-library.h"));
    final Matcher value = Pattern.compile("HTS_HEADER_([A-Z_]+)\\s*=\\s*(\\d+)").matcher(source);
    final Map<String, Integer> engine = new HashMap<String, Integer>();
    while (value.find()) {
      engine.put(value.group(1), Integer.valueOf(value.group(2)));
    }
    assertEquals("the engine enum did not parse", 6, engine.size());
    assertEquals(engine.get("SENT").intValue(), HTTrackLib.HEADER_SENT);
    assertEquals(engine.get("DROPPED_ORPHAN_FOLD").intValue(),
        HTTrackLib.HEADER_DROPPED_ORPHAN_FOLD);
    assertEquals(engine.get("DROPPED_MALFORMED").intValue(),
        HTTrackLib.HEADER_DROPPED_MALFORMED);
    assertEquals(engine.get("DROPPED_FRAMING").intValue(), HTTrackLib.HEADER_DROPPED_FRAMING);
    assertEquals(engine.get("SENT_REPLACES_EARLIER").intValue(),
        HTTrackLib.HEADER_SENT_REPLACES_EARLIER);
    assertEquals(engine.get("SENT_UNLESS_ENGINE_OVERRIDES").intValue(),
        HTTrackLib.HEADER_SENT_UNLESS_ENGINE_OVERRIDES);
  }

  @Test
  public void nothingIsReportedWhenEveryLineGoesOutAsTyped() {
    assertTrue(report("X-A: 1\nX-B: 2", new Fake()).isEmpty());
    assertTrue(report("", new Fake()).isEmpty());
    assertTrue(report(null, new Fake()).isEmpty());
  }

  /** Each verdict the engine can give reaches the user as its own reason. */
  @Test
  public void eachDroppingVerdictBecomesItsOwnReason() {
    assertEquals(Arrays.asList("1:" + HeaderWarnings.REASON_MALFORMED),
        report("no colon here",
            new Fake().on(null, "no colon here", HTTrackLib.HEADER_DROPPED_MALFORMED)));
    assertEquals(Arrays.asList("1:" + HeaderWarnings.REASON_MALFORMED),
        report(" folded",
            new Fake().on(null, "folded", HTTrackLib.HEADER_DROPPED_ORPHAN_FOLD)));
    assertEquals(Arrays.asList("1:" + HeaderWarnings.REASON_FRAMING),
        report("Connection: close",
            new Fake().on(null, "Connection: close", HTTrackLib.HEADER_DROPPED_FRAMING)));
    assertEquals(Arrays.asList("1:" + HeaderWarnings.REASON_CONDITIONAL),
        report("Range: bytes=0-1",
            new Fake().on(null, "Range: bytes=0-1",
                HTTrackLib.HEADER_SENT_UNLESS_ENGINE_OVERRIDES)));
  }

  /**
   * The engine keeps the LAST line of a single-valued field, so the EARLIER one is the line
   * the user loses. Reporting the line the engine keeps would send them to delete the wrong one.
   */
  @Test
  public void aReplacementReportsTheEarlierLine() {
    final Fake verdicts = new Fake()
        .on("Host: a", "Host: b", HTTrackLib.HEADER_SENT_REPLACES_EARLIER);
    assertEquals(Arrays.asList("1:" + HeaderWarnings.REASON_REPLACED),
        report("Host: a\nHost: b", verdicts));
  }

  /** Every loser is named, because the engine drops all but the last of them. */
  @Test
  public void everyEarlierLineOfTheSameFieldIsNamed() {
    final Fake verdicts = new Fake()
        .on("Cookie: a", "Cookie: b", HTTrackLib.HEADER_SENT_REPLACES_EARLIER)
        .on("Cookie: a\r\nCookie: b", "Cookie: c", HTTrackLib.HEADER_SENT_REPLACES_EARLIER)
        .on("Cookie: a", "Cookie: c", HTTrackLib.HEADER_SENT_REPLACES_EARLIER)
        .on("Cookie: b", "Cookie: c", HTTrackLib.HEADER_SENT_REPLACES_EARLIER);
    assertEquals(
        Arrays.asList("1:" + HeaderWarnings.REASON_REPLACED,
            "2:" + HeaderWarnings.REASON_REPLACED),
        report("Cookie: a\nCookie: b\nCookie: c", verdicts));
    // A line between them, so naming only the line just above would name the wrong one.
    final Fake gapped = new Fake()
        .on("Host: a\r\nX-A: 1", "Host: b", HTTrackLib.HEADER_SENT_REPLACES_EARLIER)
        .on("Host: a", "Host: b", HTTrackLib.HEADER_SENT_REPLACES_EARLIER)
        .on("Host: a\r\nX-A: 1\r\nHost: b", "Host: c",
            HTTrackLib.HEADER_SENT_REPLACES_EARLIER)
        .on("Host: a", "Host: c", HTTrackLib.HEADER_SENT_REPLACES_EARLIER)
        .on("Host: b", "Host: c", HTTrackLib.HEADER_SENT_REPLACES_EARLIER);
    assertEquals(
        Arrays.asList("1:" + HeaderWarnings.REASON_REPLACED,
            "3:" + HeaderWarnings.REASON_REPLACED),
        report("Host: a\nX-A: 1\nHost: b\nHost: c", gapped));
  }

  /** A dropped line is still an earlier line, or a later one is judged against the wrong text. */
  @Test
  public void aDroppedLineStillCountsAsAnEarlierLine() {
    final Fake verdicts = new Fake()
        .on(null, "Connection: close", HTTrackLib.HEADER_DROPPED_FRAMING)
        .on("Connection: close", "X-A: 1", HTTrackLib.HEADER_SENT);
    assertEquals(Arrays.asList("1:" + HeaderWarnings.REASON_FRAMING),
        report("Connection: close\nX-A: 1", verdicts));
    assertEquals(Arrays.asList("<null> => Connection: close",
        "Connection: close => X-A: 1"), verdicts.asked);
  }

  /** A conditional line is reported wherever it sits, not only in a box of its own. */
  @Test
  public void aConditionalLineIsReportedInsideALongerBox() {
    final Fake verdicts = new Fake()
        .on("X-A: 1", "Range: bytes=0-1", HTTrackLib.HEADER_SENT_UNLESS_ENGINE_OVERRIDES);
    assertEquals(Arrays.asList("2:" + HeaderWarnings.REASON_CONDITIONAL),
        report("X-A: 1\nRange: bytes=0-1\nX-B: 2", verdicts));
  }

  /** Only a tablet Back leaves for good without a tab change to show the warning anyway. */
  @Test
  public void onlyATabletBackNeedsTheWarningBeforeLeaving() {
    assertTrue(HeaderWarnings.warnsBeforeLeaving(true, true));
    assertFalse(HeaderWarnings.warnsBeforeLeaving(true, false));
    assertFalse(HeaderWarnings.warnsBeforeLeaving(false, true));
    assertFalse(HeaderWarnings.warnsBeforeLeaving(false, false));
  }

  /** A line of another field is not a loser, however many lines separate them. */
  @Test
  public void anUnrelatedLineIsNotReported() {
    final Fake verdicts = new Fake()
        .on("Host: a\r\nX-A: 1", "Host: b", HTTrackLib.HEADER_SENT_REPLACES_EARLIER)
        .on("Host: a", "Host: b", HTTrackLib.HEADER_SENT_REPLACES_EARLIER);
    assertEquals(Arrays.asList("1:" + HeaderWarnings.REASON_REPLACED),
        report("Host: a\nX-A: 1\nHost: b", verdicts));
  }

  /** "Line N" has to be the line the user can count, so a blank line still takes a number. */
  @Test
  public void theNumberIsTheLineTheUserSees() {
    final Fake verdicts = new Fake()
        .on("X-A: 1", "Connection: close", HTTrackLib.HEADER_DROPPED_FRAMING);
    assertEquals(Arrays.asList("4:" + HeaderWarnings.REASON_FRAMING),
        report("X-A: 1\n\n   \nConnection: close", verdicts));
  }

  /** The engine is asked the way its own contract says: NULL first, then the earlier lines. */
  @Test
  public void theEarlierLinesAreAskedAboutAsTheEngineWantsThem() {
    final Fake verdicts = new Fake();
    report("X-A: 1\nX-B: 2\nX-C: 3", verdicts);
    assertEquals(Arrays.asList("<null> => X-A: 1", "X-A: 1 => X-B: 2",
        "X-A: 1\r\nX-B: 2 => X-C: 3"), verdicts.asked);
  }

  /** A line is normalised before the engine sees it, because that is what reaches -%X. */
  @Test
  public void theLineAskedAboutIsTheOneTheEngineGets() {
    final Fake verdicts = new Fake();
    report("  X-A:   1  ", verdicts);
    assertEquals(Arrays.asList("<null> => X-A: 1"), verdicts.asked);
  }
}
