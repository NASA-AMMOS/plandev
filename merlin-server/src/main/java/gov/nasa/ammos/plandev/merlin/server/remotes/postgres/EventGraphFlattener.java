package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class EventGraphFlattener {
  private EventGraphFlattener() {}

  public static <T> List<Pair<String, T>> flatten(final EventGraph<T> graph) {
    final var accumulator = new ArrayList<Pair<String, T>>();

    graph.evaluate(new TagLogger.Trait<>(), TagLogger.Atom::new)
         .accept(Tag.origin(), (tag, event) -> accumulator.add(Pair.of(tag.serialize(), event)));

    return accumulator;
  }

  private enum BranchType {
    Sequentially,
    Concurrently;

    private BranchType opposite() {
      return switch (this) {
        case Sequentially -> Concurrently;
        case Concurrently -> Sequentially;
      };
    }
  }

  private record Tag(Optional<Tag> prefix, BranchType branchType, int index) {
    public static Tag origin() {
      return new Tag(Optional.empty(), BranchType.Sequentially, 1);
    }

    public Tag bump() {
      return new Tag(this.prefix, this.branchType, this.index + 1);
    }

    public Tag descend() {
      return new Tag(Optional.of(this), this.branchType.opposite(), 1);
    }

    public String serialize() {
      final var builder = new StringBuilder();
      this.serialize(builder);
      return builder.toString();
    }

    public void serialize(final StringBuilder builder) {
      this.prefix.ifPresent($ -> $.serialize(builder));
      builder.append('.');
      builder.append(this.index);
    }
  }

  private interface Log<T> {
    void put(Tag tag, T event);
  }

  private sealed interface TagLogger<T> {
    Tag accept(Tag tag, Log<T> log);

    record Atom<T>(T event) implements TagLogger<T> {
      @Override
      public Tag accept(final Tag tag, final Log<T> log) {
        log.put(tag, this.event);
        return tag.bump();
      }
    }

    record Empty<T>() implements TagLogger<T> {
      @Override
      public Tag accept(final Tag tag, final Log<T> log) {
        return tag;
      }
    }

    record Sequentially<T>(TagLogger<T> prefix, TagLogger<T> suffix) implements TagLogger<T> {
      @Override
      public Tag accept(final Tag tag, final Log<T> log) {
        return switch (tag.branchType()) {
          case Sequentially -> {
            yield this.suffix.accept(this.prefix.accept(tag, log), log);
          }

          case Concurrently -> {
            this.suffix.accept(this.prefix.accept(tag.descend(), log), log);
            yield tag.bump();
          }
        };
      }
    }

    record Concurrently<T>(TagLogger<T> left, TagLogger<T> right) implements TagLogger<T> {
      @Override
      public Tag accept(final Tag tag, final Log<T> log) {
        return switch (tag.branchType()) {
          case Sequentially -> {
            this.right.accept(this.left.accept(tag.descend(), log), log);
            yield tag.bump();
          }

          case Concurrently -> {
            yield this.right.accept(this.left.accept(tag, log), log);
          }
        };
      }
    }

    record Trait<T>() implements EffectTrait<TagLogger<T>> {
      @Override
      public TagLogger<T> empty() {
        return new Empty<>();
      }

      @Override
      public TagLogger<T> sequentially(final TagLogger<T> prefix, final TagLogger<T> suffix) {
        if (prefix instanceof Empty<T>) return suffix;
        if (suffix instanceof Empty<T>) return prefix;

        return new Sequentially<>(prefix, suffix);
      }

      @Override
      public TagLogger<T> concurrently(final TagLogger<T> left, final TagLogger<T> right) {
        if (left instanceof Empty<T>) return right;
        if (right instanceof Empty<T>) return left;

        return new Concurrently<>(left, right);
      }
    }
  }
}
