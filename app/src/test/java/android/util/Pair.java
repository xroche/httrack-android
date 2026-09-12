package android.util;

import java.util.Objects;

/** Stands in for the mockable android.jar, whose Pair constructor drops both fields. */
public class Pair<F, S> {
  public final F first;
  public final S second;

  public Pair(final F first, final S second) {
    this.first = first;
    this.second = second;
  }

  public static <A, B> Pair<A, B> create(final A a, final B b) {
    return new Pair<A, B>(a, b);
  }

  /** Value equality over both fields, as the real class has. */
  @Override
  public boolean equals(final Object o) {
    if (!(o instanceof Pair)) {
      return false;
    }
    final Pair<?, ?> other = Pair.class.cast(o);
    return Objects.equals(other.first, first)
        && Objects.equals(other.second, second);
  }

  @Override
  public int hashCode() {
    // The real class XORs the two, so a swapped pair collides here too.
    return (first == null ? 0 : first.hashCode())
        ^ (second == null ? 0 : second.hashCode());
  }

  @Override
  public String toString() {
    return "Pair{" + String.valueOf(first) + " " + String.valueOf(second) + "}";
  }
}
