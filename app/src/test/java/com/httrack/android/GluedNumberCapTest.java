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
 * Glued numeric options the engine refuses above a ceiling. It used to read
 * them with sscanf and run on whatever it got, but it now panics, so an
 * over-range field must emit nothing.
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
        .compile("readGluedU?L?L?[Ii]nt\\(opt->" + field + ",\\s*([^)]+)\\)")
        .matcher(source);
    assertTrue("the engine no longer bounds opt->" + field, bound.find());
    return bound.group(1).trim();
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
    assertEquals("[]", emit("MaxAll", OVER_ANY_CEILING).toString());
  }

  /** The engine scans -%c with %f and clamps it, so no integer ceiling applies. */
  @Test
  public void aFractionOptionIsLeftAlone() {
    assertEquals("[-%c0.5]", emit("MaxConn", "0.5").toString());
    assertEquals("[-%c" + OVER_ANY_CEILING + "]",
        emit("MaxConn", OVER_ANY_CEILING).toString());
  }

  /**
   * The whole population, not the keys I thought to list. The mapper's own
   * fraction flag picks out the options the engine does not read as integers.
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
