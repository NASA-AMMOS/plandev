package gov.nasa.jpl.aerie.command_expansion.model.fsw;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;

import java.util.List;
import java.util.stream.IntStream;

import static gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;

public class GlobalVariables {
    public static final int NUM_GLOBAL_INTS = 32;
    public static final int NUM_GLOBAL_FLOATS = 32;
    public static final int NUM_GLOBAL_STRINGS = 32;

    public final List<MutableResource<Discrete<Integer>>> globalInts;
    public final List<MutableResource<Discrete<Double>>> globalFloats;
    public final List<MutableResource<Discrete<String>>> globalStrings;

    public GlobalVariables(Registrar registrar) {
        var prefix = "globals.";

        globalInts = IntStream.range(0, NUM_GLOBAL_INTS)
                .mapToObj(i -> {
                    var resource = discreteResource(0);
                    registrar.discrete(String.format("%sG%02dINT", prefix, i), resource, $int());
                    return resource;
                })
                .toList();
        globalFloats = IntStream.range(0, NUM_GLOBAL_FLOATS)
                .mapToObj(i -> {
                    var resource = discreteResource(0.0);
                    registrar.discrete(String.format("%sG%02dFLT", prefix, i), resource, $double());
                    return resource;
                })
                .toList();
        globalStrings = IntStream.range(0, NUM_GLOBAL_STRINGS)
                .mapToObj(i -> {
                    var resource = discreteResource("");
                    registrar.discrete(String.format("%sG%02dSTR", prefix, i), resource, string());
                    return resource;
                })
                .toList();
    }

    public MutableResource<Discrete<Integer>> getGlobalInt(String name) {
        return getGlobal(name, "INT", globalInts);
    }

    public MutableResource<Discrete<Double>> getGlobalFloat(String name) {
        return getGlobal(name, "FLT", globalFloats);
    }

    public MutableResource<Discrete<String>> getGlobalString(String name) {
        return getGlobal(name, "STR", globalStrings);
    }

    private <R> R getGlobal(String name, String typeSuffix, List<R> vars) {
        if (!name.matches("G\\d\\d" + typeSuffix)) {
            throw new IllegalArgumentException(
                    String.format("There is no variable named %s", name));
        }
        int n = Integer.parseInt(name.substring(1, 3));
        if (n < 0 || n > vars.size()) {
            throw new IllegalArgumentException(
                    String.format("There is no variable named %s", name));
        }
        return vars.get(n);
    }
}
