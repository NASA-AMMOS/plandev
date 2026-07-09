package gov.nasa.jpl.aerie.merlin.driver.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class Subscriptions<TopicRef, QueryRef> {
  /** The set of topics depended upon by a given query. */
  private final Map<QueryRef, Set<TopicRef>> topicsByQuery = new HashMap<>();

  /** An index of queries by subscribed topic. */
  @DerivedFrom("topicsByQuery")
  private final Map<TopicRef, Set<QueryRef>> queriesByTopic = new HashMap<>();

  public Set<TopicRef> getTopics() {
    return queriesByTopic.keySet();
  }

  // This method takes ownership of `topics`; the set should not be referenced after calling this method.
  public void subscribeQuery(final QueryRef query, final Set<TopicRef> topics) {
    this.topicsByQuery.put(query, topics);

    for (final var topic : topics) {
      this.queriesByTopic.computeIfAbsent(topic, $ -> new HashSet<>()).add(query);
    }
  }

  public void unsubscribeQuery(final QueryRef query) {
    final var topics = this.topicsByQuery.remove(query);

    for (final var topic : topics) {
      final var queries = this.queriesByTopic.get(topic);
      if (queries == null) continue;

      queries.remove(query);
      if (queries.isEmpty()) this.queriesByTopic.remove(topic);
    }
  }

  /**
   * Get an unmodifiable set of topics for the specified query
   * @param query the query whose subscribed topics are returned
   * @return the topics to which the specified query is subscribed as an unmodifiable Set
   */
  public Set<TopicRef> getTopics(final QueryRef query) {
    var topics = topicsByQuery.get(query);
    if (topics == null) return Collections.emptySet();
    return Collections.unmodifiableSet(topics);
  }

  private Set<QueryRef> removeTopic(final TopicRef topic) {
    final var queries = Optional
        .ofNullable(this.queriesByTopic.remove(topic))
        .orElseGet(Collections::emptySet);

    for (final var query : queries) unsubscribeQuery(query);

    return queries;
  }

  public Set<QueryRef> invalidateTopic(final TopicRef topic) {
    return removeTopic(topic);
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

  @Override
  public String toString() {
    return "Subscriptions{" +
           "topicsByQuery=" + topicsByQuery +
           ", queriesByTopic=" + queriesByTopic +
           '}';
  }
}
