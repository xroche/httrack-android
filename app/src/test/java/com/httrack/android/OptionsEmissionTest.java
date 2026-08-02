package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.ArgumentOption;
import com.httrack.android.OptionsMapper.OptionMapper;
import com.httrack.android.OptionsMapper.ProxyHandler;
import com.httrack.android.OptionsMapper.SimpleOptionFlag;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Guards the argv emitted for the engine options exposed by issue #6. */
public class OptionsEmissionTest {
  /* Drive a proxy handler through its mappers and return the emitted argv. */
  private static List<String> emitProxy(final String protocol,
      final String address, final String port) {
    final ProxyHandler handler = new ProxyHandler();
    final StringBuilder flags = new StringBuilder();
    final List<String> cmd = new ArrayList<String>();
    handler.getProtocolMapper().emit(flags, cmd, protocol);
    handler.getAddressMapper().emit(flags, cmd, address);
    final OptionMapper portMapper = handler.getPortMapper();
    portMapper.emit(flags, cmd, port);
    ((OptionMapper.FinishMapper) portMapper).finish(flags, cmd);
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
    new ArgumentOption("-%K").emit(new StringBuilder(), cmd, "cookies.txt");
    assertEquals("-%K", cmd.get(0));
    assertEquals("cookies.txt", cmd.get(1));
  }

  @Test
  public void valueOptionStaysSilentWhenEmpty() {
    final List<String> cmd = new ArrayList<String>();
    new ArgumentOption("-%K").emit(new StringBuilder(), cmd, "");
    assertTrue(cmd.isEmpty());
  }

  /* keep-* toggles emit their %-flag as a standalone token. */
  @Test
  public void keepToggleEmitsFlagWhenChecked() {
    final List<String> cmd = new ArrayList<String>();
    new SimpleOptionFlag("%j").emit(new StringBuilder("-"), cmd, "1");
    assertEquals("-%j", cmd.get(0));
  }

  @Test
  public void keepToggleEmitsNothingWhenUnchecked() {
    final List<String> cmd = new ArrayList<String>();
    new SimpleOptionFlag("%j").emit(new StringBuilder("-"), cmd, "0");
    assertTrue(cmd.isEmpty());
  }

  /* WARC emits -%r only when checked. */
  @Test
  public void warcToggleEmitsFlagWhenChecked() {
    final List<String> cmd = new ArrayList<String>();
    new SimpleOptionFlag("%r").emit(new StringBuilder("-"), cmd, "1");
    assertEquals("-%r", cmd.get(0));
  }

  @Test
  public void warcToggleEmitsNothingWhenUnchecked() {
    final List<String> cmd = new ArrayList<String>();
    new SimpleOptionFlag("%r").emit(new StringBuilder("-"), cmd, "0");
    assertTrue(cmd.isEmpty());
  }

  /* sitemap/single-file/changes keep the short forms the engine aliases them to. */
  @Test
  public void spiderTogglesEmitTheirEngineFlag() {
    final String[][] cases = { { "%m", "-%m" }, { "%Z", "-%Z" },
        { "%d", "-%d" } };
    for (final String[] c : cases) {
      final StringBuilder flags = new StringBuilder("-");
      final List<String> cmd = new ArrayList<String>();
      new SimpleOptionFlag(c[0]).emit(flags, cmd, "1");
      assertEquals(1, cmd.size());
      assertEquals(c[1], cmd.get(0));
      assertEquals("-", flags.toString());
    }
  }

  @Test
  public void spiderToggleStaysSilentWhenUncheckedOrUnset() {
    for (final String value : new String[] { "0", "2", "", null }) {
      final StringBuilder flags = new StringBuilder("-");
      final List<String> cmd = new ArrayList<String>();
      new SimpleOptionFlag("%Z").emit(flags, cmd, value);
      assertTrue(cmd.isEmpty());
      assertEquals("-", flags.toString());
    }
  }

  /* companions of the sitemap, single-file and WARC toggles. */
  private static final String[] COMPANIONS = { "--sitemap-url",
      "--single-file-max-size", "--warc-file" };

  @Test
  public void companionEmitsItsOwnTokenPairWhenSet() {
    for (final String option : COMPANIONS) {
      final StringBuilder flags = new StringBuilder("-");
      final List<String> cmd = new ArrayList<String>();
      new ArgumentOption(option).emit(flags, cmd, "value");
      assertEquals(2, cmd.size());
      assertEquals(option, cmd.get(0));
      assertEquals("value", cmd.get(1));
      assertEquals("-", flags.toString());
    }
  }

  /* an empty field emits nothing: a bare --opt would eat the next token. */
  @Test
  public void companionStaysSilentWhenEmptyOrUnset() {
    for (final String option : COMPANIONS) {
      for (final String value : new String[] { "", null }) {
        final StringBuilder flags = new StringBuilder("-");
        final List<String> cmd = new ArrayList<String>();
        new ArgumentOption(option).emit(flags, cmd, value);
        assertTrue(cmd.isEmpty());
        assertEquals("-", flags.toString());
      }
    }
  }

  /* the mappers are static singletons: emitting again must not go quiet. */
  @Test
  public void flagEmitsOnEveryBuild() {
    final OptionMapper mapper = new SimpleOptionFlag("%d");
    final List<String> first = new ArrayList<String>();
    final List<String> second = new ArrayList<String>();
    mapper.emit(new StringBuilder("-"), first, "1");
    mapper.emit(new StringBuilder("-"), second, "1");
    assertEquals(first, second);
    assertEquals("-%d", second.get(0));
  }
}
