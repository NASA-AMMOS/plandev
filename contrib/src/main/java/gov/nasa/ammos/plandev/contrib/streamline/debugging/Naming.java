package gov.nasa.jpl.aerie.contrib.streamline.debugging;

import org.apache.commons.lang3.mutable.MutableObject;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/**
 * Allows anything that uses reference equality to be given a name.
 *
 * <p>
 *   By handling naming in a static auxiliary data structure, we achieve several goals:
 *   1) Naming doesn't bloat interfaces like Resource and DynamicsEffect.
 *   2) Names can be applied to classes and interfaces after-the-fact,
 *      including applying names to classes and interfaces that can't be modified, like library code.
 *   3) Naming is nevertheless globally available.
 *      (Unlike passing the name in a parallel parameter, for example.)
 * </p>
 */
public final class Naming {
  private Naming() {}

  // Use a WeakHashMap so that naming a thing doesn't prevent it from being garbage-collected.
  private static final WeakHashMap<Object, Function<NamingContext, Optional<String>>> NAMES = new WeakHashMap<>();

  private record NamingContext(Set<Object> visited, Function<Object, Optional<String>> anonymousName) {
    NamingContext visit(Object thing) {
      var newVisited = new HashSet<>(visited);
      newVisited.add(thing);
      return new NamingContext(newVisited, anonymousName);
    }

    public NamingContext() {
      this(Set.of(), $ -> Optional.empty());
    }

    public NamingContext(String anonymousName) {
      this(Set.of(), anonymousName == null ? $ -> Optional.of(Objects.toString($)) : $ -> Optional.of(anonymousName));
    }
  }

  /**
   * Register a name for thing, as a function of args' names.
   * If any of the args are anonymous, so is this thing.
   */
  public static <T> T name(T thing, String nameFormat, Object... args) {
    // Only capture weak references to arguments, so we don't leak memory
    var args$ = Arrays.stream(args).map(WeakReference::new).toArray(WeakReference[]::new);
    NAMES.put(thing, context -> {
      Object[] argNames = new Object[args$.length];
      for (int i = 0; i < args$.length; ++i) {
        var argName$ = Optional.ofNullable(args$[i].get())
                .flatMap(argRef -> getName(argRef, context));
        if (argName$.isEmpty()) {
          return context.anonymousName.apply(args$[i].get());
        }
        argNames[i] = argName$.get();
      }
      return Optional.of(nameFormat.formatted(argNames));
    });
    return thing;
  }

  /**
   * Get the name for thing.
   * If thing has no registered name and no synonyms,
   * returns empty.
   */
  public static Optional<String> getName(Object thing) {
    return getName(thing, new NamingContext());
  }

  // TODO - build a version of getName, or make this the default behavior, (maybe if anonymousName is null?)
  //   which calls Object.toString on things that don't otherwise have a name.
  //   This supports the use of literals and dynamics objects in effect names,
  //   without needing to eagerly compute their names, which involves costly string ops.

  /**
   * Get the name for thing.
   * Use anonymousName for anything without a name instead of returning empty.
   */
  public static String getName(Object thing, String anonymousName) {
    // This expression never throws, because context always has a name available.
    return getName(thing, new NamingContext(anonymousName)).orElseThrow();
  }

  private static Optional<String> getName(Object thing, NamingContext context) {
    return context.visited.contains(thing)
            ? context.anonymousName.apply(thing)
            : NAMES.getOrDefault(thing, ctx -> ctx.anonymousName.apply(thing)).apply(context.visit(thing));
  }

  public static String argsFormat(Collection<?> collection) {
    return "(" + IntStream.range(0, collection.size()).mapToObj($ -> "%s").collect(Collectors.joining(", ")) + ")";
  }
}
