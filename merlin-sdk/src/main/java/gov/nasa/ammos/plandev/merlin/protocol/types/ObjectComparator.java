package gov.nasa.jpl.aerie.merlin.protocol.types;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

/**
 * A generic comparator to use when you don't have one and need one.  This handles some common Collection types.
 * There may be some others out there that could simplify or improve on this.
 */
public class ObjectComparator implements Comparator<Object> {
  private static gov.nasa.jpl.aerie.merlin.protocol.types.ObjectComparator INSTANCE = null;

  public static gov.nasa.jpl.aerie.merlin.protocol.types.ObjectComparator getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new gov.nasa.jpl.aerie.merlin.protocol.types.ObjectComparator();
    }
    return INSTANCE;
  }

  @SuppressWarnings("unchecked")
  @Override
  public int compare(Object o1, Object o2) {
    if (o1 == o2) return 0;
    if (o1 == null) return -1;
    if (o2 == null) return 1;

    // Compare using Comparable if applicable.
    // o1's comparator may assume the type of o2, causing a ClassCastException.
    // This could result in poor performance if the exception is thrown regularly.
    try {
      if (o1 instanceof Comparable && o2 instanceof Comparable) {
        return ((Comparable) o1).compareTo(o2);
      }
    } catch (ClassCastException t) {
    }

    if (o1 instanceof Set && !(o1 instanceof SortedSet) && o2 instanceof Set && !(o2 instanceof SortedSet)) {
      return Integer.compare(o1.hashCode(), o2.hashCode());  // this is sometimes sum of element hashcodes
    }

    if (o1 instanceof Iterable && o2 instanceof Iterable) {
      var i1 = ((Iterable<?>) o1).iterator();
      var i2 = ((Iterable<?>) o2).iterator();
      while (i1.hasNext() && i2.hasNext()) {
        int c = compare(i1.next(), i2.next());
        if (c != 0) return c;
      }
      if (i1.hasNext()) return 1;
      if (i2.hasNext()) return -1;
      return 0;
    }

    if (o1 instanceof Map<?, ?> && o2 instanceof Map<?, ?>) {
      return compare(((Map<?, ?>) o1).entrySet(), ((Map<?, ?>) o2).entrySet());
    }

    if (o1 instanceof Map.Entry<?, ?> && o2 instanceof Map.Entry<?, ?>) {
      int c = compare(((Map.Entry<?, ?>) o1).getKey(), ((Map.Entry<?, ?>) o2).getKey());
      if (c != 0) return c;
      c = compare(((Map.Entry<?, ?>) o1).getValue(), ((Map.Entry<?, ?>) o2).getValue());
      return c;
    }

    // Fallback comparison
    int classComparison = o1.getClass().getName().compareTo(o2.getClass().getName());
    if (classComparison != 0) {
      return classComparison;
    }

    return Integer.compare(o1.hashCode(), o2.hashCode());
//      return Integer.compare(System.identityHashCode(o1), System.identityHashCode(o2));
  }
}
