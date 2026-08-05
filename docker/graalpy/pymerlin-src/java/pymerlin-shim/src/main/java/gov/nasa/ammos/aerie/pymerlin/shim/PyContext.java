package gov.nasa.ammos.aerie.pymerlin.shim;

import gov.nasa.jpl.aerie.graalpy.SharedPythonEngine;
import org.graalvm.polyglot.Context;
import org.graalvm.python.embedding.GraalPyResources;

import java.nio.file.Path;

/**
 * Builds a GraalPy {@link Context} pointed at the external-directory layout
 * that the worker image provisions at build time (roadmap §5.2):
 *
 * <pre>
 *   ${PYMERLIN_RESOURCES}/
 *     venv/   ← pymerlin + numpy + spiceypy, pip-installed by install.sh
 *     src/    ← on the Python path by GraalPyResources convention; must exist
 * </pre>
 *
 * The root path is read from the {@code PYMERLIN_RESOURCES} environment
 * variable (set in the Dockerfile), falling back to the {@code pymerlin.resources}
 * system property, then to {@code /opt/pymerlin/python-resources}.
 *
 * <p>Phase 2 note: {@link GraalBridge} currently loads the model by adding its extracted
 * directory to {@code sys.path} directly (which works because {@link #build} sets
 * {@code allowAllAccess(true)}), rather than copying it into {@code ${root}/src} as
 * roadmap §5.3 ultimately calls for. That relocation is deferred — it is only strictly
 * needed once filesystem access is sandboxed — but the source is now cleaned up on
 * bridge close either way, which is the item §5.3 flagged.
 *
 * <p>One {@link Context} is still created (and closed) per simulation ({@code
 * instantiate()} call) — that per-simulation isolation is intentional and stays,
 * since Python module globals and model state must never leak between unrelated
 * simulations. What's shared (roadmap §11.3) is the underlying {@link
 * org.graalvm.polyglot.Engine}, via {@link SharedPythonEngine}: a JVM-wide singleton
 * that holds only the compiled-code cache and language configuration, no
 * simulation-specific state, so every {@code Context} built here amortizes the
 * interpreter/stdlib/pymerlin compile cost across every simulation this worker or
 * server process ever runs — instead of paying it fresh each time. A shared {@code
 * Context} (rather than a shared {@code Engine}) was considered and rejected: it
 * would carry {@code sys.modules} and model-level globals across simulations of
 * different plans/models, which nothing has validated as safe. {@link
 * SharedPythonEngine} deliberately lives in its own plandev module rather than here,
 * because a class bundled inside the uploaded model JAR gets reloaded — and its
 * statics reset — by the fresh {@code URLClassLoader} {@code MissionModelLoader}
 * creates for every simulation; only a class the worker's own parent classloader
 * supplies can actually stay shared.
 */
public final class PyContext {

    private PyContext() {}

    public static Context build() {
        Path resourcesRoot = resolveResourcesRoot();

        return GraalPyResources
            .contextBuilder(resourcesRoot)
            .engine(SharedPythonEngine.get())
            .allowAllAccess(true)
            .allowCreateThread(true)   // _ActivityRunner still uses Python threads in Phase 2
            .build();
    }

    static Path resolveResourcesRoot() {
        String env = System.getenv("PYMERLIN_RESOURCES");
        if (env != null && !env.isBlank()) return Path.of(env);

        String prop = System.getProperty("pymerlin.resources");
        if (prop != null && !prop.isBlank()) return Path.of(prop);

        return Path.of("/opt/pymerlin/python-resources");
    }
}
