package gov.nasa.ammos.plandev.merlin.driver;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.function.UnaryOperator;

/**
 * Loads a JAR whose class references name packages this runtime has since renamed,
 * rewriting those references on the way in.
 *
 * <p> The caller supplies the redirect policy: given an internal class name, return the
 * name this runtime knows it by, or the same name to leave it alone. What counts as
 * "renamed" differs by artifact — see {@link LegacyModelCompat} and
 * {@link LegacyProcedureCompat} — but the mechanics do not.
 *
 * <p> A redirected class is loaded from the parent rather than defined here. That matters
 * for JARs that bundle their own copy of the runtime: the stale copy must stay unloaded so
 * the running one wins, which is exactly what parent-first delegation did for these JARs
 * before the rename.
 */
public final class RemappingJarClassLoader extends URLClassLoader {
  private final UnaryOperator<String> redirect;
  private final Remapper remapper;

  public RemappingJarClassLoader(final URL jar, final UnaryOperator<String> redirect) {
    super(new URL[] {jar});
    this.redirect = redirect;
    this.remapper = new Remapper() {
      @Override public String map(final String internalName) { return redirect.apply(internalName); }
    };
  }

  @Override
  protected Class<?> findClass(final String name) throws ClassNotFoundException {
    final var internal = name.replace('.', '/');
    final var redirected = redirect.apply(internal);
    if (!redirected.equals(internal)) {
      // This runtime owns the class; the JAR's copy of it is stale by definition.
      return getParent().loadClass(redirected.replace('/', '.'));
    }

    final var resource = findResource(internal + ".class");
    if (resource == null) throw new ClassNotFoundException(name);

    final byte[] original;
    try (final var stream = resource.openStream()) {
      original = stream.readAllBytes();
    } catch (final IOException ex) {
      throw new ClassNotFoundException(name, ex);
    }

    // No COMPUTE_FRAMES: a rename cannot invalidate a stack map, and computing one would
    // need types this loader cannot resolve. ClassRemapper rewrites the frames it copies.
    final var writer = new ClassWriter(0);
    new ClassReader(original).accept(new ClassRemapper(writer, remapper), 0);
    final var bytes = writer.toByteArray();

    return defineClass(name, bytes, 0, bytes.length);
  }
}
