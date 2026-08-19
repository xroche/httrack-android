package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.ArgumentOption;
import com.httrack.android.OptionsMapper.GatedFeatureHandler;
import com.httrack.android.OptionsMapper.LogHandler;
import com.httrack.android.OptionsMapper.MultipleChoicesOption;
import com.httrack.android.OptionsMapper.OptionMapper;
import com.httrack.android.OptionsMapper.PrimaryScanHandler;
import com.httrack.android.OptionsMapper.ProxyHandler;
import com.httrack.android.OptionsMapper.SimpleOptionFlag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Guards the argv emitted for the engine options exposed by issue #6. */
public class OptionsEmissionTest {
  /* Drive a proxy handler through its mappers and return the emitted argv. */
  private static List<String> emitProxy(final String protocol,
      final String address, final String port) {
    final ProxyHandler handler = new ProxyHandler();
    final List<String> cmd = new ArrayList<String>();
    handler.getProtocolMapper().emit(cmd, protocol);
    handler.getAddressMapper().emit(cmd, address);
    final OptionMapper portMapper = handler.getPortMapper();
    portMapper.emit(cmd, port);
    ((OptionMapper.FinishMapper) portMapper).finish(cmd);
    return cmd;
  }

  @Test
  public void socks5PrependsSchemeAndDefaultsPort1080() {
    final List<String> cmd = emitProxy("1", "proxy.example", "");
    assertEquals("-P", cmd.get(0));
    assertEquals("socks5://proxy.example:1080", cmd.get(1));
  }

  @Test
  public void socks5KeepsAnExplicitPort() {
    assertEquals("socks5://proxy.example:9050",
        emitProxy("1", "proxy.example", "9050").get(1));
  }

  @Test
  public void httpProxyStaysBareAndDefaultsPort8080() {
    assertEquals("proxy.example:8080", emitProxy("0", "proxy.example", "").get(1));
    assertEquals("proxy.example:3128",
        emitProxy("0", "proxy.example", "3128").get(1));
  }

  @Test
  public void noProxyAddressEmitsNothing() {
    assertTrue(emitProxy("1", "", "").isEmpty());
  }

  /* -%K/-%G/-%g emit two tokens only when set; empty stays silent. */
  @Test
  public void valueOptionEmitsFlagAndArgumentWhenSet() {
    final List<String> cmd = new ArrayList<String>();
    new ArgumentOption("-%K").emit(cmd, "cookies.txt");
    assertEquals("-%K", cmd.get(0));
    assertEquals("cookies.txt", cmd.get(1));
  }

  @Test
  public void valueOptionStaysSilentWhenEmpty() {
    final List<String> cmd = new ArrayList<String>();
    new ArgumentOption("-%K").emit(cmd, "");
    assertTrue(cmd.isEmpty());
  }

  /* keep-* toggles emit their %-flag as a standalone token. */
  @Test
  public void keepToggleEmitsFlagWhenChecked() {
    final List<String> cmd = new ArrayList<String>();
    new SimpleOptionFlag("%j").emit(cmd, "1");
    assertEquals("-%j", cmd.get(0));
  }

  @Test
  public void keepToggleEmitsNothingWhenUnchecked() {
    final List<String> cmd = new ArrayList<String>();
    new SimpleOptionFlag("%j").emit(cmd, "0");
    assertTrue(cmd.isEmpty());
  }

  /* WARC emits -%r only when checked. */
  @Test
  public void warcToggleEmitsFlagWhenChecked() {
    final List<String> cmd = new ArrayList<String>();
    new SimpleOptionFlag("%r").emit(cmd, "1");
    assertEquals("-%r", cmd.get(0));
  }

  @Test
  public void warcToggleEmitsNothingWhenUnchecked() {
    final List<String> cmd = new ArrayList<String>();
    new SimpleOptionFlag("%r").emit(cmd, "0");
    assertTrue(cmd.isEmpty());
  }

