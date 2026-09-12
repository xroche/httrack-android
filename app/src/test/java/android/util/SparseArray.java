package android.util;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Stands in for the mockable android.jar, whose SparseArray throws "not mocked". */
public class SparseArray<E> implements Cloneable {
  private TreeMap<Integer, E> entries = new TreeMap<Integer, E>();

  public SparseArray() {
  }

  public SparseArray(final int initialCapacity) {
  }

  public void put(final int key, final E value) {
    entries.put(key, value);
  }

  public E get(final int key) {
    return entries.get(key);
  }

  public E get(final int key, final E valueIfKeyNotFound) {
    // A key held with a null value returns that null, as the real class does.
    return entries.containsKey(key) ? entries.get(key) : valueIfKeyNotFound;
  }

  public void delete(final int key) {
    entries.remove(key);
  }

  public void remove(final int key) {
    delete(key);
  }

  public void clear() {
    entries.clear();
  }

  public int size() {
    return entries.size();
  }

  public int keyAt(final int index) {
    return keys().get(index);
  }

  public E valueAt(final int index) {
    return entries.get(keyAt(index));
  }

  public int indexOfKey(final int key) {
    return keys().indexOf(key);
  }

  /** Copies the container and shares the values, as the real clone() does. */
  @Override
  @SuppressWarnings("unchecked")
  public SparseArray<E> clone() {
    try {
      // super.clone() so a subclass keeps its own runtime type.
      final SparseArray<E> copy = (SparseArray<E>) super.clone();
      copy.entries = new TreeMap<Integer, E>(entries);
      return copy;
    } catch (final CloneNotSupportedException cnse) {
      throw new AssertionError(cnse);
    }
  }

  private List<Integer> keys() {
    return new ArrayList<Integer>(entries.keySet());
  }
}
