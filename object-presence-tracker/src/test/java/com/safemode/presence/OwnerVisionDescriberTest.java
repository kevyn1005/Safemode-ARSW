package com.safemode.presence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OwnerVisionDescriberTest {

    @Test
    void describeArmsPorDefectoDevuelveNull() {
        OwnerVisionDescriber describer = crop -> "persona con camisa roja";

        assertEquals("persona con camisa roja", describer.describe(null));
        assertNull(describer.describeArms(null));
    }
}