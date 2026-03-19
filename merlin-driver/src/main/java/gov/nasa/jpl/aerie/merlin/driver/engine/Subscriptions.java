package gov.nasa.jpl.aerie.merlin.driver.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class Subscriptions<TopicRef, QueryRef> {
  /** The set of topics depended upon by a given query. */
  private final Map<QueryRef, Set<TopicRef>> topicsByQuery = new HashMap<>();

  /** An index of queries by subscribed topic. */
  @DerivedFrom("topicsByQuery")
  private final Map<TopicRef, Set<QueryRef>> queriesByTopic = new HashMap<>();

  // This method takes ownership of `topics`; the set should not be referenced after calling this method.
  public void subscribeQuery(final QueryRef query, final Set<TopicRef> topics) {
    final var oldTopics = this.topicsByQuery.put(query, topics);

    // If the subscription is unchanged, skip the expensive reverse-index rebuild.
    if (oldTopics != null && oldTopics.equals(topics)) return;

    // Remove stale reverse-index entries for topics no longer referenced.
    if (oldTopics != null) {
      for (final var oldTopic : oldTopics) {
        if (topics.contains(oldTopic)) continue;
        final var queries = this.queriesByTopic.get(oldTopic);
        if (queries == null) continue;
        queries.remove(query);
        if (queries.isEmpty()) this.queriesByTopic.remove(oldTopic);
      }
    }

    // Add reverse-index entries for all current topics.
    for (final var topic : topics) {
      this.queriesByTopic.computeIfAbsent(topic, $ -> new HashSet<>()).add(query);
    }
  }

  public void unsubscribeQuery(final QueryRef query) {
    final var topics = this.topicsByQuery.remove(query);
    if (topics == null) return;

    for (final var topic : topics) {
      final var queries = this.queriesByTopic.get(topic);
      if (queries == null) continue;

      queries.remove(query);
      if (queries.isEmpty()) this.queriesByTopic.remove(topic);
    }
  }

  public Set<QueryRef> invalidateTopic(final TopicRef topic) {
    final var queries = this.queriesByTopic.remove(topic);
    if (queries == null || queries.isEmpty()) return Collections.emptySet();

    for (final var query : queries) {
      final var topics = this.topicsByQuery.get(query);
      if (topics == null) continue;
      // Only fully unsubscribe — remove from other topics' reverse index
      for (final var otherTopic : topics) {
        if (otherTopic.equals(topic)) continue;
        final var otherQueries = this.queriesByTopic.get(otherTopic);
        if (otherQueries == null) continue;
        otherQueries.remove(query);
        if (otherQueries.isEmpty()) this.queriesByTopic.remove(otherTopic);
      }
      this.topicsByQuery.remove(query);
    }

    return queries;
  }

  public void clear() {
    this.topicsByQuery.clear();
    this.queriesByTopic.clear();
  }

  public Subscriptions<TopicRef, QueryRef> duplicate() {
    final Subscriptions<TopicRef, QueryRef> subscriptions = new Subscriptions<>();
    for (final var entry : this.topicsByQuery.entrySet()) {
      final var query = entry.getKey();
      final var topics = entry.getValue();
      subscriptions.subscribeQuery(query, new HashSet<>(topics));
    }
    return subscriptions;
  }
}
