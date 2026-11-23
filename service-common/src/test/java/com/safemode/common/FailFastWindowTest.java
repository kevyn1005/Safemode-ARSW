package com.safemode.common;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailFastWindowTest {

    @Test
    void startsClosedOpensOnFailureAndClosesWhenTheWindowPasses() throws InterruptedException {
        FailFastWindow window = new FailFastWindow(Duration.ofMillis(60));
        assertFalse(window.isOpen(), "sin fallas no hay ventana");

        window.recordFailure();
        assertTrue(window.isOpen(), "tras una falla se falla al instante");

        Thread.sleep(90);
        assertFalse(window.isOpen(), "pasada la ventana se vuelve a intentar");
    }

    @Test
    void aSuccessClosesTheWindowImmediately() {
        FailFastWindow window = new FailFastWindow(Duration.ofSeconds(30));
        window.recordFailure();
        assertTrue(window.isOpen());
        window.recordSuccess();
        assertFalse(window.isOpen());
    }
}
