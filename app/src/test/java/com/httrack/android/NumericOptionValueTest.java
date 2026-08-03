package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.OptionMapper;
import com.httrack.android.OptionsMapper.SimpleOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * A value loaded from a saved profile never goes through the keyboard filters,
 * so the mapper is where the engine-hostile ones get dropped (issue #100).
 */
public class NumericOptionValueTest {
  /** The only option the engine scans with %f (htscoremain.c, case 'c'). */
  private static final String FLOAT_OPTION = "%c";

  private static List<String> emit(final OptionMapper mapper, final String value) {
    final List<String> cmd = new ArrayList<String>();
    mapper.emit(cmd, value);
    return cmd;
  }

  /* The mapper table pulls in R.id, which the stub android.jar cannot load, so
     the declarations are read out of the source. */
  private static List<String[]> declaredOptions() throws IOException {
    final String src = new String(Files.readAllBytes(Paths
        .get("src/main/java/com/httrack/android/OptionsMapper.java")), "UTF-8");
    final Matcher m = Pattern.compile(
        "new SimpleOption\\(\\s*\"([^\"]+)\"\\s*(?:,\\s*(true|false)\\s*)?\\)")
        .matcher(src);
    final List<String[]> declared = new ArrayList<String[]>();
    while (m.find()) {
      declared.add(new String[] { m.group(1), m.group(2) });
    }
    assertTrue("no SimpleOption declarations parsed", declared.size() > 10);
    return declared;
  }

  /* Guards the whole table, not just the options issue #100 happened to name. */
  @Test
  public void onlyTheFloatOptionIsDeclaredToTakeAFraction() throws IOException {
    for (final String[] declared : declaredOptions()) {
      final String option = declared[0];
      final boolean fraction = "true".equals(declared[1]);
      assertEquals("-" + option + " fraction flag", FLOAT_OPTION.equals(option),
          fraction);
      assertEquals("-" + option + " with a fraction", fraction,
          !emit(new SimpleOption(option, fraction), "1.5").isEmpty());
    }
  }

  @Test
  public void aDecimalIsDroppedFromAnOptionTheEngineParsesAsAnInteger() {
    for (final String option : new String[] { "r", "%e", "M", "E", "A", "#L",
        "c", "T", "R", "J", "u", "s" }) {
      assertTrue("-" + option + " kept a decimal",
          emit(new SimpleOption(option), "1.5").isEmpty());
    }
  }

  /* -%e is the trap: it carries a '%' but the engine scans it with %d, and the
     leftover '.' then aborts the whole run with "invalid option .". */
  @Test
  public void aDecimalSurvivesOnTheFloatOptionOnly() {
    assertEquals(Arrays.asList("-%c1.5"),
        emit(new SimpleOption("%c", true), "1.5"));
    assertTrue(emit(new SimpleOption("%e"), "2.5").isEmpty());
  }

  @Test
  public void anIntegerStillEmits() {
    assertEquals(Arrays.asList("-r5"), emit(new SimpleOption("r"), "5"));
    assertEquals(Arrays.asList("-%e2"), emit(new SimpleOption("%e"), "2"));
    assertEquals(Arrays.asList("-#L500"), emit(new SimpleOption("#L"), "500"));
    assertEquals(Arrays.asList("-%c8"),
        emit(new SimpleOption("%c", true), "8"));
  }

  /** The engine takes a signed value and quietly means something else by it. */
  @Test
  public void aSignedValueIsDropped() {
    assertTrue(emit(new SimpleOption("r"), "-1").isEmpty());
    assertTrue(emit(new SimpleOption("%c", true), "-1.5").isEmpty());
  }

  @Test
  public void aMalformedValueIsDropped() {
    for (final String value : new String[] { "5x", "1.", ".5", "1.2.3", " 5",
        "5 ", "1e3", "0x10", "", null }) {
      assertTrue("-r kept " + value, emit(new SimpleOption("r"), value)
          .isEmpty());
      assertTrue("-%c kept " + value,
          emit(new SimpleOption("%c", true), value).isEmpty());
    }
  }

  @Test
  public void isDecimalTakesAFractionAndIsDigitsDoesNot() {
    assertTrue(OptionValues.isDecimal("1.5"));
    assertTrue(OptionValues.isDecimal("15"));
    assertFalse(OptionValues.isDigits("1.5"));
    assertFalse(OptionValues.isDecimal(null));
  }
}
