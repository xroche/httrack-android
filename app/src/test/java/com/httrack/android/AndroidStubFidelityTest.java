package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.util.Pair;
import android.util.SparseArray;
import org.junit.Test;

/** The local android.util stubs stand in for classes the mockable android.jar cannot run, so a
 *  stub that answers differently from the real class quietly grades every test that uses it. */
public class AndroidStubFidelityTest {
  /** Real SparseArray keeps a DELETED sentinel apart from a stored null, so a key present with
   *  a null value answers with that null and not with the caller's fallback. */
  @Test
  public void aKeyHeldWithANullValueAnswersNull() {
    final SparseArray<String> array = new SparseArray<String>();
    array.put(1, null);
    assertNull("stored null, not the fallback", array.get(1, "fallback"));
    assertEquals("absent key takes the fallback", "fallback",
        array.get(2, "fallback"));
    assertEquals("a present key still wins", "held",
        newArray(3, "held").get(3, "fallback"));
  }

  @Test
  public void cloneCopiesTheContainer() {
    final SparseArray<String> array = newArray(1, "one");
    final SparseArray<String> copy = array.clone();
    array.put(2, "two");
    assertEquals("the copy did not follow", 1, copy.size());
    copy.put(3, "three");
    assertEquals("and the original did not follow", 2, array.size());
    assertSame("the values themselves are shared", array.get(1),
        copy.get(1));
  }

  /** Object.clone() is what the real class calls, so a subclass gets back its own type. */
  @Test
  public void cloneKeepsTheRuntimeType() {
    final NamedArray subclass = new NamedArray();
    subclass.put(1, "one");
    assertTrue("a plain SparseArray would be the wrong type",
        subclass.clone() instanceof NamedArray);
  }

  @Test
  public void pairComparesByValue() {
    assertEquals("equal fields make equal pairs", Pair.create("a", "b"),
        Pair.create("a", "b"));
    assertFalse("a differing first", Pair.create("a", "b").equals(
        Pair.create("z", "b")));
    assertFalse("a differing second", Pair.create("a", "b").equals(
        Pair.create("a", "z")));
    assertEquals("null fields compare too", Pair.create(null, null),
        Pair.create(null, null));
    assertFalse("a null against a value", Pair.create("a", null).equals(
        Pair.create("a", "b")));
    assertFalse("a non-pair", Pair.create("a", "b").equals("a"));
  }

  @Test
  public void pairHashesBothFields() {
    assertEquals("equal pairs must agree", Pair.create("a", "b").hashCode(),
        Pair.create("a", "b").hashCode());
    // The real class XORs the two fields, so record that a swap collides.
    assertEquals("a swap collides, as upstream", Pair.create("a", "b")
        .hashCode(), Pair.create("b", "a").hashCode());
    assertEquals("null fields hash as zero", 0, Pair.create(null, null)
        .hashCode());
  }

  @Test
  public void pairPrintsBothFields() {
    assertEquals("Pair{a b}", Pair.create("a", "b").toString());
    assertEquals("Pair{null null}", Pair.create(null, null).toString());
  }

  private static SparseArray<String> newArray(final int key,
      final String value) {
    final SparseArray<String> array = new SparseArray<String>();
    array.put(key, value);
    return array;
  }

  /** A subclass, as SparseArraySerializable is, so clone() has a runtime type to keep. */
  private static class NamedArray extends SparseArray<String> {
  }
}
