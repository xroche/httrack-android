package com.httrack.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.MultipleChoicesOption;
import com.httrack.android.OptionsMapper.OptionMapper;
import com.httrack.android.OptionsMapper.ProfileFormat;
import com.httrack.android.OptionsMapper.SimpleOptionFlag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Checks winprofile.ini storage and option emission against WinHTTrack's conventions. */
public class WinProfileParityTest {
  private static List<String> emit(final OptionMapper mapper, final String value) {
    final List<String> cmd = new ArrayList<String>();
    mapper.emit(cmd, value);
    return cmd;
  }

  /* A profile file, in the order its lines appear. */
  private static Map<String, String> file(final String... pairs) {
    final Map<String, String> raw = new LinkedHashMap<String, String>();
    for (int i = 0; i < pairs.length; i += 2) {
      raw.put(pairs[i], pairs[i + 1]);
    }
    return raw;
  }

  private static String[] boxes(final Map<String, String> values) {
    return new String[] { values.get("Dos"), values.get("Iso9660") };
  }

  /* -x turns external links into error pages, and the engine defaults it off. */
  @Test
  public void noExternalPagesEmitsWhenChecked() {
    assertEquals(Arrays.asList("-x"), emit(new SimpleOptionFlag("x"), "1"));
    assertTrue(emit(new SimpleOptionFlag("x"), "0").isEmpty());
  }

  /* -%q includes query strings and -%q0 drops them; the box hides them. */
  @Test
  public void hideQueryStringsEmitsTheDisablingForm() {
    assertEquals(Arrays.asList("-%q0"), emit(new SimpleOptionFlag("%q0"), "1"));
    assertTrue(emit(new SimpleOptionFlag("%q0"), "0").isEmpty());
  }

  /* Bare -j re-asserts the engine default, so unticking the box needs -j0. */
  @Test
  public void parseJavaTurnsOffWithTheZeroForm() {
    assertEquals(Arrays.asList("-j0"),
        emit(new SimpleOptionFlag("j0", true), "0"));
    assertTrue(emit(new SimpleOptionFlag("j0", true), "1").isEmpty());
  }

  /* Checked must revalidate, which is -C2; the engine's own default is -C1. */
  @Test
  public void cacheChecksForUpdatesWhenChecked() {
    final OptionMapper cache = new MultipleChoicesOption(new String[] { "C0",
        "C2" });
    assertEquals(Arrays.asList("-C2"), emit(cache, "1"));
    assertEquals(Arrays.asList("-C0"), emit(cache, "0"));
  }

  @Test
  public void winHttrackProfileOpensWithBothBoxes() {
    assertArrayEquals(new String[] { "0", "0" },
        boxes(ProfileFormat.resolve(file("Dos", "0"))));
    assertArrayEquals(new String[] { "1", "0" },
        boxes(ProfileFormat.resolve(file("Dos", "1"))));
    assertArrayEquals(new String[] { "0", "1" },
        boxes(ProfileFormat.resolve(file("Dos", "2"))));
    assertArrayEquals(new String[] { "1", "1" },
        boxes(ProfileFormat.resolve(file("Dos", "3"))));
  }

  /* Our own older profiles wrote Dos as a plain boolean beside an Iso9660. */
  @Test
  public void olderProfileKeepsEveryRenamedSetting() {
    final Map<String, String> values = ProfileFormat.resolve(file("Dos", "1",
        "Iso9660", "1", "ProxyProtocol", "1", "KeepWwwPrefix", "1",
        "KeepDoubleSlashes", "1"));
    assertArrayEquals(new String[] { "1", "1" }, boxes(values));
    assertEquals("1", values.get("ProxyType"));
    assertEquals("1", values.get("KeepWww"));
    assertEquals("1", values.get("KeepSlashes"));
    assertFalse(values.containsKey("ProxyProtocol"));
  }

  /* The split must read the file, not the map it is filling: a profile with no
     Dos line would otherwise clear a saved Iso9660. */
  @Test
  public void profileWithoutDosLeavesBothBoxesAlone() {
    final Map<String, String> values = ProfileFormat.resolve(file("ProxyType",
        "1"));
    assertFalse(values.containsKey("Dos"));
    assertFalse(values.containsKey("Iso9660"));
  }

