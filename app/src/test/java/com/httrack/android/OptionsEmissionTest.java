package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
