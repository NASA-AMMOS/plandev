package gov.nasa.ammos.plandev.merlin.driver;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;

/**
 * Checks that everything a legacy JAR asks of this runtime is still there.
 *
 * <p> Rewriting package names only works while the classes behind them are otherwise
 * unchanged. Rename a method, drop a field, narrow a return type, and the remap still
 * succeeds -- the JAR links, and then fails somewhere inside a simulation or a constraint
 * evaluation with {@link NoSuchMethodError} or {@link AbstractMethodError}, a long way from
 * the change that caused it.
 *
 * <p> So this walks a JAR's bytecode for every reference it makes into a package this
 * runtime supplies, and resolves each one against the running classes. Checking references
 * rather than comparing whole API surfaces keeps it honest in both directions: it cannot be
 * defeated by an API that grows, and it cannot be satisfied by an API that keeps a method
 * the JAR needs while dropping one it does not.
 *
 * <p> Held by the tests that load the checked-in fixtures. When one starts failing, the
 * compatibility window has closed for that vintage, and the answer is to say so -- refuse
 * the JAR with a message naming what is missing -- not to widen the remap.
 */
public final class LegacyAbiCheck {
  private LegacyAbiCheck() {}

  /** A reference a legacy JAR makes that this runtime no longer satisfies. */
  public record Unsatisfied(String owner, String member, String descriptor, String reason) {
    @Override public String toString() {
      return owner + "#" + member + descriptor + "  (" + reason + ")";
    }
  }

  /**
   * Every reference in {@code jarPath} into a redirected package that cannot be resolved
   * against {@code runtime}. Empty means this JAR's vintage is still supported.
   */
  public static List<Unsatisfied> unsatisfiedReferences(
      final Path jarPath,
      final UnaryOperator<String> redirect,
      final ClassLoader runtime)
  throws IOException {
    return unsatisfied(references(jarPath, redirect), redirect, runtime);
  }

  /**
   * The redirected references {@code jarPath} makes, as sorted lines of
   * {@code owner name descriptor}.
   *
   * <p> Recording these is how the check covers a JAR far larger than anything worth
   * committing: the test fixtures are trimmed to what a load touches, but drift can break a
   * class the fixture dropped, so the reference set is taken from the full original JAR and
   * checked in as text. It is also reviewable, which a JAR is not -- a diff shows exactly
   * which API a new vintage started or stopped depending on.
   */
  public static List<String> references(final Path jarPath, final UnaryOperator<String> redirect)
  throws IOException {
    final var references = collect(jarPath, redirect);
    return references.stream()
        .map(r -> (r.isMethod() ? "M " : "F ") + r.owner() + " " + r.name() + " " + r.descriptor())
        .distinct()
        .sorted()
        .toList();
  }

  /** Resolve recorded references (see {@link #references}) against the running classes. */
  public static List<Unsatisfied> unsatisfied(
      final List<String> references,
      final UnaryOperator<String> redirect,
      final ClassLoader runtime) {
    final var parsed = new ArrayList<Reference>();
    for (final var line : references) {
      if (line.isBlank() || line.startsWith("#")) continue;
      final var parts = line.split(" ");
      if (parts.length != 4) throw new IllegalArgumentException("malformed reference: " + line);
      parsed.add(new Reference(parts[1], parts[2], parts[3], "M".equals(parts[0])));
    }
    return resolveAll(parsed, redirect, runtime);
  }

  private static List<Unsatisfied> resolveAll(
      final List<Reference> references,
      final UnaryOperator<String> redirect,
      final ClassLoader runtime) {
    final var remapper = new Remapper() {
      @Override public String map(final String internalName) { return redirect.apply(internalName); }
    };
    final var unsatisfied = new ArrayList<Unsatisfied>();
    for (final var reference : references) {
      final var owner = redirect.apply(reference.owner());
      final Class<?> target;
      try {
        target = Class.forName(owner.replace('/', '.'), false, runtime);
      } catch (final ClassNotFoundException | LinkageError ex) {
        unsatisfied.add(new Unsatisfied(owner, reference.name(), reference.descriptor(), "class not found"));
        continue;
      }
      final var wanted = reference.isMethod()
          ? remapper.mapMethodDesc(reference.descriptor())
          : remapper.mapDesc(reference.descriptor());
      if (!resolves(target, reference, wanted)) {
        unsatisfied.add(new Unsatisfied(owner, reference.name(), wanted,
            reference.isMethod() ? "no such method" : "no such field"));
      }
    }
    return unsatisfied;
  }

