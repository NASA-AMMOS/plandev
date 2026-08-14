package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public final class EventGraphUnflattener {
  private EventGraphUnflattener() {}

  public static <T> EventGraph<T> unflatten(final List<Pair<String, T>> events) throws InvalidTagException {
    final var iter = events.iterator();
    if (!iter.hasNext()) return EventGraph.empty();

    final Trie<T> trie;
    {
      final var event = iter.next();

      final var tag = Tag.deserialize(event.getKey());
      trie = Trie.of(Optional.of(tag), event.getValue());
    }

    while (iter.hasNext()) {
      final var event = iter.next();

      final var tag = Tag.deserialize(event.getKey());
      try {
        trie.add(Optional.of(tag), event.getValue());
      } catch (final Trie.CollisionException ex) {
        throw new InvalidTagException("Tag `%s` collides with another.".formatted(tag));
      }
    }

    return trie.sequentially();
  }

  private record Tag(int index, Optional<Tag> suffix) {
    public static Tag deserialize(final String subject) throws InvalidTagException {
      return new TagReader(subject).readTag();
    }
  }

  private sealed interface Trie<T> {
    record Leaf<T>(T event) implements Trie<T> {}

    record Node<T>(TreeMap<Integer, Trie<T>> children) implements Trie<T> {}

    static <T> Trie<T> of(final Optional<Tag> tag$, T event) {
      if (tag$.isEmpty()) return new Leaf<>(event);
      final var tag = tag$.get();

      return new Node<>(new TreeMap<>(Map.of(tag.index(), Trie.of(tag.suffix(), event))));
    }

    default void add(Optional<Tag> tag$, T event) throws CollisionException {
      if (this instanceof Leaf<T>) {
        throw new CollisionException();
      } else if (this instanceof Node<T> self) {
        if (tag$.isEmpty()) throw new CollisionException();
        final var tag = tag$.get();

        if (self.children.containsKey(tag.index())) {
          self.children.get(tag.index()).add(tag.suffix(), event);
        } else {
          self.children.put(tag.index(), Trie.of(tag.suffix(), event));
        }
      } else {
        throw new IllegalArgumentException("Unexpected variant %s of type %s".formatted(this, Trie.class));
      }
    }

    default EventGraph<T> sequentially() {
      if (this instanceof Leaf<T> self) {
        return EventGraph.atom(self.event);
      } else if (this instanceof Node<T> self) {
        var graph = EventGraph.<T>empty();
        for (final var entry : self.children().descendingMap().entrySet()) {
          graph = EventGraph.sequentially(entry.getValue().concurrently(), graph);
        }
        return graph;
      } else {
        throw new IllegalArgumentException("Unexpected variant %s of type %s".formatted(this, Trie.class));
      }
    }

    default EventGraph<T> concurrently() {
      if (this instanceof Leaf<T> self) {
        return EventGraph.atom(self.event);
      } else if (this instanceof Node<T> self) {
        var graph = EventGraph.<T>empty();
        for (final var entry : self.children().entrySet()) {
          graph = EventGraph.concurrently(graph, entry.getValue().sequentially());
        }
        return graph;
      } else {
        throw new IllegalArgumentException("Unexpected variant %s of type %s".formatted(this, Trie.class));
      }
    }

    class CollisionException extends Exception {}
  }


  private static final class TagReader {
    private final String subject;
    private int offset = 0;

    public TagReader(final String subject) {
      this.subject = Objects.requireNonNull(subject);
    }

    public boolean eof() {
      return this.subject.length() <= this.offset;
    }

    public char peek() {
      return this.subject.charAt(this.offset);
    }

    public char advance() {
      return this.subject.charAt(this.offset++);
    }

    public void readChar(final char ch) throws InvalidTagException {
      if (this.eof()) throw new InvalidTagException("Expected '" + ch + "' at offset " + this.offset);
      if (this.peek() != ch) throw new InvalidTagException("Expected '" + ch + "' at offset " + this.offset);
      this.advance();
    }

    public int readInt() throws InvalidTagException {
      int acc;

      {
        if (this.eof()) throw new InvalidTagException("Expected a digit at offset " + this.offset);

        var digit = Character.digit(this.peek(), 10);
        if (digit == -1) throw new InvalidTagException("Expected a digit at offset " + this.offset);
        this.advance();

        acc = digit;
      }

      while (true) {
        if (this.eof()) break;

        var digit = Character.digit(this.peek(), 10);
        if (digit == -1) break;
        this.advance();

        acc = (acc * 10) + digit;
      }

      return acc;
    }

    public Tag readTag() throws InvalidTagException {
      this.readChar('.');
      final var index = this.readInt();

      final var suffix = (!this.eof())
          ? Optional.of(this.readTag())
          : Optional.<Tag>empty();

      return new Tag(index, suffix);
    }
  }

  public static class InvalidTagException extends Exception {
    public InvalidTagException(final String msg) {
      super(msg);
    }
  }
}
