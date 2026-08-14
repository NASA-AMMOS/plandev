package gov.nasa.jpl.aerie.spice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import spice.basic.CSPICE;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SpiceTests {

    @BeforeAll
    public static void loadSpice() {
        SpiceLoader.loadSpice();
    }

    @Test
    public void getSpeedOfLight() {
        assertEquals(299792, CSPICE.clight(), 1);
    }

}