  private static Set<Reference> collect(final Path jarPath, final UnaryOperator<String> redirect)
  throws IOException {
    final var references = new LinkedHashSet<Reference>();
    try (final var jar = new JarFile(jarPath.toFile())) {
      final var entries = jar.entries();
      while (entries.hasMoreElements()) {
        final var entry = entries.nextElement();
        if (!entry.getName().endsWith(".class")) continue;
        // A class this runtime owns is shadowed, never loaded; its references are moot.
        final var self = entry.getName().substring(0, entry.getName().length() - ".class".length());
        if (!redirect.apply(self).equals(self)) continue;

        try (final var stream = jar.getInputStream(entry)) {
          new ClassReader(stream.readAllBytes()).accept(collector(references, redirect), ClassReader.SKIP_FRAMES);
        }
      }
    }
    return references;
  }

  /**
   * Members can be inherited, so the whole hierarchy counts as "present" -- including
   * {@link Object}, which an interface owner does not reach by superclass or interface
   * links but whose methods are callable on one all the same.
   */
  private static boolean resolves(final Class<?> from, final Reference reference, final String wanted) {
    final var seen = new LinkedHashSet<Class<?>>();
    final var queue = new ArrayList<Class<?>>();
    queue.add(from);
    queue.add(Object.class);
    while (!queue.isEmpty()) {
      final var type = queue.remove(0);
      if (type == null || !seen.add(type)) continue;

      if (reference.isMethod()) {
        if ("<init>".equals(reference.name())) {
          for (final var c : type.getDeclaredConstructors()) {
            if (Type.getConstructorDescriptor(c).equals(wanted)) return true;
          }
        } else {
          for (final var m : type.getDeclaredMethods()) {
            if (m.getName().equals(reference.name()) && Type.getMethodDescriptor(m).equals(wanted)) return true;
          }
        }
      } else {
        for (final var f : type.getDeclaredFields()) {
          if (f.getName().equals(reference.name()) && Type.getDescriptor(f.getType()).equals(wanted)) return true;
        }
      }

      queue.add(type.getSuperclass());
      queue.addAll(List.of(type.getInterfaces()));
    }
    return false;
  }

  private record Reference(String owner, String name, String descriptor, boolean isMethod) {}

  private static ClassVisitor collector(final Set<Reference> into, final UnaryOperator<String> redirect) {
    return new ClassVisitor(Opcodes.ASM9) {
      private void record(final String owner, final String name, final String descriptor, final boolean method) {
        // Arrays carry their element type in the descriptor; only named owners resolve.
        if (owner == null || owner.startsWith("[")) return;
        if (redirect.apply(owner).equals(owner)) return;   // not a package we redirect
        into.add(new Reference(owner, name, descriptor, method));
      }

      @Override
      public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
        return new MethodVisitor(Opcodes.ASM9) {
          @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
            record(owner, name, desc, true);
          }
          @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
            record(owner, name, desc, false);
          }
          @Override
          public void visitInvokeDynamicInsn(String n2, String d2, org.objectweb.asm.Handle bsm, Object... args) {
            // A lambda's target method is named in the bootstrap arguments, not the owner.
            for (final var arg : args) {
              if (arg instanceof org.objectweb.asm.Handle handle) {
                record(handle.getOwner(), handle.getName(), handle.getDesc(), true);
              }
            }
          }
        };
      }
    };
  }

  /**
   * Writes a baseline: {@code <jar> <out> model|procedure}.
   *
   * <p> Must run on the classpath of the service that will check it. The redirect is decided
   * against what the runtime supplies, and the services do not ship the same libraries, so a
   * baseline taken elsewhere records a different set and will not line up. Each module wires
   * this to a {@code regenerateAbiBaseline} task using its own test runtime classpath.
   */
  public static void main(final String[] args) throws IOException {
    final var jar = Path.of(args[0]);
    final var out = Path.of(args[1]);
    final var runtime = LegacyAbiCheck.class.getClassLoader();
    final var redirect = "model".equals(args[2])
        ? LegacyModelCompat.redirect()
        : LegacyProcedureCompat.redirect(runtime);

    final var references = references(jar, redirect);
    final var lines = new ArrayList<String>(List.of(
        "# ABI surface of " + jar.getFileName() + " (PlanDev 4.3.0), as this service loads it.",
        "# Each line is a reference the JAR makes into a package this runtime now owns under a",
        "# different name. The test resolves every one against the running classes; a failure",
        "# means the 4.3 compatibility window has closed for this kind of JAR.",
        "#",
        "# Recorded from the full 4.3 artifact, not the trimmed test fixture, which carries only",
        "# a subset. Regenerate with ./gradlew :<module>:regenerateAbiBaseline -- and only on the",
        "# classpath of the service that checks it.",
        "# " + references.size() + " references"));
    lines.addAll(references);
    java.nio.file.Files.write(out, lines);
    System.out.println("wrote " + out + " (" + references.size() + " references)");
  }
}
