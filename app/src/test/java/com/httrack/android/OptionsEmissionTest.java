package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.ArgumentOption;
import com.httrack.android.OptionsMapper.LongOptionFlag;
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

  /* keep-* toggles bundle their %-flag into the single dash token. */
  @Test
  public void keepToggleBundlesFlagWhenChecked() {
    final StringBuilder flags = new StringBuilder();
    new SimpleOptionFlag("%j").emit(flags, new ArrayList<String>(), "1");
    assertTrue(flags.toString().contains("%j"));
  }

  @Test
  public void keepToggleEmitsNothingWhenUnchecked() {
    final StringBuilder flags = new StringBuilder();
    new SimpleOptionFlag("%j").emit(flags, new ArrayList<String>(), "0");
    assertFalse(flags.toString().contains("%j"));
  }

  /* WARC toggle bundles -%r only when checked. */
  @Test
  public void warcToggleBundlesFlagWhenChecked() {
    final StringBuilder flags = new StringBuilder();
    new SimpleOptionFlag("%r").emit(flags, new ArrayList<String>(), "1");
    assertTrue(flags.toString().contains("%r"));
  }

  @Test
  public void warcToggleEmitsNothingWhenUnchecked() {
    final StringBuilder flags = new StringBuilder();
    new SimpleOptionFlag("%r").emit(flags, new ArrayList<String>(), "0");
    assertFalse(flags.toString().contains("%r"));
  }

  /* sitemap/single-file/changes: own token, and never in the packed string. */
  @Test
  public void longOptionEmitsItsOwnTokenWhenChecked() {
    for (final String option : new String[] { "--sitemap", "--single-file",
        "--changes" }) {
      /* seeded as buildCommandline() does, so a guarded append cannot hide */
      final StringBuilder flags = new StringBuilder("-");
      final List<String> cmd = new ArrayList<String>();
      new LongOptionFlag(option).emit(flags, cmd, "1");
      assertEquals(1, cmd.size());
      assertEquals(option, cmd.get(0));
      assertEquals("-", flags.toString());
    }
  }

  @Test
  public void longOptionStaysSilentWhenUncheckedOrUnset() {
    for (final String value : new String[] { "0", "", null }) {
      final StringBuilder flags = new StringBuilder("-");
      final List<String> cmd = new ArrayList<String>();
      new LongOptionFlag("--single-file").emit(flags, cmd, value);
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
  public void longOptionEmitsOnEveryBuild() {
    final OptionMapper mapper = new LongOptionFlag("--changes");
    final List<String> first = new ArrayList<String>();
    final List<String> second = new ArrayList<String>();
    mapper.emit(new StringBuilder("-"), first, "1");
    mapper.emit(new StringBuilder("-"), second, "1");
    assertEquals(first, second);
    assertEquals("--changes", second.get(0));
  }
}
