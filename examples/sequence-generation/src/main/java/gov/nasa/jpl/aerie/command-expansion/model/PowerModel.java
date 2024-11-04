package gov.nasa.jpl.aerie.command_expansion.model;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers.$enum;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.approximateAsLinear;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.polynomialResource;

public class PowerModel {
    public final MutableResource<Discrete<HeaterState>> heater = discreteResource(HeaterState.OFF);
    public final MutableResource<Polynomial> batterySOC = polynomialResource(100);

    public PowerModel(Registrar registrar) {
        var prefix = "power.";
        registrar.discrete(prefix + "heater", heater, $enum(HeaterState.class));
        registrar.real(prefix + "batterySOC", approximateAsLinear(batterySOC));
    }

    public enum HeaterState {
        OFF,
        STANDBY,
        ON
    }
}
