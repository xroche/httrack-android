package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * A JNI glue source left out of the traced build step would drop out of the CodeQL scan with
 * nothing turning red, because that step is what scopes the c-cpp analysis.
 */
public class CodeqlScopeTest {
  private static final String WORKFLOW = ".github/workflows/codeql.yml";
  private static final String UNTRACED = "Build the engine, untraced";
  private static final String TRACED = "Recompile the JNI glue, traced";
  private static final String INIT = "github/codeql-action/init";

  /** Android.mk is CRLF and wraps its lists, so fold both away before matching. */
  private static String flattened(final String path) throws IOException {
    for (final String prefix : new String[] { "", "../" }) {
      final File file = new File(prefix + path);
      if (file.isFile()) {
        return TestSources.read(file).replace("\r", "").replaceAll("\\\\\n\\s*", " ");
      }
    }
    throw new IOException("no " + path + " below " + new File(".").getAbsolutePath());
  }

  /** Every source the htslibjni module builds, "+=" included, so an appended one is not missed. */
  private static Set<String> glueSources() throws IOException {
    final String mk = TestSources.flattenedMakefile();
    final Matcher module = Pattern.compile("LOCAL_MODULE\\s*:=\\s*htslibjni\\b").matcher(mk);
    assertTrue("Android.mk must still define the htslibjni module", module.find());
    final String body = mk.substring(module.end()).split("include \\$\\(BUILD")[0];
    final Matcher sources = Pattern.compile("LOCAL_SRC_FILES\\s*[:+]?=\\s*([^\n]*)").matcher(body);
    final Set<String> all = new LinkedHashSet<String>();
    assertTrue("the htslibjni module must still carry a source list", sources.find());
    do {
      all.addAll(Arrays.asList(sources.group(1).trim().split("\\s+")));
    } while (sources.find());
    return all;
  }

  /** The sources the traced step touches, named as Android.mk names them. */
  private static Set<String> tracedSources() throws IOException {
    final String workflow = flattened(WORKFLOW);
    final Matcher touch =
        Pattern.compile("touch\\s+([^\n]*)").matcher(workflow.substring(indexOf(workflow, TRACED)));
    assertTrue("the traced step must touch the sources it wants recompiled", touch.find());
    final Set<String> all = new LinkedHashSet<String>();
    for (final String path : touch.group(1).trim().split("\\s+")) {
      all.add(path.replace("app/src/main/jni/", ""));
    }
    return all;
  }

  private static int indexOf(final String haystack, final String needle) {
    final int at = haystack.indexOf(needle);
    assertTrue(needle + " must still appear in " + WORKFLOW, at >= 0);
    return at;
  }

  @Test
  public void theTracedBuildRecompilesEveryGlueSource() throws IOException {
    assertEquals(glueSources(), tracedSources());
  }

  /** Compiling the engine after init would put it back in this repo's results. */
  @Test
  public void theEngineIsBuiltBeforeCodeqlInit() throws IOException {
    final String workflow = flattened(WORKFLOW);
    assertTrue("the untraced engine build must come before init",
        indexOf(workflow, UNTRACED) < indexOf(workflow, INIT));
    assertTrue("the traced recompile must come after init",
        indexOf(workflow, INIT) < indexOf(workflow, TRACED));
  }

  /** A step named for a build that no longer runs ndk-build would pass the ordering check alone. */
  @Test
  public void bothBuildStepsStillRunNdkBuild() throws IOException {
    final String workflow = flattened(WORKFLOW);
    final String untraced =
        workflow.substring(indexOf(workflow, UNTRACED), indexOf(workflow, INIT));
    assertTrue("the untraced step must build the engine", untraced.contains("ndk-build"));
    assertTrue("the traced step must build the glue",
        workflow.substring(indexOf(workflow, TRACED)).contains("ndk-build"));
  }
}
