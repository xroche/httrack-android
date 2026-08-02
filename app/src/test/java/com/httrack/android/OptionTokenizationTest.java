package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.BuildHandler;
import com.httrack.android.OptionsMapper.DosIso9660Handler;
import com.httrack.android.OptionsMapper.HostControlHandler;
import com.httrack.android.OptionsMapper.MaxSizeHandler;
import com.httrack.android.OptionsMapper.OptionMapper;
import com.httrack.android.OptionsMapper.SimpleOption;
import com.httrack.android.OptionsMapper.SimpleOption0;
import com.httrack.android.OptionsMapper.SimpleOptionFlag;
import com.httrack.android.OptionsMapper.UrlSplit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Each engine option must be its own argv token, never packed (issue #78). */
public class OptionTokenizationTest {
  /* Two-letter forms that swallow the next argv token (engine htsalias.c). */
  private static final String[] ARGUMENT_VARIANTS = { "%rf", "%rs", "%rc",
      "%rz", "%mu", "%Zs" };

  private static void assertNoArgumentVariant(final List<String> argv) {
    for (final String token : argv) {
      for (final String variant : ARGUMENT_VARIANTS) {
        assertFalse("token " + token + " spells " + variant,
            token.contains(variant));
      }
    }
  }

  private static List<String> emit(final OptionMapper mapper, final String value) {
    final List<String> cmd = new ArrayList<String>();
    mapper.emit(cmd, value);
    return cmd;
  }

  private static List<String> finish(final OptionMapper mapper) {
    final List<String> cmd = new ArrayList<String>();
    ((OptionMapper.FinishMapper) mapper).finish(cmd);
    return cmd;
  }

  /* Reported profile: WARC on, no CheckType to separate it from robots. */
  @Test
  public void warcWithoutCheckTypeKeepsTheCrawlUrl() {
    final List<String> cmd = new ArrayList<String>();
    UrlSplit.INSTANCE.emit(cmd, "http://www.example.com/");
    new SimpleOptionFlag("X0").emit(cmd, "1"); // NoPurgeOldFiles
    new SimpleOptionFlag("%r").emit(cmd, "1"); // Warc
    new SimpleOption("u").emit(cmd, ""); // CheckType, out of range
    new SimpleOption("s").emit(cmd, "2"); // FollowRobotsTxt
    new SimpleOptionFlag("%s").emit(cmd, "1"); // UpdateHack

    assertNoArgumentVariant(cmd);
    assertEquals(Arrays.asList("http://www.example.com/", "-X0", "-%r", "-s2",
        "-%s"), cmd);
  }

  /* Sitemap and single-file have the same shape, and the same neighbours. */
  @Test
  public void sitemapAndSingleFileNeverSpellTheirArgumentVariant() {
    final List<String> cmd = new ArrayList<String>();
    UrlSplit.INSTANCE.emit(cmd, "http://www.example.com/");
    new SimpleOptionFlag("%m").emit(cmd, "1"); // Sitemap
    new SimpleOption0("%u").emit(cmd, "1"); // URLHack
    new SimpleOptionFlag("%Z").emit(cmd, "1"); // SingleFile
    new SimpleOption("s").emit(cmd, "0"); // FollowRobotsTxt

    assertNoArgumentVariant(cmd);
    assertEquals(Arrays.asList("http://www.example.com/", "-%m", "-%u", "-%Z",
        "-s0"), cmd);
  }

  @Test
  public void simpleOptionAppendsItsValue() {
    assertEquals(Arrays.asList("-r5"), emit(new SimpleOption("r"), "5"));
    assertEquals(Arrays.asList("-#L500"), emit(new SimpleOption("#L"), "500"));
    assertEquals(Arrays.asList("-u0"), emit(new SimpleOption("u"), "0"));
  }

  @Test
  public void simpleOptionStaysSilentWithoutAValue() {
    assertTrue(emit(new SimpleOption("r"), "").isEmpty());
    assertTrue(emit(new SimpleOption("r"), null).isEmpty());
  }

  /* -%f is on unless the value is exactly "0", which spells -%f0. */
  @Test
  public void simpleOption0AppendsTheDisableDigitOnlyForZero() {
    assertEquals(Arrays.asList("-%f0"), emit(new SimpleOption0("%f"), "0"));
    assertEquals(Arrays.asList("-%f"), emit(new SimpleOption0("%f"), "1"));
    assertEquals(Arrays.asList("-%f"), emit(new SimpleOption0("%f"), "2"));
  }

  @Test
  public void simpleOption0StaysSilentWithoutAValue() {
    assertTrue(emit(new SimpleOption0("%f"), "").isEmpty());
    assertTrue(emit(new SimpleOption0("%f"), null).isEmpty());
  }

  /* Keep-alive is the second-set -%k; the bare -k is store-all-in-cache. */
  @Test
  public void keepAliveEmitsTheSecondSetOption() {
    assertEquals(Arrays.asList("-%k"), emit(new SimpleOption0("%k"), "1"));
    assertEquals(Arrays.asList("-%k0"), emit(new SimpleOption0("%k"), "0"));
    for (final String value : new String[] { "", null }) {
      assertTrue(emit(new SimpleOption0("%k"), value).isEmpty());
    }
  }

  @Test
  public void simpleOptionFlagEmitsOnlyOnOne() {
    assertEquals(Arrays.asList("-n"), emit(new SimpleOptionFlag("n"), "1"));
    for (final String value : new String[] { "0", "2", "", null }) {
      assertTrue(emit(new SimpleOptionFlag("n"), value).isEmpty());
    }
  }

  /* The reverted form (b0, I0, C0, j) emits on "0" and stays silent on "1". */
  @Test
  public void revertedFlagEmitsOnlyOnZero() {
    assertEquals(Arrays.asList("-b0"),
        emit(new SimpleOptionFlag("b0", true), "0"));
    for (final String value : new String[] { "1", "2", "", null }) {
      assertTrue(emit(new SimpleOptionFlag("b0", true), value).isEmpty());
    }
  }

  private static List<String> emitMaxSize(final String html,
      final String nonHtml) {
    final MaxSizeHandler handler = new MaxSizeHandler();
    final List<String> cmd = new ArrayList<String>();
    handler.getHtml().emit(cmd, html);
    final OptionMapper nonHtmlMapper = handler.getNonHtml();
    nonHtmlMapper.emit(cmd, nonHtml);
    ((OptionMapper.FinishMapper) nonHtmlMapper).finish(cmd);
    return cmd;
  }

  @Test
  public void maxSizeMergesBothLimitsIntoOneToken() {
    assertEquals(Arrays.asList("-m2000,1000"), emitMaxSize("1000", "2000"));
    assertEquals(Arrays.asList("-m,1000"), emitMaxSize("1000", ""));
    assertEquals(Arrays.asList("-m2000"), emitMaxSize("", "2000"));
  }

  @Test
  public void maxSizeStaysSilentWithNoLimit() {
    assertTrue(emitMaxSize("", "").isEmpty());
    assertTrue(emitMaxSize(null, "not-a-number").isEmpty());
  }

  @Test
  public void maxSizeEmitsOnce() {
    final MaxSizeHandler handler = new MaxSizeHandler();
    final OptionMapper mapper = handler.getHtml();
    final List<String> cmd = new ArrayList<String>();
    mapper.emit(cmd, "1000");
    ((OptionMapper.FinishMapper) mapper).finish(cmd);
    ((OptionMapper.FinishMapper) handler.getNonHtml()).finish(cmd);
    assertEquals(Arrays.asList("-m,1000"), cmd);
  }

  private static List<String> emitHostControl(final String time,
      final String rate) {
    final HostControlHandler handler = new HostControlHandler();
    final List<String> cmd = new ArrayList<String>();
    handler.getTimeMapper().emit(cmd, time);
    final OptionMapper rateMapper = handler.getRateMapper();
    rateMapper.emit(cmd, rate);
    ((OptionMapper.FinishMapper) rateMapper).finish(cmd);
    return cmd;
  }

  @Test
  public void hostControlOrsTheTwoAbandonFlags() {
    assertEquals(Arrays.asList("-H0"), emitHostControl("0", "0"));
    assertEquals(Arrays.asList("-H1"), emitHostControl("1", "0"));
    assertEquals(Arrays.asList("-H2"), emitHostControl("0", "1"));
    assertEquals(Arrays.asList("-H3"), emitHostControl("1", "1"));
  }

  @Test
  public void hostControlEmitsOnce() {
    final HostControlHandler handler = new HostControlHandler();
    final List<String> cmd = finish(handler.getTimeMapper());
    ((OptionMapper.FinishMapper) handler.getRateMapper()).finish(cmd);
    assertEquals(Arrays.asList("-H0"), cmd);
  }

  private static List<String> emitDosIso(final String dos, final String iso) {
    final DosIso9660Handler handler = new DosIso9660Handler();
    final List<String> cmd = new ArrayList<String>();
    handler.getDosMapper().emit(cmd, dos);
    final OptionMapper isoMapper = handler.getIso9660Mapper();
    isoMapper.emit(cmd, iso);
    ((OptionMapper.FinishMapper) isoMapper).finish(cmd);
    return cmd;
  }

  @Test
  public void dosIso9660PrefersDosOverIso() {
    assertTrue(emitDosIso("0", "0").isEmpty());
    assertEquals(Arrays.asList("-L0"), emitDosIso("1", "0"));
    assertEquals(Arrays.asList("-L2"), emitDosIso("0", "1"));
    assertEquals(Arrays.asList("-L0"), emitDosIso("1", "1"));
  }

  @Test
  public void dosIso9660EmitsOnce() {
    final DosIso9660Handler handler = new DosIso9660Handler();
    final OptionMapper mapper = handler.getDosMapper();
    final List<String> cmd = new ArrayList<String>();
    mapper.emit(cmd, "1");
    ((OptionMapper.FinishMapper) mapper).finish(cmd);
    ((OptionMapper.FinishMapper) handler.getIso9660Mapper()).finish(cmd);
    assertEquals(Arrays.asList("-L0"), cmd);
  }

  private static List<String> emitBuild(final String type, final String custom) {
    final BuildHandler handler = new BuildHandler();
    final List<String> cmd = new ArrayList<String>();
    handler.getCustomMapper().emit(cmd, custom);
    final OptionMapper typeMapper = handler.getTypeMapper();
    typeMapper.emit(cmd, type);
    ((OptionMapper.FinishMapper) typeMapper).finish(cmd);
    return cmd;
  }

  /* Radio index into the engine's structure numbers; 14 is "custom". */
  @Test
  public void buildMapsTheRadioIndexToAStructureNumber() {
    assertEquals(Arrays.asList("-N0"), emitBuild("0", "%h%p/%n%q.%t"));
    assertEquals(Arrays.asList("-N100"), emitBuild("6", "%h%p/%n%q.%t"));
    assertEquals(Arrays.asList("-N199"), emitBuild("13", "%h%p/%n%q.%t"));
    assertEquals(Arrays.asList("-N", "%h%p/%n%q.%t"),
        emitBuild("14", "%h%p/%n%q.%t"));
    assertTrue(emitBuild("15", "%h%p/%n%q.%t").isEmpty());
  }

  @Test
  public void buildEmitsOnce() {
    final BuildHandler handler = new BuildHandler();
    final OptionMapper mapper = handler.getTypeMapper();
    final List<String> cmd = new ArrayList<String>();
    mapper.emit(cmd, "6");
    ((OptionMapper.FinishMapper) mapper).finish(cmd);
    ((OptionMapper.FinishMapper) handler.getCustomMapper()).finish(cmd);
    assertEquals(Arrays.asList("-N100"), cmd);
  }
}
