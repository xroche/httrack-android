package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Truth table for the cache-token decision, then the same decision read off the production
 *  option table and the real buildCommandline(). */
public class CachePolicyTest {
  private static final String CONTINUE = "0";
  private static final String UPDATE = "1";
  private static final String NEW = null;
  private static final String TICKED = "1";
  private static final String UNTICKED = "0";

  /** Each answer is written out rather than computed, so the table cannot agree with a change
   *  to the expression it checks. Arguments are the action radio and the cache checkbox. */
  @Test
  public void continueNeverEmitsTheCacheOption() {
    assertEquals("continue/ticked", false,
        CachePolicy.emitsCacheOption(CONTINUE, TICKED));
    assertEquals("continue/unticked", false,
        CachePolicy.emitsCacheOption(CONTINUE, UNTICKED));
    assertEquals("update/ticked", false,
        CachePolicy.emitsCacheOption(UPDATE, TICKED));
    assertEquals("update/unticked", true,
        CachePolicy.emitsCacheOption(UPDATE, UNTICKED));
    assertEquals("new/ticked", false,
        CachePolicy.emitsCacheOption(NEW, TICKED));
    assertEquals("new/unticked", true,
        CachePolicy.emitsCacheOption(NEW, UNTICKED));
  }

  /* The shipped mapper, driven through the shipped assembly path. */
  private static List<String> argv(final String action, final String cache) {
    final OptionsMapper mapper = new OptionsMapper();
    mapper.setMap(R.id.radioAction, action);
    mapper.setMap(R.id.checkUseCacheForUpdates, cache);
    return mapper.buildCommandline();
  }

  @Test
  public void continueKeepsItsCacheModeWhenTheBoxIsUnticked() {
    final List<String> cmd = argv(CONTINUE, UNTICKED);
    assertTrue("-iC1 must carry the resume", cmd.contains("-iC1"));
    assertFalse("-C0 would overwrite it", cmd.contains("-C0"));
  }

  @Test
  public void updateStillHonoursTheBox() {
    final List<String> cmd = argv(UPDATE, UNTICKED);
    assertTrue("-iC2 must select update", cmd.contains("-iC2"));
    assertTrue("-C0 is what the untick asks for", cmd.contains("-C0"));
    assertFalse("a ticked box asks for nothing",
        argv(UPDATE, TICKED).contains("-C0"));
  }

  @Test
  public void aNewProjectHonoursTheBox() {
    final List<String> cmd = argv(NEW, UNTICKED);
    assertFalse("no mirror to resume or update", cmd.contains("-iC1"));
    assertFalse("no mirror to resume or update", cmd.contains("-iC2"));
    assertTrue("-C0 is what the untick asks for", cmd.contains("-C0"));
    assertFalse("a ticked box asks for nothing",
        argv(NEW, TICKED).contains("-C0"));
  }
}
