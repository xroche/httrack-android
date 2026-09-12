package com.httrack.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** The argv the app hands the engine, recorded literally before the crawl core moved out of
 *  HTTrackActivity.Runner, so the move can be proved to have changed nothing. The literals were
 *  produced by the run at e62ccdb; masterArgv() is Runner.runInternal()'s own assembly from that
 *  commit. Once a production assembler exists, it answers the same bytes or this reds. */
public class CrawlArgvGoldenTest {
  private static final String ASSEMBLER = "com.httrack.android.CrawlArgv";
  private static final String TARGET = "/storage/emulated/0/HTTrack/demo";

  /** Every option the golden map sets, and its value. */
  private static final int IDS[] = { R.id.fieldProjectName, R.id.fieldWebsiteURLs,
      R.id.radioAction, R.id.editMaxDepth, R.id.editMaxExtDepth, R.id.editNumberOfConnections,
      R.id.checkPersistentConnections, R.id.checkUseCacheForUpdates, R.id.editRules,
      R.id.checkDosNames, R.id.editBrowserIdentity, R.id.editAcceptLanguage };
  private static final String VALUES[] = { "demo", "http://example.com/ http://example.org/", "1",
      "3", "1", "4", "0", "0", "+*.png -*.zip", "1", "Mozilla/5.0 (probe)", "fr,en,*" };

  /** What buildCommandline() returned for that map on master. */
  private static final String OPTIONS[] = { "-iC2", "http://example.com/", "http://example.org/",
      "+*.png", "-*.zip", "-r3", "-%e1", "-A25000", "-c4", "-%k0", "-%P", "-F",
      "Mozilla/5.0 (probe)", "-%F",
      "<!-- Mirrored from {url} by HTTrack Website Copier/3.x [XR&CO], {date} -->", "-%l",
      "fr,en,*", "-u1", "-s2", "-%s", "-%u", "-%f", "-C0", "-D", "-a", "-K0", "-H0", "-N0", "-L0",
      "-p3" };

  /** The whole argv with IPv6 available, so no family is forced. */
  private static final String ARGV_IPV6[] = full("httrack", "-O", TARGET);

  /** The whole argv without IPv6, where -@i4 precedes the target. */
  private static final String ARGV_IPV4[] = full("httrack", "-@i4", "-O", TARGET);

  private static String[] full(final String... prelude) {
    final List<String> argv = new ArrayList<String>(Arrays.asList(prelude));
    argv.addAll(Arrays.asList(OPTIONS));
    return argv.toArray(new String[] {});
  }

  /** The shipped mapper, driven through the shipped assembly path. */
  private static List<String> options() {
    final OptionsMapper mapper = new OptionsMapper();
    for (int i = 0; i < IDS.length; i++) {
      mapper.setMap(IDS[i], VALUES[i]);
    }
    return mapper.buildCommandline();
  }

  /** Runner.runInternal()'s assembly as master wrote it, kept here as the thing to match. */
  private static String[] masterArgv(final boolean ipv6Enabled, final String target,
      final List<String> options) {
    final List<String> args = new ArrayList<String>();
    args.add("httrack");
    if (!ipv6Enabled) {
      args.add("-@i4");
    }
    args.add("-O");
    args.add(target);
    args.addAll(options);
    return args.toArray(new String[] {});
  }

  @Test
  public void theOptionsAreStillTheOnesMasterEmitted() {
    assertEquals(Arrays.asList(OPTIONS), options());
  }

  @Test
  public void masterAssembledTheseTwoArgvs() {
    assertArrayEquals("with IPv6", ARGV_IPV6, masterArgv(true, TARGET, options()));
    assertArrayEquals("without IPv6", ARGV_IPV4, masterArgv(false, TARGET, options()));
  }

  /** Absent on master, where the assembly is inline in Runner.runInternal(); present after the
   *  extraction, where it must answer the same two arrays. */
  @Test
  public void theExtractedAssemblerAnswersTheSame() throws Exception {
    final Class<?> assembler;
    try {
      assembler = Class.forName(ASSEMBLER);
    } catch (final ClassNotFoundException notYet) {
      System.out.println(ASSEMBLER + " does not exist yet; the assembly is still inline");
      return;
    }
    final Method build;
    try {
      build = assembler.getDeclaredMethod("build", boolean.class, String.class, List.class);
    } catch (final NoSuchMethodException missing) {
      fail(ASSEMBLER + " has no build(boolean, String, List)");
      return;
    }
    build.setAccessible(true);
    assertArrayEquals("with IPv6", ARGV_IPV6, (String[]) build.invoke(null, true, TARGET,
        options()));
    assertArrayEquals("without IPv6", ARGV_IPV4, (String[]) build.invoke(null, false, TARGET,
        options()));
  }
}
