package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The c-cpp CodeQL analysis is scoped by build order: the engine compiles before init, where the
 * tracer cannot see it, and only our own sources are recompiled after. So the traced step must
 * touch exactly the htslibjni module's sources. A source added to that module and left out of the
 * workflow would silently drop out of the scan.
 */
public class CodeqlScopeTest {
  private static File repoFile(final String path) throws IOException {
    for (final String prefix : new String[] { "", "../" }) {
      final File file = new File(prefix + path);
      if (file.isFile()) {
        return file;
      }
    }
    throw new IOException("no " + path + " below " + new File(".").getAbsolutePath());
  }

  /** Android.mk is CRLF and wraps its lists, so fold both away before matching. */
  private static String flattened(final String path) throws IOException {
    return TestSources.read(repoFile(path)).replace("\r", "").replaceAll("\\\\\n\\s*", " ");
  }

  private static List<String> glueSources() throws IOException {
    final String mk = flattened("app/src/main/jni/Android.mk");
    final Matcher module = Pattern.compile("LOCAL_MODULE\\s*:=\\s*htslibjni\\b").matcher(mk);
    assertTrue("Android.mk must still define the htslibjni module", module.find());
    final Matcher sources =
        Pattern.compile("LOCAL_SRC_FILES\\s*:=\\s*([^\n]*)").matcher(mk.substring(module.end()));
    assertTrue("the htslibjni module must still carry a source list", sources.find());
    return Arrays.asList(sources.group(1).trim().split("\\s+"));
  }

  private static List<String> tracedSources() throws IOException {
    final String workflow = flattened(".github/workflows/codeql.yml");
    final Matcher touch = Pattern.compile("touch\\s+([^\n]*)").matcher(workflow);
    assertTrue("the traced step must touch the sources it wants recompiled", touch.find());
    final String[] paths = touch.group(1).trim().split("\\s+");
    for (int i = 0; i < paths.length; i++) {
      paths[i] = paths[i].replace("app/src/main/jni/", "");
    }
    return Arrays.asList(paths);
  }

  @Test
  public void theTracedBuildRecompilesEveryGlueSource() throws IOException {
    assertEquals(glueSources(), tracedSources());
  }

  /** Compiling the engine after init would put it back in this repo's results. */
  @Test
  public void theEngineIsBuiltBeforeCodeqlInit() throws IOException {
    final String workflow = flattened(".github/workflows/codeql.yml");
    final int untraced = workflow.indexOf("Build the engine, untraced");
    final int init = workflow.indexOf("github/codeql-action/init");
    final int traced = workflow.indexOf("Recompile our own native code, traced");
    assertTrue("the untraced engine build must come first", untraced > 0 && untraced < init);
    assertTrue("the traced recompile must come after init", init < traced);
  }
}
