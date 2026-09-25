package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Pair;
import com.httrack.android.OptionsMapper.OptionMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The Retry-After cap. Both bounds are hand-kept copies of engine constants, and
 * the engine panics outside them rather than clamping, so a wrong copy loses the
 * whole crawl and not just the option.
 */
public class MaxRetryAfterFieldTest {
  private static final OptionsMapper MAPPER = new OptionsMapper();

  private static String engineDefine(final String name) throws Exception {
    final String header =
        TestSources.read(TestSources.engineFile("src/htsglobal.h"));
    final Matcher define =
        Pattern.compile("#define\\s+" + name + "\\s+(\\d+)").matcher(header);
    assertTrue("the engine no longer defines " + name, define.find());
    return define.group(1);
  }

  /* The mapper fieldsMapper wires the key to, so these run what ships. */
  private static OptionMapper wired() {
    for (final Pair<String, OptionMapper> field : MAPPER.fieldsMapper) {
      if ("MaxRetryAfter".equals(field.first)) {
        return field.second;
      }
    }
    fail("MaxRetryAfter is not in fieldsMapper");
    return null;
  }

  private static List<String> emit(final String value) {
    final List<String> commandline = new ArrayList<String>();
    wired().emit(commandline, value);
    return commandline;
  }

  @Test
  public void ourLimitIsTheEnginesLimit() throws Exception {
    assertEquals("the engine moved HTS_MAX_RETRY_AFTER_LIMIT",
        engineDefine("HTS_MAX_RETRY_AFTER_LIMIT"),
        String.valueOf(OptionsMapper.MAX_RETRY_AFTER_LIMIT));
  }

  /** The hint is what the user reads as the value they get by leaving it blank. */
  @Test
  public void everyVariantHintsTheEnginesDefault() throws Exception {
    final String hint =
        "android:hint=\"" + engineDefine("HTS_DEFAULT_MAX_RETRY_AFTER") + "\"";
    int declaring = 0;
    for (final File layout :
        TestSources.layouts("activity_options_flowcontrol")) {
      final String source = TestSources.read(layout);
      if (!source.contains("editMaxRetryAfter")) {
        continue;
      }
      declaring++;
      assertTrue(layout.getName() + " does not hint the engine default",
          TestSources.between(source, "editMaxRetryAfter", "/>").contains(hint));
    }
    assertEquals("no flow-control layout declares the field", 1, declaring);
  }

  @Test
  public void aValueInsideTheRangeReachesTheEngine() {
    assertEquals(Arrays.asList("-%J120"), emit("120"));
  }

  /** 0 is a setting and not an absence: it means retry with no wait at all. */
  @Test
  public void zeroReachesTheEngine() {
    assertEquals(Arrays.asList("-%J0"), emit("0"));
  }

  @Test
  public void theLimitItselfReachesTheEngine() {
    assertEquals(
        Arrays.asList("-%J" + OptionsMapper.MAX_RETRY_AFTER_LIMIT),
        emit(String.valueOf(OptionsMapper.MAX_RETRY_AFTER_LIMIT)));
  }

  /* Each of these panics htscoremain.c and returns -1, so the crawl never
     starts. A profile saved by another front end can hold any of them. */
  @Test
  public void aValueTheEngineWouldRefuseIsDropped() {
    for (final String value : new String[] {
        String.valueOf(OptionsMapper.MAX_RETRY_AFTER_LIMIT + 1), "3601",
        "99999", "99999999999999999999", "-1", "1.5", "60s", " 60", "", null }) {
      assertEquals("the engine would refuse " + value,
          new ArrayList<String>(), emit(value));
    }
  }
}