  /* sitemap/single-file/changes keep the short forms the engine aliases them to. */
  @Test
  public void spiderTogglesEmitTheirEngineFlag() {
    final String[][] cases = { { "%m", "-%m" }, { "%Z", "-%Z" },
        { "%d", "-%d" } };
    for (final String[] c : cases) {
      final List<String> cmd = new ArrayList<String>();
      new SimpleOptionFlag(c[0]).emit(cmd, "1");
      assertEquals(1, cmd.size());
      assertEquals(c[1], cmd.get(0));
    }
  }

  @Test
  public void spiderToggleStaysSilentWhenUncheckedOrUnset() {
    for (final String value : new String[] { "0", "2", "", null }) {
      final List<String> cmd = new ArrayList<String>();
      new SimpleOptionFlag("%Z").emit(cmd, value);
      assertTrue(cmd.isEmpty());
    }
  }

  /* companions of the sitemap, single-file and WARC toggles. */
  private static final String[] COMPANIONS = { "--sitemap-url",
      "--single-file-max-size", "--warc-file" };

  @Test
  public void companionEmitsItsOwnTokenPairWhenSet() {
    for (final String option : COMPANIONS) {
      final List<String> cmd = new ArrayList<String>();
      new ArgumentOption(option).emit(cmd, "value");
      assertEquals(2, cmd.size());
      assertEquals(option, cmd.get(0));
      assertEquals("value", cmd.get(1));
    }
  }

  /* an empty field emits nothing: a bare --opt would eat the next token. */
  @Test
  public void companionStaysSilentWhenEmptyOrUnset() {
    for (final String option : COMPANIONS) {
      for (final String value : new String[] { "", null }) {
        final List<String> cmd = new ArrayList<String>();
        new ArgumentOption(option).emit(cmd, value);
        assertTrue(cmd.isEmpty());
      }
    }
  }

  /* the mappers are static singletons: emitting again must not go quiet. */
  @Test
  public void flagEmitsOnEveryBuild() {
    final OptionMapper mapper = new SimpleOptionFlag("%d");
    final List<String> first = new ArrayList<String>();
    final List<String> second = new ArrayList<String>();
    mapper.emit(first, "1");
    mapper.emit(second, "1");
    assertEquals(first, second);
    assertEquals("-%d", second.get(0));
  }

  /* Neither field may decide alone, so emit() has to stay silent: a mapper the
     user left unset is skipped entirely, and would finish on partial state. */
  private static List<String> emitLog(final String enabled, final String type,
      final boolean typeFirst) {
    final LogHandler handler = new LogHandler();
    final OptionMapper enabledMapper = handler.getEnabledMapper();
    final OptionMapper typeMapper = handler.getTypeMapper();
    final List<String> cmd = new ArrayList<String>();
    if (typeFirst) {
      typeMapper.emit(cmd, type);
      enabledMapper.emit(cmd, enabled);
    } else {
      enabledMapper.emit(cmd, enabled);
      typeMapper.emit(cmd, type);
    }
    assertTrue("emit() must not emit; finish() does", cmd.isEmpty());
    ((OptionMapper.FinishMapper) typeMapper).finish(cmd);
    return cmd;
  }

  /* Verbosity radio: quiet, -z, -Z; unticked logging is -Q whatever the radio. */
  @Test
  public void logVerbosityIsItsOwnToken() {
    for (final boolean typeFirst : new boolean[] { false, true }) {
      assertTrue(emitLog("1", "0", typeFirst).isEmpty());
      assertEquals(Arrays.asList("-z"), emitLog("1", "1", typeFirst));
      assertEquals(Arrays.asList("-Z"), emitLog("1", "2", typeFirst));
      assertEquals(Arrays.asList("-Q"), emitLog("0", "0", typeFirst));
      assertEquals(Arrays.asList("-Q"), emitLog("0", "2", typeFirst));
    }
  }

