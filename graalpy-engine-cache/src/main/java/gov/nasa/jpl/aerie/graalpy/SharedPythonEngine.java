package gov.nasa.jpl.aerie.graalpy;

import org.graalvm.polyglot.Engine;

/**
 * A single GraalVM polyglot Engine, shared JVM-wide across every simulation this
 * worker/server process ever runs. Holds no simulation- or model-specific state — only
 * the compiled-code cache and language configuration — so it is safe to share even
 * though each simulation still gets its own fresh, isolated {@code Context} (pymerlin
 * roadmap §11.3).
 *
 * <p>Must live on the worker's/server's own classpath, not bundled into an uploaded
 * model JAR: {@code MissionModelLoader} creates a fresh child {@code URLClassLoader}
 * per simulation, and a class living inside the model JAR would be reloaded (and its
 * statics reset) every single time — which is exactly why a naive static cache inside
 * pymerlin-shim's own {@code GraalBridge} could never actually hit.
 */
public final class SharedPythonEngine {
    private static final Engine ENGINE = Engine.newBuilder("python").build();

    private SharedPythonEngine() {}

    public static Engine get() {
        return ENGINE;
    }
}
