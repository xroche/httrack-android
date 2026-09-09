package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.httrack.android.OptionsMapper.ProfileFormat;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Reading a winprofile.ini, the null one a refused project name yields included. */
public class ProfileReadTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

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

  /** The crash was in unserialize, so the guard is only worth anything if it reads through it. */
  @Test
  public void unserializeReachesTheFileOnlyThroughRawFields() throws Exception {
    final String source = TestSources.javaSource("OptionsMapper");
    final String body = TestSources.balancedBlock(source,
        source.indexOf("public static void unserialize(final File profile,"));
    assertTrue("unserialize must hand the profile to rawFields",
        body.contains("ProfileFormat.rawFields(profile)"));
    assertEquals("unserialize must not touch the file itself", -1,
        body.indexOf("profile."));
  }
}
