package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.httrack.android.OptionsMapper.ProfileFormat;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Reads winprofile.ini, including the null file a refused project name yields. */
public class ProfileReadTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private static int occurrences(final String text, final String word) {
    final Matcher at = Pattern.compile("\\b" + word + "\\b").matcher(text);
    int count = 0;
    while (at.find()) {
      count++;
    }
    return count;
  }

  /** A name holding a slash is refused as a path, so getProfileFile() hands down null. */
  @Test
  public void aProfileThatIsNullReadsAsAnAbsentOne() throws Exception {
    try {
      ProfileFormat.rawFields(null);
      fail("a null profile must not read as an empty one");
    } catch (final IOException e) {
      assertEquals("no such profile", e.getMessage());
    }
  }

  @Test
  public void anAbsentProfileStillThrows() throws Exception {
    try {
      ProfileFormat.rawFields(new File(tmp.getRoot(), "winprofile.ini"));
      fail("expected IOException");
    } catch (final IOException e) {
      assertEquals("no such profile", e.getMessage());
    }
  }

  @Test
  public void keepsTheStatedPairsAndDropsCommentsAndBlanks() throws Exception {
    final File profile = tmp.newFile("winprofile.ini");
    Files.write(profile.toPath(),
        ("; a comment\n\nProjectName=demo\nCurrentUrl=http://example.com/?a=b\nnoise\n")
            .getBytes("UTF-8"));

    final Map<String, String> raw = ProfileFormat.rawFields(profile);
    assertEquals(2, raw.size());
    assertEquals("demo", raw.get("ProjectName"));
    assertEquals("http://example.com/?a=b", raw.get("CurrentUrl"));
  }

  /** rawFields is one side of the decode boundary, so it must hand the escapes back untouched. */
  @Test
  public void leavesTheValuesEncoded() throws Exception {
    final File profile = tmp.newFile("winprofile.ini");
    Files.write(profile.toPath(), "Category=100%% done%09here\n".getBytes("UTF-8"));

    assertEquals("100%% done%09here",
        ProfileFormat.rawFields(profile).get("Category"));
  }

  /** The crash was in unserialize, so the guard is only worth anything if it reads through it. */
  @Test
  public void unserializeReachesTheFileOnlyThroughRawFields() throws Exception {
    final String source = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("OptionsMapper"));
    final int at =
        source.indexOf("public static void unserialize(final File profile,");
    assertTrue("unserialize's declaration changed; re-anchor this guard", at != -1);
    final String body = TestSources.balancedBlock(source, at);

    assertTrue("unserialize must hand the profile to rawFields",
        body.contains("ProfileFormat.rawFields(profile)"));
    // A second mention is unserialize opening or testing the file itself, which is the crash.
    assertEquals("rawFields must be the only use of the profile file", 1,
        occurrences(body, "profile"));
    // Without it the %% escapes reach the UI verbatim.
    assertTrue("unserialize must decode what rawFields returns",
        body.contains("profileDecode(line.getValue())"));
  }
}
