package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The engine refuses to compile a self-test source under HTS_NO_SELFTEST, so re-adding one to
 * Android.mk breaks the build. A later engine could add a self-test that does not include that
 * header, and then nothing would notice, so hold the engine's own list against ours.
 */
public class EngineSelftestsExcludedTest {
  /** The sources the engine builds only when self-tests are wanted, basenames. */
  private static Set<String> engineSelftests() throws IOException {
    final String makefile = TestSources.read(TestSources.engineFile("src/Makefile.am"))
        .replaceAll("\\\\\n\\s*", " ");
    final String block = TestSources.between(makefile, "if SELFTESTS", "endif");
    final Matcher sources = Pattern.compile("_SOURCES\\s*[:+]?=\\s*([^\n]*)").matcher(block);
    final Set<String> all = new LinkedHashSet<String>();
    assertTrue("the engine must still gate its self-tests on SELFTESTS", sources.find());
    do {
      all.addAll(Arrays.asList(sources.group(1).trim().split("\\s+")));
    } while (sources.find());
    return all;
  }

  /** Basenames of the engine sources the libhttrack module builds. */
  private static Set<String> engineSourcesBuilt() throws IOException {
    final String mk = TestSources.flattenedMakefile();
    final Matcher module = Pattern.compile("LOCAL_MODULE\\s*:=\\s*libhttrack\\b").matcher(mk);
    assertTrue("Android.mk must still define the libhttrack module", module.find());
    final String body = mk.substring(module.end()).split("include \\$\\(BUILD")[0];
    final Matcher sources = Pattern.compile("LOCAL_SRC_FILES\\s*[:+]?=\\s*([^\n]*)").matcher(body);
    final Set<String> all = new LinkedHashSet<String>();
    assertTrue("the libhttrack module must still carry a source list", sources.find());
    do {
      for (final String path : sources.group(1).trim().split("\\s+")) {
        all.add(path.substring(path.lastIndexOf('/') + 1));
      }
    } while (sources.find());
    return all;
  }

  @Test
  public void noEngineSelftestIsBuilt() throws IOException {
    final Set<String> selftests = engineSelftests();
    final Set<String> built = engineSourcesBuilt();
    assertFalse("no self-test source parsed, so this test would pass on anything",
        selftests.isEmpty());
    // Control: an engine source that must be built, or the list above could be read as empty.
    assertTrue("htscore.c must still be built", built.contains("htscore.c"));
    final Set<String> both = new LinkedHashSet<String>(built);
    both.retainAll(selftests);
    assertTrue("the APK must not build the engine self-tests: " + both, both.isEmpty());
  }
}
