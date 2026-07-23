package gov.nasa.jpl.powerModel;

import gov.nasa.jpl.resource.*;

import java.util.Arrays;

/** Resource declarations for the power/downlink demo adaptation. */
public class Res extends ResourceDeclaration {
    public static DoubleResource  BatterySoC = new DoubleResource(100.0, "power");   // percent, starts full
    public static IntegerResource DataBuffer = new IntegerResource();               // Mbit, starts 0
    public static DoubleResource  SolarPower = new DoubleResource(0.0, "power");     // watts
    public static StringResource  Mode = new StringResource(
        Arrays.asList(new String[]{"Idle", "Science", "Downlink", "Recharge"}));     // defaults to Idle
}
