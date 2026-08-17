package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.DosIso9660Handler;
import com.httrack.android.OptionsMapper.OptionMapper;
import com.httrack.android.OptionsMapper.SimpleOptionFlag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Holds winprofile.ini storage and option emission to WinHTTrack's conventions. */
public class WinProfileParityTest {
  private static List<String> emit(final OptionMapper mapper, final String value) {
    final List<String> cmd = new ArrayList<String>();
    mapper.emit(cmd, value);
    return cmd;
  }

  /* The mapper table pulls in R.id, which the stub android.jar cannot load, so
     the declarations are read out of the source. */
  private static Map<String, String> mapperTable() throws IOException {
    final String src = TestSources.javaSource("OptionsMapper");
    final Matcher m = Pattern.compile(
        "new Pair<String, OptionMapper>\\(\"([^\"]+)\",\\s*"
            + "(new \\w+\\([^)]*\\)|[\\w.]+\\(\\))").matcher(src);
    final Map<String, String> table = new HashMap<String, String>();
    while (m.find()) {
      table.put(m.group(1), m.group(2).replaceAll("\\s+", " "));
    }
    assertTrue("no mapper declarations parsed", table.size() > 50);
    return table;
  }

  private static List<String> serializerKeys() throws IOException {
    final String src = TestSources.javaSource("OptionsMapper");
    final Matcher m = Pattern.compile(
        "new Pair<Integer, String>\\(R\\.id\\.\\w+,\\s*\"([^\"]+)\"\\)")
        .matcher(src);
    final List<String> keys = new ArrayList<String>();
    while (m.find()) {
      keys.add(m.group(1));
    }
    assertTrue("no serializer keys parsed", keys.size() > 50);
    return keys;
  }

  /* -x turns external links into error pages, and the engine defaults it off. */
  @Test
  public void noExternalPagesEmitsWhenChecked() throws IOException {
    assertEquals("new SimpleOptionFlag( \"x\")",
        mapperTable().get("NoExternalPages"));
    assertEquals(Arrays.asList("-x"), emit(new SimpleOptionFlag("x"), "1"));
    assertTrue(emit(new SimpleOptionFlag("x"), "0").isEmpty());
  }

  /* -%q includes query strings and -%q0 drops them; the box hides them. */
  @Test
  public void hideQueryStringsEmitsTheDisablingForm() throws IOException {
    assertEquals("new SimpleOptionFlag( \"%q0\")",
        mapperTable().get("NoQueryStrings"));
    assertEquals(Arrays.asList("-%q0"), emit(new SimpleOptionFlag("%q0"), "1"));
    assertTrue(emit(new SimpleOptionFlag("%q0"), "0").isEmpty());
  }

  /* Bare -j re-asserts the engine default, so unticking the box needs -j0. */
  @Test
  public void parseJavaTurnsOffWithTheZeroForm() throws IOException {
    assertEquals("new SimpleOptionFlag(\"j0\", true)",
        mapperTable().get("ParseJava"));
    assertEquals(Arrays.asList("-j0"),
        emit(new SimpleOptionFlag("j0", true), "0"));
    assertTrue(emit(new SimpleOptionFlag("j0", true), "1").isEmpty());
  }

  /* No flag body may re-assert an engine default to express "off". */
  @Test
  public void noRevertedFlagEmitsABareEnablingForm() throws IOException {
    final Matcher m = Pattern.compile(
        "new SimpleOptionFlag\\(\\s*\"([^\"]+)\",\\s*true\\s*\\)").matcher(
        TestSources.javaSource("OptionsMapper"));
    int seen = 0;
    while (m.find()) {
      seen++;
      assertTrue("-" + m.group(1) + " must carry its 0 form", m.group(1)
          .endsWith("0"));
    }
    assertTrue("no reverted flags parsed", seen > 0);
  }

  @Test
  public void keysUseTheWinHttrackSpelling() throws IOException {
    final List<String> keys = serializerKeys();
    for (final String key : new String[] { "ProxyType", "KeepWww",
        "KeepSlashes" }) {
      assertTrue(key + " missing", keys.contains(key));
    }
    for (final String legacy : new String[] { "ProxyProtocol",
        "KeepWwwPrefix", "KeepDoubleSlashes" }) {
      assertFalse(legacy + " still written", keys.contains(legacy));
    }
  }

  /* Renaming a key would silently reset it on every profile already saved. */
  @Test
  public void everyRenamedKeyKeepsItsOldSpellingReadable() throws IOException {
    final String src = TestSources.javaSource("OptionsMapper");
    final Matcher m = Pattern.compile(
        "new Pair<String, String>\\(\"(ProxyProtocol|KeepWwwPrefix"
            + "|KeepDoubleSlashes)\",\\s*\"(\\w+)\"\\)").matcher(src);
    final Map<String, String> aliases = new HashMap<String, String>();
    while (m.find()) {
      aliases.put(m.group(1), m.group(2));
    }
    assertEquals("ProxyType", aliases.get("ProxyProtocol"));
    assertEquals("KeepWww", aliases.get("KeepWwwPrefix"));
    assertEquals("KeepSlashes", aliases.get("KeepDoubleSlashes"));
  }

  /* Dos is a two-bit field on the desktop, not a checkbox. */
  @Test
  public void dosPacksBothBoxes() {
    assertEquals("0", DosIso9660Handler.pack("0", "0"));
    assertEquals("1", DosIso9660Handler.pack("1", "0"));
    assertEquals("2", DosIso9660Handler.pack("0", "1"));
    assertEquals("3", DosIso9660Handler.pack("1", "1"));
    assertEquals("0", DosIso9660Handler.pack(null, null));
  }

  @Test
  public void dosUnpacksBothBoxes() {
    assertArrayEquals(new String[] { "0", "0" }, DosIso9660Handler.unpack("0"));
    assertArrayEquals(new String[] { "1", "0" }, DosIso9660Handler.unpack("1"));
    assertArrayEquals(new String[] { "0", "1" }, DosIso9660Handler.unpack("2"));
    assertArrayEquals(new String[] { "1", "1" }, DosIso9660Handler.unpack("3"));
  }

  /* A WinHTTrack project with both boxes ticked used to open with neither. */
  @Test
  public void dosRoundTripsEveryCombination() {
    for (final String dos : new String[] { "0", "1" }) {
      for (final String iso9660 : new String[] { "0", "1" }) {
        assertArrayEquals(dos + "/" + iso9660, new String[] { dos, iso9660 },
            DosIso9660Handler.unpack(DosIso9660Handler.pack(dos, iso9660)));
      }
    }
  }

  @Test
  public void dosLeavesTheBoxesAloneWhenItHoldsNoNumber() {
    assertNull(DosIso9660Handler.unpack(null));
    assertNull(DosIso9660Handler.unpack(""));
    assertNull(DosIso9660Handler.unpack("yes"));
  }

  /* Bit 0 wins when both are set, matching the -L0 the desktop emits. */
  @Test
  public void bothBoxesEmitDosNames() {
    final DosIso9660Handler handler = new DosIso9660Handler();
    final List<String> cmd = new ArrayList<String>();
    final String[] boxes = DosIso9660Handler.unpack("3");
    handler.getDosMapper().emit(cmd, boxes[0]);
    final OptionMapper iso = handler.getIso9660Mapper();
    iso.emit(cmd, boxes[1]);
    ((OptionMapper.FinishMapper) iso).finish(cmd);
    assertEquals(Arrays.asList("-L0"), cmd);
  }
}
