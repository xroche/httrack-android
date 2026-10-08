package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.OptionMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Glued numeric options the engine refuses above a ceiling. An over-range
 * field must emit nothing, or the engine panics and the crawl never starts.
 */
public class GluedNumberCapTest {
  private static final OptionsMapper MAPPER = new OptionsMapper();

  /** Longer than any ceiling, and too long for a long. */
  private static final String OVER_ANY_CEILING = "99999999999999999999";

  /** What ships: the mapper the production table wires the key to. */
  private static List<String> emit(final String key, final String value) {
    final OptionMapper wired = MAPPER.fieldsNameToMapper.get(key);
    assertTrue(key + " reaches no mapper", wired != null);
    final List<String> commandline = new ArrayList<String>();
    wired.emit(commandline, value);
    return commandline;
  }

  /** The ceiling the engine itself enforces for a glued option. */
  private static String engineCeiling(final String field) throws Exception {
    final String source =
        TestSources.read(TestSources.engineFile("src/htscoremain.c"));
    final Matcher bound = Pattern
        .compile("readGlued(?:LL)?(?:[Ii]nt|Enum)\\(opt->" + field + ",\\s*([^)]+)\\)")
        .matcher(source);
    assertTrue("the engine no longer bounds opt->" + field, bound.find());
    return bound.group(1).trim();
  }

  private static String engineEnumValue(final String name) throws Exception {
    final String header = TestSources.read(TestSources.engineFile("src/htsopt.h"));
    final Matcher value =
        Pattern.compile("(?<![A-Za-z0-9_])" + name + "\\b\\s*=\\s*(\\d+)")
            .matcher(header);
    assertTrue("the engine no longer defines " + name, value.find());
    return value.group(1);
  }

  @Test
  public void depthStopsOneShortOfIntMax() throws Exception {
    assertEquals("the engine moved the depth ceiling", "INT_MAX - 1",
        engineCeiling("depth"));
    assertEquals("[-r2147483646]", emit("Depth", "2147483646").toString());
    assertEquals("the engine refuses INT_MAX for -r", "[]",
        emit("Depth", "2147483647").toString());
  }

  @Test
  public void anIntFieldStopsAtIntMax() throws Exception {
    assertEquals("the engine moved the timeout ceiling", "INT_MAX",
        engineCeiling("timeout"));
    assertEquals("[-T2147483647]", emit("TimeOut", "2147483647").toString());
    assertEquals("[]", emit("TimeOut", "2147483648").toString());
  }

  /** -M is an int64 in the engine, so a 10 GB cap must still ship. */
  @Test
  public void maxAllKeepsItsSixtyFourBitRange() throws Exception {
    assertEquals("the engine moved the maxsite ceiling", "INT64_MAX",
        engineCeiling("maxsite"));
    assertEquals("[-M10000000000]", emit("MaxAll", "10000000000").toString());
    assertEquals("[-M9223372036854775807]",
        emit("MaxAll", "9223372036854775807").toString());
    assertEquals("[]", emit("MaxAll", "9223372036854775808").toString());
  }

  /**
   * The engine refuses -u above 2 and -s above HTS_ROBOTS_ALWAYS_STRICT. Both are
   * radio groups, but an imported profile carries whatever it likes.
   */
  @Test
  public void anEnumeratedOptionStopsAtTheEnginesOwnCeiling() throws Exception {
    assertEquals("the engine moved the check_type ceiling", "2",
        engineCeiling("check_type"));
    assertEquals("[-u2]", emit("CheckType", "2").toString());
    assertEquals("[]", emit("CheckType", "3").toString());

    assertEquals("the engine moved the robots ceiling", "HTS_ROBOTS_ALWAYS_STRICT",
        engineCeiling("robots"));
    assertEquals("our -s ceiling is not the engine's",
        engineEnumValue("HTS_ROBOTS_ALWAYS_STRICT"),
        String.valueOf(OptionsMapper.MAX_ROBOTS));
    assertEquals("[-s3]", emit("FollowRobotsTxt", "3").toString());
    assertEquals("[]", emit("FollowRobotsTxt", "4").toString());
  }

  /** The engine scans -%c with %f and clamps it, so no integer ceiling applies. */
  @Test
  public void aFractionOptionIsLeftAlone() {
    assertEquals("[-%c0.5]", emit("MaxConn", "0.5").toString());
    assertEquals("[-%c" + OVER_ANY_CEILING + "]",
        emit("MaxConn", OVER_ANY_CEILING).toString());
  }

  /**
   * Every mapper in the table, so no unbounded emitter survives. It cannot see a
   * cap that is merely too HIGH, because a run this long overflows a long at any
   * ceiling, so an option the engine bounds below INT_MAX needs its own boundary
   * test above.
   */
  @Test
  public void noFieldGluesAnOverRangeNumber() {
    final Pattern glued = Pattern.compile("^-.*" + OVER_ANY_CEILING + "$");
    final List<String> offenders = new ArrayList<String>();
    for (final Map.Entry<String, OptionMapper> entry :
        MAPPER.fieldsNameToMapper.entrySet()) {
      final OptionMapper mapper = entry.getValue();
      if (mapper instanceof OptionsMapper.SimpleOption
          && ((OptionsMapper.SimpleOption) mapper).fraction) {
        continue;
      }
      final List<String> commandline = new ArrayList<String>();
      mapper.emit(commandline, OVER_ANY_CEILING);
      for (final String token : commandline) {
        if (glued.matcher(token).matches()) {
          offenders.add(entry.getKey() + " -> " + token);
        }
      }
    }
    assertEquals("a field glues a number the engine refuses", "[]",
        offenders.toString());
  }
}