  @Test
  public void logEmitsOnce() {
    final LogHandler handler = new LogHandler();
    final OptionMapper typeMapper = handler.getTypeMapper();
    final List<String> cmd = new ArrayList<String>();
    handler.getEnabledMapper().emit(cmd, "1");
    typeMapper.emit(cmd, "2");
    ((OptionMapper.FinishMapper) typeMapper).finish(cmd);
    ((OptionMapper.FinishMapper) handler.getEnabledMapper()).finish(cmd);
    assertEquals(Arrays.asList("-Z"), cmd);
  }

  private static List<String> emitPrimaryScan(final String type,
      final String htmlFirst, final boolean typeFirst) {
    final PrimaryScanHandler handler = new PrimaryScanHandler();
    final OptionMapper htmlFirstMapper = handler.getHtmlFirstMapper();
    final OptionMapper typeMapper = handler.getTypeMapper();
    final List<String> cmd = new ArrayList<String>();
    if (typeFirst) {
      typeMapper.emit(cmd, type);
      htmlFirstMapper.emit(cmd, htmlFirst);
    } else {
      htmlFirstMapper.emit(cmd, htmlFirst);
      typeMapper.emit(cmd, type);
    }
    assertTrue("emit() must not emit; finish() does", cmd.isEmpty());
    ((OptionMapper.FinishMapper) typeMapper).finish(cmd);
    return cmd;
  }

  /* Scan radio 0..2 map straight to -pN; 3 and 4 fold "html first" into -p7. */
  @Test
  public void primaryScanModeIsItsOwnToken() {
    for (final boolean typeFirst : new boolean[] { false, true }) {
      assertEquals(Arrays.asList("-p0"), emitPrimaryScan("0", "0", typeFirst));
      assertEquals(Arrays.asList("-p1"), emitPrimaryScan("1", "0", typeFirst));
      assertEquals(Arrays.asList("-p2"), emitPrimaryScan("2", "1", typeFirst));
      assertEquals(Arrays.asList("-p3"), emitPrimaryScan("3", "0", typeFirst));
      assertEquals(Arrays.asList("-p7"), emitPrimaryScan("3", "1", typeFirst));
      assertEquals(Arrays.asList("-p7"), emitPrimaryScan("4", "0", typeFirst));
      assertTrue(emitPrimaryScan("5", "0", typeFirst).isEmpty());
    }
  }

  @Test
  public void primaryScanEmitsOnce() {
    final PrimaryScanHandler handler = new PrimaryScanHandler();
    final OptionMapper typeMapper = handler.getTypeMapper();
    final List<String> cmd = new ArrayList<String>();
    typeMapper.emit(cmd, "1");
    ((OptionMapper.FinishMapper) typeMapper).finish(cmd);
    ((OptionMapper.FinishMapper) handler.getHtmlFirstMapper()).finish(cmd);
    assertEquals(Arrays.asList("-p1"), cmd);
  }

  private static List<String> emitChoice(final MultipleChoicesOption mapper,
      final String value) {
    final List<String> cmd = new ArrayList<String>();
    mapper.emit(cmd, value);
    return cmd;
  }

  /* Pins the shipped radio tables, not a copy of them. */
  @Test
  public void radioChoiceIsItsOwnToken() {
    assertEquals(Arrays.asList("-iC1"),
        emitChoice(MultipleChoicesOption.ACTION, "0"));
    assertEquals(Arrays.asList("-iC2"),
        emitChoice(MultipleChoicesOption.ACTION, "1"));
    final String[] travel = { "-S", "-D", "-U", "-B" };
    final String[] globalTravel = { "-a", "-d", "-l", "-e" };
    final String[] rewriteLinks = { "-K0", "-K", "-K3", "-K4" };
    for (int i = 0; i < 4; i++) {
      final String index = String.valueOf(i);
      assertEquals(Arrays.asList(travel[i]),
          emitChoice(MultipleChoicesOption.TRAVEL, index));
      assertEquals(Arrays.asList(globalTravel[i]),
          emitChoice(MultipleChoicesOption.GLOBAL_TRAVEL, index));
      assertEquals(Arrays.asList(rewriteLinks[i]),
          emitChoice(MultipleChoicesOption.REWRITE_LINKS, index));
    }
  }

