package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** CrawlRun.Messages carries six strings of one type, so a swapped pair compiles and only shows
 *  up as the wrong sentence on a phone. Each field is pinned to the resource that fills it and to
 *  the parameter that assigns it. */
public class CrawlMessagesTest {
  /** Field, then the string resource the activity reads for it, in constructor order. */
  private static final String PAIRS[][] = { { "creatingProject", "R.string.creating_project" },
      { "startingMirror", "R.string.starting_mirror" },
      { "selfContainedConflict", "R.string.self_contained_conflict" },
      { "alreadyInProgress", "R.string.mirror_already_in_progress" },
      { "engineFaulted", "R.string.engine_faulted" },
      { "mirrorFinished", "R.string.mirror_finished" } };

  private static String source(final String name) throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.javaSource(name));
  }

  /** Body of the method whose declaration starts with SIGNATURE, braces balanced. */
  private static String body(final String source, final String signature) {
    final int at = source.indexOf(signature);
    assertTrue("no " + signature.trim(), at != -1);
    return TestSources.balancedBlock(source, at + signature.length());
  }

  /** ARGUMENTS split at top-level commas, whitespace collapsed. */
  private static List<String> split(final String arguments) {
    final List<String> parts = new ArrayList<String>();
    int depth = 0;
    int from = 0;
    for (int i = 0; i < arguments.length(); i++) {
      final char c = arguments.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(arguments.substring(from, i).replaceAll("\\s+", " ").trim());
        from = i + 1;
      }
    }
    parts.add(arguments.substring(from).replaceAll("\\s+", " ").trim());
    return parts;
  }

  @Test
  public void theActivityFillsEachFieldFromItsOwnString() throws IOException {
    final List<String> read = split(TestSources.arguments(
        body(source("HTTrackActivity"), "CrawlRun.Messages crawlMessages()"),
        "new CrawlRun.Messages"));
    assertEquals("the message count changed", PAIRS.length, read.size());
    for (int i = 0; i < PAIRS.length; i++) {
      assertEquals(PAIRS[i][0] + " is filled from the wrong string",
          "requireString(" + PAIRS[i][1] + ")", read.get(i));
    }
  }

  @Test
  public void theConstructorAssignsEachParameterToItsOwnField() throws IOException {
    // Sliced first, or setMessages() elsewhere in the file could pass for the constructor.
    final String declared = body(source("CrawlRun"), "static final class Messages");
    final List<String> parameters = split(TestSources.arguments(declared, "Messages"));
    assertEquals("the constructor takes a different number of messages", PAIRS.length,
        parameters.size());
    final String assignments = body(declared, "Messages(final String creatingProject");
    for (int i = 0; i < PAIRS.length; i++) {
      assertEquals("parameter " + i + " is not the one the activity fills there",
          "final String " + PAIRS[i][0], parameters.get(i));
      assertTrue(PAIRS[i][0] + " is assigned from another parameter", assignments
          .contains("this." + PAIRS[i][0] + " = " + PAIRS[i][0] + ";"));
    }
  }
}
