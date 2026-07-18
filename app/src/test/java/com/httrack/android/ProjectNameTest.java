package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Attacks StoragePaths.isValidProjectName: a name is one in-root directory segment, nothing more. */
public class ProjectNameTest {
  @Test
  public void acceptsAPlainName() {
    assertTrue(StoragePaths.isValidProjectName("My Website"));
    assertTrue(StoragePaths.isValidProjectName("..dotdot..name"));
    assertTrue(StoragePaths.isValidProjectName("a.b.c"));
  }

  @Test
  public void refusesEmptyOrDotSegments() {
    assertFalse(StoragePaths.isValidProjectName(null));
    assertFalse(StoragePaths.isValidProjectName(""));
    assertFalse(StoragePaths.isValidProjectName("."));
    assertFalse(StoragePaths.isValidProjectName(".."));
  }

  @Test
  public void refusesAnyPathSeparator() {
    assertFalse(StoragePaths.isValidProjectName("../../../../sdcard/Android/data/evil"));
    assertFalse(StoragePaths.isValidProjectName("a/b"));
    assertFalse(StoragePaths.isValidProjectName("a\\b"));
    assertFalse(StoragePaths.isValidProjectName("evil\0hidden"));
  }
}