  @Test
  public void unreadableDosLeavesTheBoxesAlone() {
    assertEquals("yes", ProfileFormat.resolve(file("Dos", "yes")).get("Dos"));
    assertNull(ProfileFormat.resolve(file("Dos", "yes")).get("Iso9660"));
    assertEquals("", ProfileFormat.resolve(file("Dos", "")).get("Dos"));
  }

  @Test
  public void dosPacksBothBoxes() {
    assertEquals("0", ProfileFormat.pack("0", "0"));
    assertEquals("1", ProfileFormat.pack("1", "0"));
    assertEquals("2", ProfileFormat.pack("0", "1"));
    assertEquals("3", ProfileFormat.pack("1", "1"));
    assertEquals("0", ProfileFormat.pack(null, null));
  }

  /* WinHTTrack has no Iso9660 key, so ours must not survive into the file. */
  @Test
  public void writtenProfileFoldsIso9660IntoDos() {
    final Map<String, String> written = ProfileFormat.toFile(file("Dos", "0",
        "Iso9660", "1", "ProxyType", "1"));
    assertEquals("2", written.get("Dos"));
    assertFalse(written.containsKey("Iso9660"));
    assertEquals("1", written.get("ProxyType"));
  }

  @Test
  public void everyBoxCombinationSurvivesAWriteAndRead() {
    for (final String dos : new String[] { "0", "1" }) {
      for (final String iso9660 : new String[] { "0", "1" }) {
        final Map<String, String> values = ProfileFormat.resolve(ProfileFormat
            .toFile(file("Dos", dos, "Iso9660", iso9660)));
        assertArrayEquals(dos + "/" + iso9660,
            new String[] { dos, iso9660 }, boxes(values));
      }
    }
  }

  @Test
  public void renamedKeysMapBothWays() {
    assertEquals("ProxyType", ProfileFormat.canonicalName("ProxyProtocol"));
    assertEquals("ProxyProtocol", ProfileFormat.legacyName("ProxyType"));
    assertEquals("KeepWww", ProfileFormat.canonicalName("KeepWwwPrefix"));
    assertEquals("KeepSlashes",
        ProfileFormat.canonicalName("KeepDoubleSlashes"));
    assertEquals("Near", ProfileFormat.canonicalName("Near"));
    assertNull(ProfileFormat.legacyName("Near"));
  }

  /* The table pulls in R.id, so the declarations are read out of the source. */
  private static String mapperTable() throws IOException {
    return TestSources.javaSource("OptionsMapper");
  }

  @Test
  public void keysUseTheWinHttrackSpelling() throws IOException {
    final Matcher m = Pattern.compile(
        "new Pair<Integer, String>\\(R\\.id\\.\\w+,\\s*\"([^\"]+)\"\\)")
        .matcher(mapperTable());
    final List<String> keys = new ArrayList<String>();
    while (m.find()) {
      keys.add(m.group(1));
    }
    assertEquals("serializer keys parsed", 94, keys.size());
    assertTrue(keys.containsAll(Arrays.asList("ProxyType", "KeepWww",
        "KeepSlashes")));
    for (final String legacy : new String[] { "ProxyProtocol",
        "KeepWwwPrefix", "KeepDoubleSlashes" }) {
      assertFalse(legacy + " still written", keys.contains(legacy));
    }
  }

  /* No flag may re-assert an engine default to mean "off", whichever primitive
     spells it: the -%q bug wore SimpleOption0 rather than a reverted flag. */
  @Test
  public void noOffSwitchEmitsABareEnablingForm() throws IOException {
    final Matcher m = Pattern.compile(
        "new (SimpleOptionFlag|SimpleOption0)\\(\\s*\"([^\"]+)\""
            + "(?:\\s*,\\s*(?:/\\*[^*]*\\*/\\s*)?(true|false))?\\s*\\)")
        .matcher(mapperTable());
    int reverted = 0;
    int tristate = 0;
    while (m.find()) {
      final boolean isFlag = "SimpleOptionFlag".equals(m.group(1));
      if (isFlag && "true".equals(m.group(3))) {
        reverted++;
        assertTrue("-" + m.group(2) + " must carry its 0 form", m.group(2)
            .endsWith("0"));
      } else if (!isFlag) {
        tristate++;
        assertFalse("-" + m.group(2) + " emits both forms, so it may not be "
            + "spelled as its own off switch", m.group(2).endsWith("0"));
      }
    }
    assertEquals("reverted flags parsed", 3, reverted);
    assertEquals("tri-state options parsed", 4, tristate);
  }
}
