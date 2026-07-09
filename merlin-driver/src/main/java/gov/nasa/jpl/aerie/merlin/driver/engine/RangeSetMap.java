package gov.nasa.jpl.aerie.merlin.driver.engine;

import com.google.common.collect.Range;
import com.google.common.collect.TreeRangeMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.*;
import org.apache.commons.lang3.tuple.Pair;

public class RangeSetMap<K extends Comparable<K>, V> {
  private final RangeMap<K, Set<V>> rangeMap;

  public RangeSetMap() {
    this.rangeMap = TreeRangeMap.create();
  }

  public RangeSetMap(RangeMap<K, Set<V>> map) {
    this.rangeMap = map;
  }

  public RangeSetMap(RangeSetMap r) {
    this();
    merge(r);
  }

  public RangeSetMap<K, V> subMap(Range<K> range) {
    return new RangeSetMap(rangeMap.subRangeMap(range));
  }

  public void set(Range<K> range, Set<V> value) {
    rangeMap.putCoalescing(range, value);
  }

  public void add(Range<K> range, V value) {
    addAll(range, Sets.newHashSet(value));
  }

  public void addAll(Range<K> range, Set<V> value) {
    rangeMap.subRangeMap(range).merge(range, value, (existingSet, newSet) -> {
      Set<V> mergedSet = new HashSet<>(existingSet);
      mergedSet.addAll(newSet);
      return mergedSet;
    });

    coalesce(rangeMap.subRangeMap(range));
    // coalesce around range
    var entry = rangeMap.getEntry(range.lowerEndpoint());
    if (entry != null) rangeMap.putCoalescing(entry.getKey(), entry.getValue());
    entry = rangeMap.getEntry(range.upperEndpoint());
    if (entry != null) rangeMap.putCoalescing(entry.getKey(), entry.getValue());
  }

  public void merge(RangeSetMap<K, V> r) {
    if (r == null) return;
    r.asMapOfRanges().entrySet().forEach(e -> addAll(e.getKey(), e.getValue()));
  }

  public void remove(Range<K> range, V value) {
    removeAll(range, Sets.newHashSet(value));
  }

  public void removeAll(Range<K> range, Set<V> value) {
    var list = new ArrayList<Pair<Range<K>, Set<V>>>();
    for (var e : rangeMap.subRangeMap(range).asMapOfRanges().entrySet()) {
      if (e.getKey().isConnected(range)) {
        var newSet = new HashSet<V>(e.getValue());
        newSet.removeAll(value);
        list.add(Pair.of(e.getKey().intersection(range), newSet));
      }
    }
    for (var p : list) {
      rangeMap.putCoalescing(p.getLeft(), p.getRight());
    }

    coalesce(rangeMap.subRangeMap(range));  // this is really just to remove entries with empty sets
  }

  private void coalesce() {
    coalesce(this.rangeMap);
  }
  private static <K extends Comparable<K>, V> void coalesce(final RangeMap<K, Set<V>> rangeMap) {
    if (rangeMap.asMapOfRanges().isEmpty()) return;
    final LinkedHashMap<Range<K>, Set<V>> mapOfRanges = new LinkedHashMap<>(rangeMap.asMapOfRanges());

    Map.Entry<Range<K>, Set<V>> previous = null;
    for (Map.Entry<Range<K>, Set<V>>  current : mapOfRanges.entrySet()) {
      if (previous != null &&
          previous.getValue().equals(current.getValue()) &&
          previous.getKey().isConnected(current.getKey())) {

        Range<K> mergedRange = previous.getKey().span(current.getKey());
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

  public Map<Range<K>, Set<V>> asMapOfRanges() {
    return rangeMap.asMapOfRanges();
  }

  public boolean isEmpty() {
    return asMapOfRanges().isEmpty();
  }

  public Range<K> span() {
    return rangeMap.span();
  }

  @Override
  public String toString() {
    return rangeMap.asMapOfRanges().toString();
  }

  public Set<V> get(K k) {
    var x = rangeMap.get(k);
    if (x == null) return Collections.emptySet();
    return x;
  }
}
