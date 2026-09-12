package android.util;

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
}
