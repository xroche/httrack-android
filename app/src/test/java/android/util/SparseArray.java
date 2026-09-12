package android.util;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Stands in for the mockable android.jar, whose SparseArray throws "not mocked". */
public class SparseArray<E> implements Cloneable {
  private final TreeMap<Integer, E> entries = new TreeMap<Integer, E>();

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
    final E value = entries.get(key);
    return value != null ? value : valueIfKeyNotFound;
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

  private List<Integer> keys() {
    return new ArrayList<Integer>(entries.keySet());
  }
}
