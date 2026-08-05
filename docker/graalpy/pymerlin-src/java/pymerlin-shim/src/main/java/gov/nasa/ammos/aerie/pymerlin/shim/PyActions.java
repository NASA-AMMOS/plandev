package gov.nasa.ammos.aerie.pymerlin.shim;

import org.graalvm.polyglot.Value;

import java.util.function.BooleanSupplier;

/**
 * The Java host object handed to Python via {@link GraalBridge} (roadmap §6/§7).
 *
 * <p>A Python activity running in-process calls these methods <em>synchronously, on the Java
 * {@code ThreadedTask} thread it is executing on</em>:
 * <ul>
 *   <li>{@code delay(micros)} → {@code ModelActions.delay(...)} parks this thread.</li>
 *   <li>{@code emit(resource, value)} → routes to the resource's topic (Phase 3 path, kept
 *       for backwards compat).</li>
 *   <li>{@code emitCell(cellIndex, value)} → emits to the cell's topic by index (Phase 4).</li>
 *   <li>{@code ask(cellIndex)} → {@code ModelActions.ask(cellId)} — returns the current cell
 *       value and, during {@code waitUntil} condition evaluation, registers a read dependency
 *       so the engine re-evaluates when the topic changes (Phase 4, §7).</li>
 *   <li>{@code spawnActivity(name, argsJson)} → a fresh child {@code ThreadedTask}.</li>
 *   <li>{@code callActivity(name, argsJson)} → a fresh child, blocking the caller.</li>
 *   <li>{@code waitUntil(condition)} → wraps the Python predicate in an Aerie {@code Condition}
 *       and yields to the engine; the engine re-evaluates on the engine thread whenever a
 *       dependency changes (Phase 4, §7). GraalPy auto-wraps the Python callable to
 *       {@code BooleanSupplier}.</li>
 * </ul>
 *
 * <p>A single instance is shared across every activity: its methods delegate to
 * {@code ModelActions.*}, which act on whichever {@code ThreadedTask} thread is currently
 * calling, so no per-activity state is needed here.
 */
public final class PyActions {

    private final ShimModelType shim;

    PyActions(ShimModelType shim) {
        this.shim = shim;
    }

    public void delay(long micros) {
        shim.directDelay(micros);
    }

    public void emit(String resource, String value) {
        shim.applyEmit(resource, value);
    }

    public String ask(int cellIndex) {
        return shim.directAsk(cellIndex);
    }

    /**
     * Read an evolving cell's value as the live Python object rather than its {@code str()}.
     * <p>
     * Evolving cells can hold types that do not survive a string round-trip -- a
     * {@code Duration} stringifies to {@code "+00:00:00.0000.0"}, which nothing on the
     * Python side can parse back. Returning the object avoids the conversion entirely.
     * Returns {@code null} for non-evolving cells, whose callers use {@link #ask} instead.
     */
    public Object askObject(int cellIndex) {
        return shim.directAskObject(cellIndex);
    }

    public void emitCell(int cellIndex, String value) {
        shim.directEmitCell(cellIndex, value);
    }

    /**
     * Write an evolving cell as the live Python object, the counterpart to
     * {@link #askObject}. Avoids the str() round-trip that cannot represent every Python
     * value type an evolving cell may hold.
     */
    public void emitCellObject(int cellIndex, Value value) {
        shim.directEmitCellObject(cellIndex, value);
    }

    public void setRate(int cellIndex, double rate) {
        shim.directSetRate(cellIndex, rate);
    }

    public void waitUntil(BooleanSupplier condition) {
        shim.directWaitUntil(condition);
    }

    public void spawnActivity(String name, String argsJson) {
        shim.directSpawn(name, argsJson);
    }

    public void callActivity(String name, String argsJson) {
        shim.directCall(name, argsJson);
    }
}
