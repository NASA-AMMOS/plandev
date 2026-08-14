package gov.nasa.jpl.aerie.contrib.streamline.debugging;

import java.util.*;

import static java.util.Collections.newSetFromMap;
import static java.util.stream.Collectors.joining;

public final class Dependencies {
  private Dependencies() {}

  // Use a WeakHashMap so that describing a thing's dependencies
  // doesn't prevent it from being garbage-collected.
  private static final WeakHashMap<Object, Set<Object>> DEPENDENCIES = new WeakHashMap<>();
  private static final WeakHashMap<Object, Set<Object>> DEPENDENTS = new WeakHashMap<>();
  private static final String ANONYMOUS_NAME = "...";

  /**
   * Register that dependent depends on dependency.
   */
  public static void addDependency(Object dependent, Object dependency) {
    // Use WeakSet = newSetFromMap + WeakHashMap, to only weakly reference dependencies.
    DEPENDENCIES.computeIfAbsent(dependent, $ -> newSetFromMap(new WeakHashMap<>())).add(dependency);
    DEPENDENTS.computeIfAbsent(dependency, $ -> newSetFromMap(new WeakHashMap<>())).add(dependent);
  }

  /**
   * Get all registered dependencies of dependent.
   */
  public static Set<Object> getDependencies(Object dependent) {
    return DEPENDENCIES.getOrDefault(dependent, Set.of());
  }

  /**
   * Get all registered dependents of dependency.
   */
  public static Set<Object> getDependents(Object dependency) {
    return DEPENDENTS.getOrDefault(dependency, Set.of());
  }

  /**
   * Build a string formatting the dependency graph starting from source.
   * <p>
   *     The result is in <a href="https://mermaid.js.org/">Mermaid</a> syntax
   * </p>
   *
   * @param elideAnonymousNodes When true, remove anonymous nodes and replace them with their dependencies.
   */
  public static String describeDependencyGraph(Object source, boolean elideAnonymousNodes) {
    return describeDependencyGraph(List.of(source), elideAnonymousNodes);
  }

  /**
   * Build a string formatting the entire dependency graph.
   * <p>
   *     The result is in <a href="https://mermaid.js.org/">Mermaid</a> syntax
   * </p>
   *
   * @param elideAnonymousNodes When true, remove anonymous nodes and replace them with their dependencies.
   */
  public static String describeDependencyGraph(boolean elideAnonymousNodes) {
    return describeDependencyGraph(DEPENDENCIES.keySet(), elideAnonymousNodes);
  }

  /**
   * Build a string formatting the dependency graph starting from sources.
   * <p>
   *     The result is in DOT syntax.
   * </p>
   *
   * @param elideAnonymousNodes When true, remove anonymous nodes and replace them with their dependencies.
   */
  public static String describeDependencyGraph(Collection<?> sources, boolean elideAnonymousNodes) {
    Map<Object, Set<Object>> dependencyGraph = new HashMap<>();
    Map<Object, Set<Object>> dependentGraph = new HashMap<>();
    for (var source : sources) {
      buildClosure(source, dependencyGraph, dependentGraph);
    }

    if (elideAnonymousNodes) {
      // Collapse anonymous nodes out of the graph
      for (Object node : new ArrayList<>(dependencyGraph.keySet())) {
        if (nodeName(node).equals(ANONYMOUS_NAME)) {
          var dependencies = dependencyGraph.remove(node);
          var dependents = dependentGraph.remove(node);
          for (Object dependent : dependents) {
            dependencyGraph.get(dependent).remove(node);
            dependencyGraph.get(dependent).addAll(dependencies);
          }
          for (Object dependency : dependencies) {
            dependentGraph.get(dependency).remove(node);
            dependentGraph.get(dependency).addAll(dependents);
          }
        }
      }
    }

    // Describe the result
    Map<Object, String> nodeIds = new HashMap<>();
    StringBuilder builder = new StringBuilder();
    builder.append("digraph dependencies {\n");
    final Set<Object> visited = new HashSet<>();
    // To produce good-looking graphs, visit nodes in topological order starting from roots.
    dependencyGraph
            .keySet()
            .stream()
            .filter(node -> dependentGraph.getOrDefault(node, Set.of()).isEmpty())
            .forEach(root -> describeSubgraph(root, dependencyGraph, nodeIds, visited, builder));
    // Then, visit nodes starting from any sources involved in a cycle
    // Finally, visit any remaining nodes arbitrarily
    while (visited.size() < dependencyGraph.size()) {
      var root = sources
              .stream()
              .filter(n -> !visited.contains(n))
              .map((Object $) -> $)
              .findFirst().or(() -> dependencyGraph.keySet()
                      .stream()
                      .filter(n -> !visited.contains(n))
                      .findAny())
              .orElseThrow();
      describeSubgraph(root, dependencyGraph, nodeIds, visited, builder);
    }
    builder.append("}");
    return builder.toString();
  }

  private static void describeSubgraph(Object root, Map<Object, Set<Object>> dependencyGraph, Map<Object, String> nodeIds, Set<Object> visited, StringBuilder builder) {
    for (var node : topologicalSort(root, dependencyGraph, visited)) {
      var dependencies = dependencyGraph.get(node);
      builder.append("  ")
              .append(nodeId(node, nodeIds))
              .append(" [label=\"")
              .append(scrub(nodeName(node)))
              .append("\"]\n");
      for (var dependency : dependencies) {
        builder.append("  ")
                .append(nodeId(node, nodeIds))
                .append(" -> ")
                .append(nodeId(dependency, nodeIds))
                .append(";\n");
      }
    }
  }

  private static void buildClosure(Object node, Map<Object, Set<Object>> dependencyGraph, Map<Object, Set<Object>> dependentGraph) {
    if (dependencyGraph.containsKey(node)) return;
    var dependencies = getDependencies(node);
    dependencyGraph.computeIfAbsent(node, $ -> new HashSet<>()).addAll(dependencies);
    dependentGraph.computeIfAbsent(node, $ -> new HashSet<>());
    for (var dependency : dependencies) {
      dependentGraph.computeIfAbsent(dependency, $ -> new HashSet<>()).add(node);
    }
    for (var dependency : dependencies) {
      buildClosure(dependency, dependencyGraph, dependentGraph);
    }
  }

  /**
   * Cycle-tolerant depth-first topological sorting
   */
  private static List<Object> topologicalSort(Object root, Map<Object, Set<Object>> dependencyGraph, Set<Object> finished) {
    // Algorithm from https://en.wikipedia.org/wiki/Topological_sorting#Depth-first_search
    Set<Object> visited = new HashSet<>();
    List<Object> results = new LinkedList<>();
    tsVisit(root, dependencyGraph, visited, finished, results);
    return results;
  }

  private static void tsVisit(Object node, Map<Object, Set<Object>> dependencyGraph, Set<Object> visited, Set<Object> finished, List<Object> results) {
    if (finished.contains(node)) return;
    if (visited.contains(node)) return;
    visited.add(node);
    for (var dependency : dependencyGraph.get(node)) {
      tsVisit(dependency, dependencyGraph, visited, finished, results);
    }
    visited.remove(node);
    finished.add(node);
    results.add(0, node);
  }

  private static String nodeName(Object node) {
    return Naming.getName(node, ANONYMOUS_NAME);
  }

  private static String nodeId(Object node, Map<Object, String> nodeIds) {
    return nodeIds.computeIfAbsent(node, $ -> "N" + nodeIds.size());
  }

  private static String scrub(String label) {
    return label.replace("\"", "\\\"");
  }
}
