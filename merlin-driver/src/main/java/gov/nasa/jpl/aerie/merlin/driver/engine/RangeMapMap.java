package gov.nasa.jpl.aerie.merlin.driver.engine;

import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;

public class RangeMapMap<K1 extends Comparable<K1>, K2, V> {  // TODO -- should this just extend RangeSetMap?
  private final RangeMap<K1, Map<K2, V>> rangeMap;

  public RangeMapMap() {
    this.rangeMap = TreeRangeMap.create();
  }

  public RangeMapMap(RangeMap<K1, Map<K2, V>> map) {
    this.rangeMap = map;
  }

  public RangeMapMap(RangeMapMap r) {
    this();
    merge(r);
  }


  public RangeMapMap<K1, K2, V> subMap(Range<K1> range) {
    return new RangeMapMap<>(rangeMap.subRangeMap(range));
  }

  public void set(Range<K1> range, Map<K2, V> value) {
    rangeMap.putCoalescing(range, value);
  }

  public void add(Range<K1> range, K2 key, V value) {
    var m = new HashMap<K2, V>();
    m.put(key, value);
    addAll(range, m);
  }

  public void addAll(Range<K1> range, Map<K2, V> value) {
    rangeMap.subRangeMap(range).merge(range, value, (existingMap, newMap) -> {
      Map<K2, V> mergedMap = new HashMap<>(existingMap);
      mergedMap.putAll(newMap);
      return mergedMap;
    });

    // coalesce within range
    coalesce(rangeMap.subRangeMap(range));
    // coalesce around range
    var entry = rangeMap.getEntry(range.lowerEndpoint());
    if (entry != null) rangeMap.putCoalescing(entry.getKey(), entry.getValue());
    entry = rangeMap.getEntry(range.upperEndpoint());
    if (entry != null) rangeMap.putCoalescing(entry.getKey(), entry.getValue());
  }

  public void merge(RangeMapMap<K1, K2, V> r) {
    r.asMapOfRanges().entrySet().forEach(e -> addAll(e.getKey(), e.getValue()));
  }

  public void remove(Range<K1> range, K2 key) {
    var m = new HashSet<K2>();
    m.add(key);
    removeAll(range, m);
  }

  public void removeAll(Range<K1> range, Collection<K2> value) {
    var list = new ArrayList<Pair<Range<K1>, Map<K2, V>>>();
    for (var e : rangeMap.subRangeMap(range).asMapOfRanges().entrySet()) {
      if (e.getKey().isConnected(range)) {
        var newMap = new HashMap<K2, V>(e.getValue());
        for (var key : value) {
          newMap.remove(key);
        }
        list.add(Pair.of(e.getKey().intersection(range), newMap));
      }
    }
    for (var p : list) {
      rangeMap.putCoalescing(p.getLeft(), p.getRight());
    }

    coalesce(rangeMap.subRangeMap(range));  // this is really just to remove entries with empty maps
  }

  public void remove(Range<K1> range, K2 key, V value) {
    var m = new HashMap<K2, V>();
    m.put(key, value);
    removeAll(range, m);
  }
  public void removeAll(Range<K1> range, Map<K2, V> value) {
    var list = new ArrayList<Pair<Range<K1>, Map<K2, V>>>();
    for (var e : rangeMap.subRangeMap(range).asMapOfRanges().entrySet()) {
      if (e.getKey().isConnected(range)) {
        var newMap = new HashMap<K2, V>(e.getValue());
        for (var entry : value.entrySet()) {
          newMap.remove(entry.getKey(), entry.getValue());
        }
        list.add(Pair.of(e.getKey().intersection(range), newMap));
      }
    }
    for (var p : list) {
      rangeMap.putCoalescing(p.getLeft(), p.getRight());
    }

    coalesce(rangeMap.subRangeMap(range));  // this is really just to remove entries with empty maps
  }

  private void coalesce() {
    coalesce(this.rangeMap);
  }
  private static <K1 extends Comparable<K1>, K2, V> void coalesce(final RangeMap<K1, Map<K2, V>> rangeMap) {
    if (rangeMap.asMapOfRanges().isEmpty()) return;
    final LinkedHashMap<Range<K1>, Map<K2, V>> mapOfRanges = new LinkedHashMap<>(rangeMap.asMapOfRanges());

    Map.Entry<Range<K1>, Map<K2, V>> previous = null;
    for (Map.Entry<Range<K1>, Map<K2, V>>  current : mapOfRanges.entrySet()) {
      if (previous != null &&
          equals(previous.getValue(), current.getValue()) &&
          previous.getKey().isConnected(current.getKey())) {
        Range<K1> mergedRange = previous.getKey().span(current.getKey());
        rangeMap.remove(previous.getKey());
        rangeMap.remove(current.getKey());
        rangeMap.put(mergedRange, previous.getValue());
        previous = Map.entry(mergedRange, previous.getValue());
      } else if (current.getValue() == null || current.getValue().isEmpty()) {
        rangeMap.remove(current.getKey());
      } else {
        previous = current;
      }
    }
  }

  public static <KK, VV> boolean equals(Map<KK, VV> m1, Map<KK, VV> m2) {
    if (m1 == m2) return true;
    if (m1 == null || m2 == null) return false;
    if (m1.size() != m2.size()) return false;
    if (m1 instanceof SortedMap<KK,VV> om1 && m2 instanceof SortedMap<KK,VV> om2) {
      var i1 = m1.entrySet().iterator();
      var i2 = m2.entrySet().iterator();
      while (i1.hasNext()) {
        var e1 = i1.next();
        var e2 = i2.next();
        if (!Objects.equals(e1.getKey(), e2.getKey())) return false;
        if (!Objects.equals(e1.getValue(), e2.getValue())) return false;
      }
      return true;
    }
    for (KK k : m1.keySet()) { // This could be faster for ordered maps
      VV v1 = m1.get(k);
      VV v2 = m2.get(k);
      if (!Objects.equals(v1, v2)) return false;
    }
    return true;
  }

  public static <KK, VV> boolean contains(Collection<Map<KK, VV>> c, Map<KK, VV> m) {
    if (c == null) return false;
    //if (c.contains(m)) return true;
    if (c instanceof SortedSet<Map<KK,VV>> set) {
      var ts = set.tailSet(m);
      if (equals(ts.first(), m)) return true;
      var hs = set.headSet(m);
      if (equals(hs.last(), m)) return true;
      return false;
    }
    for (var mm : c) {
      if (equals(mm, m)) return true;
    }
    return false;
  }


  public Map<Range<K1>, Map<K2, V>> asMapOfRanges() {
    return rangeMap.asMapOfRanges();
  }

  public boolean isEmpty() {
    return asMapOfRanges().isEmpty();
  }

  public Range<K1> span() {
    return rangeMap.span();
  }

  @Override
  public String toString() {
    return rangeMap.asMapOfRanges().toString();
  }

  public Map<K2, V> get(K1 k) {
    var x = rangeMap.get(k);
    if (x == null) return Collections.emptyMap();
    return x;
  }
}
