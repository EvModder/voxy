package me.cortex.voxy.common.thread;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PerThreadContextExecutorTest {
    @Test
    void failedContextInitializationDoesNotBlockShutdown() {
        var failures = new AtomicInteger();
        var executor = new PerThreadContextExecutor(
                () -> { throw new IllegalStateException("Initialization failed"); },
                error -> failures.incrementAndGet());
        assertTrue(executor.run());
        assertEquals(1, failures.get());
        assertTimeoutPreemptively(Duration.ofSeconds(2), executor::shutdown);
    }
}