  @Test
  public void radioChoiceStaysSilentOutOfRange() {
    for (final String value : new String[] { "4", "-1", "", null, "x" }) {
      assertTrue(emitChoice(MultipleChoicesOption.TRAVEL, value).isEmpty());
    }
  }

  private static List<String> emitRules(final String value) {
    final List<String> cmd = new ArrayList<String>();
    new OptionsMapper.RuleListOption("--host-alias").emit(cmd, value);
    return cmd;
  }

  @Test
  public void oneHostAliasFlagPerRule() {
    assertEquals(Arrays.asList("--host-alias", "www2.example.com=example.com",
        "--host-alias", "https://legacy.example.com=https://example.com"),
        emitRules("www2.example.com=example.com\n"
            + "https://legacy.example.com=https://example.com"));
  }

  @Test
  public void spacesAroundSeparatorsDoNotStartANewRule() {
    assertEquals(Arrays.asList("--host-alias", "a.com,b.com=c.com"),
        emitRules("a.com , b.com = c.com"));
    assertEquals(Arrays.asList("--host-alias", "a.com,b.com=c.com"),
        emitRules("a.com,\nb.com=\nc.com"));
  }

  @Test
  public void blankLinesAndAnEmptyFieldEmitNothing() {
    assertTrue(emitRules("").isEmpty());
    assertTrue(emitRules("  \n\t\n ").isEmpty());
    assertEquals(Arrays.asList("--host-alias", "a=b", "--host-alias", "c=d"),
        emitRules("\n\n a=b \n\n  c=d\n\n"));
  }

  private static int skipWhile(final String s, int i, final boolean ws) {
    while (i < s.length() && (" \t\r\n\013\f".indexOf(s.charAt(i)) >= 0) == ws) {
      i++;
    }
    return i;
  }

  /* cat_cmdline_arglist() from httrack#1166: the character loop WebHTTrack
     splits its own field with, and the regexp form has to agree with. */
  private static List<String> arglistOracle(final String value) {
    final List<String> out = new ArrayList<String>();
    int p = skipWhile(value, 0, true);
    while (p < value.length()) {
      final StringBuilder rule = new StringBuilder();
      boolean more = true;
      while (more) {
        final int end = skipWhile(value, p, false);
        final int next = skipWhile(value, end, true);
        rule.append(value, p, end);
        more = next < value.length()
            && (value.charAt(next) == ',' || value.charAt(next) == '=' || (end != p && (value
                .charAt(end - 1) == ',' || value.charAt(end - 1) == '=')));
        p = next;
      }
      out.add("--host-alias");
      out.add(rule.toString());
    }
    return out;
  }

  @Test
  public void ruleSplitAgreesWithTheEngineSplitter() {
    final char[] alphabet = { 'a', 'B', '.', '+', '-', ',', '=', ' ', '\t',
        '\n', '\013', '\f', '\001' };
    final Random random = new Random(20260811L);
    for (int i = 0; i < 20000; i++) {
      final StringBuilder value = new StringBuilder();
      for (int j = random.nextInt(80); j > 0; j--) {
        value.append(alphabet[random.nextInt(alphabet.length)]);
      }
      final String s = value.toString();
      assertEquals(s, arglistOracle(s), emitRules(s));
    }
  }

  /* fieldsSerializer fixes the emission order but pulls in R.id, which the stub
     android.jar cannot load, so read the declared order out of the source. */
  private static List<String> serializerKeys() throws IOException {
    final String src = new String(Files.readAllBytes(Paths
        .get("src/main/java/com/httrack/android/OptionsMapper.java")), "UTF-8");
    final int from = src.indexOf("fieldsSerializer[] = new Pair[] {");
    final int to = src.indexOf("\n  };", from);
    assertTrue("fieldsSerializer not found", from != -1 && to > from);
    final Matcher m = Pattern.compile(", \"(\\w+)\"\\)").matcher(
        src.substring(from, to));
    final List<String> keys = new ArrayList<String>();
    while (m.find()) {
      keys.add(m.group(1));
    }
    assertTrue("no keys parsed", keys.size() > 80);
    return keys;
  }

