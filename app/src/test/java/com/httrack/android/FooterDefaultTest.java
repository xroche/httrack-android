package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The default -%F template must use the engine's named fields: one %s anywhere
 * selects the legacy positional path, which renders {url} verbatim.
 */
public class FooterDefaultTest {
  private static final Pattern MAPPER_DEFAULT = Pattern.compile(
      "\"Footer\",\\s*\"(.*?)\"\\)", Pattern.DOTALL);

  private static final Pattern HINT = Pattern
      .compile("<string name=\"hint_footer\"[^>]*>(.*?)</string>", Pattern.DOTALL);

  private static String group(final Pattern pattern, final String text) {
    final Matcher m = pattern.matcher(text);
    assertTrue("no match for " + pattern, m.find());
    return m.group(1);
  }

  /** Android string-resource escaping, enough for this one string. */
  private static String unescape(final String value) {
    return value.replace("&lt;", "<").replace("&gt;", ">").replace("\\'", "'")
        .replace("&amp;", "&");
  }

  private static String mapperDefault() throws IOException {
    return group(MAPPER_DEFAULT, TestSources.javaSource("OptionsMapper"));
  }

  @Test
  public void defaultUsesNamedFields() throws IOException {
    final String footer = mapperDefault();
    assertFalse(footer, footer.contains("%s"));
    assertTrue(footer, footer.contains("{url}"));
    assertTrue(footer, footer.contains("{date}"));
  }

  /** The hint shows the field's own default, so it must not teach %s either. */
  @Test
  public void hintMatchesTheDefault() throws IOException {
    final String hint = unescape(group(HINT,
        TestSources.read(TestSources.resFile("values/strings.xml"))));
    assertEquals(mapperDefault(), hint);
  }
}
