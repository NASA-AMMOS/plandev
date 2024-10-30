package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.*;
import java.util.function.Consumer;

public final class InitialConditionManager {
    private InitialConditionManager() {}

    private static boolean initialized = false;
    private static InitialConditions initialConditions;
    private static Consumer<Map<String, SerializedValue>> finconHandler;
    private static List<Consumer<FinalConditions>> finconHooks;

    public static void init(InitialConditions initialConditions, Consumer<Map<String, SerializedValue>> finconHandler) {
        if (initialized) {
            throw new IllegalStateException("InitialConditionManager has already been initialized");
        }

        InitialConditionManager.initialConditions = initialConditions;
        InitialConditionManager.finconHandler = finconHandler;
        InitialConditionManager.finconHooks = new ArrayList<>();
        initialized = true;
    }

    // The "correct" way to get an initial value also registers a way to write the final value.
    // This is intended to remind the modeler that these operations are closely coupled.
    public static <T> T register(InconBehavior<T> behavior) {
        if (!initialized) {
            throw new IllegalStateException("InitialConditionManager has not been initialized");
        }

        var result =  behavior.getIncon(initialConditions);
        // This looks admittedly strange, but the intent is for result to be something like a resource,
        // which is a stable handle for a state that changes over the course of the simulation.
        // This closure captures that stable handle, with the intent of querying it later to capture the final state.
        finconHooks.add($ -> behavior.writeFincon(result, $));
        return result;
    }

    public static void writeFincon() {
        var transparentFincons = new HashMap<String, SerializedValue>();
        // opaqueFincons is a write-only view of transparentFincons
        var opaqueFincons = FinalConditions.of(transparentFincons);
        // Each fincon hook appends its portion of the fincons
        for (var finconHook : finconHooks) {
            finconHook.accept(opaqueFincons);
        }
        // Finally we write the full object out
        finconHandler.accept(transparentFincons);
    }


    public interface InitialConditions {
        Optional<SerializedValue> get(String key);

        static InitialConditions of(Map<String, SerializedValue> map) {
            return key -> Optional.ofNullable(map.get(key));
        }
    }

    public interface FinalConditions {
        void put(String key, SerializedValue value);

        static FinalConditions of(Map<String, SerializedValue> map) {
            return (key, value) -> {
                if (map.putIfAbsent(key, value) != null) {
                    throw new IllegalStateException(String.format(
                            "Final condition has already been written for %s", key));
                }
            };
        }
    }

}