  /* The engine counts URLs positionally and -iC* carries its 'i': behind the
     URL it clears the count instead of leaving it at one, which switches the
     crawl onto the doit.log recovery path. */
  @Test
  public void currentActionIsEmittedBeforeTheUrl() throws IOException {
    final List<String> keys = serializerKeys();
    assertTrue("CurrentAction missing", keys.contains("CurrentAction"));
    assertTrue("CurrentUrl missing", keys.contains("CurrentUrl"));
    assertTrue("-iC* must precede the URL",
        keys.indexOf("CurrentAction") < keys.indexOf("CurrentUrl"));
  }

  @Test
  public void hostAliasIsWiredIntoTheProfile() throws IOException {
    assertTrue("HostAlias missing", serializerKeys().contains("HostAlias"));
  }

  private static List<String> emitGated(final String flag, final String option,
      final String ticked, final String value) {
    final GatedFeatureHandler handler = new GatedFeatureHandler(flag, option);
    final List<String> cmd = new ArrayList<String>();
    handler.getToggleMapper().emit(cmd, ticked);
    handler.getValueMapper().emit(cmd, value);
    return cmd;
  }

  /* The value switches the feature on by itself in the engine, so an unticked
     box has to withhold it or nothing ever turns the feature off. */
  @Test
  public void aGatedValueNeedsItsBoxTicked() {
    assertEquals(Arrays.asList("-%r", "--warc-file", "site.warc.gz"),
        emitGated("%r", "--warc-file", "1", "site.warc.gz"));
    assertTrue(emitGated("%r", "--warc-file", "0", "site.warc.gz").isEmpty());
    assertEquals(Arrays.asList("-%Z"),
        emitGated("%Z", "--single-file-max-size", "1", ""));
    assertTrue(emitGated("%Z", "--single-file-max-size", "0", "5000000")
        .isEmpty());
  }

  /* A second crawl must not inherit the first one's answer. */
  @Test
  public void aGatedValueRereadsItsBoxEachTime() {
    final GatedFeatureHandler handler = new GatedFeatureHandler("%m",
        "--sitemap-url");
    final List<String> first = new ArrayList<String>();
    handler.getToggleMapper().emit(first, "1");
    handler.getValueMapper().emit(first, "http://example.com/sitemap.xml");
    assertEquals(3, first.size());
    final List<String> second = new ArrayList<String>();
    handler.getToggleMapper().emit(second, "0");
    handler.getValueMapper().emit(second, "http://example.com/sitemap.xml");
    assertTrue(second.isEmpty());
  }

  /* Collapsing the blanks first turns every line into one, so "Other headers"
     reached the engine as a single malformed header. */
  @Test
  public void aLineSeparatedFieldKeepsOneValuePerLine() {
    assertEquals(Arrays.asList("X-One: 1", "X-Two: 2", "X-Three: 3"),
        CommandlineTokens.lineTokens("X-One: 1\n  X-Two: 2  \n\nX-Three: 3"));
    assertTrue(CommandlineTokens.lineTokens("  \n\n ").isEmpty());
  }

  /* -%M and single-file HTML are two ways to fold a page into one file, and
     the engine refuses to start on both. */
  @Test
  public void bothWaysToOneSelfContainedPageAreRefused() {
    assertTrue(CommandlineTokens.hasSelfContainedConflict(Arrays.asList("-%M",
        "-%Z")));
    assertTrue(CommandlineTokens.hasSelfContainedConflict(Arrays.asList("-%M",
        "--single-file-max-size", "5000000")));
    assertFalse(CommandlineTokens.hasSelfContainedConflict(Arrays.asList("-%M",
        "-%I")));
    assertFalse(CommandlineTokens.hasSelfContainedConflict(Arrays.asList("-%Z")));
  }
}
