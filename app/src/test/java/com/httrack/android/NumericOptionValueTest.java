package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.OptionMapper;
import com.httrack.android.OptionsMapper.SimpleOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * A value loaded from a saved profile never goes through the keyboard filters,
 * so the mapper is where the engine-hostile ones get dropped (issue #100).
 */
public class NumericOptionValueTest {
  private static List<String> emit(final OptionMapper mapper, final String value) {
    final List<String> cmd = new ArrayList<String>();
    mapper.emit(cmd, value);
    return cmd;
  }

  @Test
  public void aDecimalIsDroppedFromAnOptionTheEngineParsesAsAnInteger() {
    for (final String option : new String[] { "r", "M", "E", "A", "#L", "c",
        "T", "R", "J" }) {
      assertTrue("-" + option + " kept a decimal",
          emit(new SimpleOption(option), "1.5").isEmpty());
    }
  }

  @Test
  public void aDecimalSurvivesOnTheOptionsParsedWithAFloat() {
    assertEquals(Arrays.asList("-%c1.5"), emit(new SimpleOption("%c"), "1.5"));
    assertEquals(Arrays.asList("-%e2.5"), emit(new SimpleOption("%e"), "2.5"));
  }

  @Test
  public void anIntegerStillEmits() {
    assertEquals(Arrays.asList("-r5"), emit(new SimpleOption("r"), "5"));
    assertEquals(Arrays.asList("-%c8"), emit(new SimpleOption("%c"), "8"));
    assertEquals(Arrays.asList("-#L500"), emit(new SimpleOption("#L"), "500"));
  }

  /** A leading '-' makes the engine reject the whole commandline. */
  @Test
  public void aSignedValueIsDropped() {
    assertTrue(emit(new SimpleOption("r"), "-1").isEmpty());
    assertTrue(emit(new SimpleOption("%c"), "-1.5").isEmpty());
  }

  @Test
  public void aMalformedValueIsDropped() {
    for (final String value : new String[] { "5x", "1.", ".5", "1.2.3", " 5",
        "5 ", "1e3", "0x10", "", null }) {
      assertTrue("-r kept " + value, emit(new SimpleOption("r"), value)
          .isEmpty());
      assertTrue("-%c kept " + value, emit(new SimpleOption("%c"), value)
          .isEmpty());
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
