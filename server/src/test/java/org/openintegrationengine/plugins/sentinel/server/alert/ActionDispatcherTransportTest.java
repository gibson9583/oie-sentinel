/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;

@DisplayName("ActionDispatcher transport wall clock")
class ActionDispatcherTransportTest {

    @BeforeEach
    void startExecutors() {
        ActionDispatcher.startDispatchExecutor();
    }

    @AfterEach
    void stopExecutors() {
        ActionDispatcher.shutdownDispatchExecutor();
    }

    @Test
    @DisplayName("the wall clock cancels an in-flight synchronous transport and releases its caller")
    void timeoutCancelsSynchronousTransport() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AlertSender blocked = (action, event, payload) -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        };

        Action action = action(ActionType.EMAIL);
        long started = System.nanoTime();
        Exception failure = assertThrows(Exception.class,
                () -> ActionDispatcher.sendWithWallClock(
                        blocked, action, null, null, 100, TimeUnit.MILLISECONDS));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS),
                "future.cancel(true) must interrupt a cooperative synchronous sender");
        assertTrue(failure.getMessage().contains("did not complete within 100 milliseconds"),
                failure.getMessage());
        assertTrue(elapsedMillis < 2_000,
                "the dispatch caller must be released by the wall clock, not the transport");
    }

    @Test
    @DisplayName("transport failures are unwrapped and every action type uses the same boundary")
    void failuresAreUnwrappedForEveryTransportType() {
        AtomicInteger calls = new AtomicInteger();
        AlertSender failing = (action, event, payload) -> {
            calls.incrementAndGet();
            throw new Exception("transport-specific failure");
        };

        for (ActionType type : ActionType.values()) {
            Exception failure = assertThrows(Exception.class,
                    () -> ActionDispatcher.sendWithWallClock(
                            failing, action(type), null, null, 1, TimeUnit.SECONDS));
            assertEquals("transport-specific failure", failure.getMessage());
        }
        assertEquals(ActionType.values().length, calls.get());
    }

    @Test
    @DisplayName("interrupt-ignoring calls time out and exhaust capacity without creating a backlog")
    void uncooperativeTransportsFailLaterCallsFast() throws Exception {
        int capacity = 4;
        CountDownLatch entered = new CountDownLatch(capacity);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(capacity);
        CountDownLatch callersDone = new CountDownLatch(capacity);
        AtomicInteger timedOut = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        AlertSender stubborn = (action, event, payload) -> {
            entered.countDown();
            try {
                while (true) {
                    try {
                        release.await();
                        return;
                    } catch (InterruptedException ignored) {
                        interrupted.set(true);
                        // Model a third-party client that swallows cancellation.
                    }
                }
            } finally {
                exited.countDown();
            }
        };

        for (int i = 0; i < capacity; i++) {
            Thread caller = new Thread(() -> {
                try {
                    ActionDispatcher.sendWithWallClock(stubborn,
                            action(ActionType.EMAIL), null, null,
                            100, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    if (e.getMessage().contains("did not complete within 100 milliseconds")) {
                        timedOut.incrementAndGet();
                    }
                } finally {
                    callersDone.countDown();
                }
            }, "transport-timeout-test-caller");
            caller.start();
        }

        try {
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(callersDone.await(2, TimeUnit.SECONDS));
        assertEquals(capacity, timedOut.get());
        assertTrue(interrupted.get(), "timeout cancellation must still be attempted");

        Exception exhausted = assertThrows(Exception.class,
                () -> ActionDispatcher.sendWithWallClock(stubborn,
                        action(ActionType.EMAIL), null, null,
                        1, TimeUnit.SECONDS));
        assertTrue(exhausted.getMessage().contains("transport capacity is exhausted"),
                exhausted.getMessage());

        } finally {
            release.countDown();
            assertTrue(exited.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutWhilePolicyIsStillLoadingDefersWithoutCallingTransport() throws Exception {
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        assertThrows(ActionDispatcher.DispatchDeferredException.class,
                () -> ActionDispatcher.sendWithWallClock((action, event, payload) -> sends.incrementAndGet(),
                        action(ActionType.EMAIL), null, null, 100, TimeUnit.MILLISECONDS, () -> {
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } finally {
                                exited.countDown();
                            }
                        }));
        assertTrue(exited.await(1, TimeUnit.SECONDS));
        assertEquals(0, sends.get());
    }

    @Test
    void actualTransportThreadRechecksBeforeCallingSender() throws Exception {
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean policyAllows = new AtomicBoolean(true);
        AtomicInteger sends = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                ActionDispatcher.sendWithWallClock((action, event, payload) -> sends.incrementAndGet(),
                        action(ActionType.EMAIL), null, null, 2, TimeUnit.SECONDS, () -> {
                            assertTrue(Thread.currentThread().getName().startsWith("sentinel-transport-"));
                            checking.countDown();
                            try {
                                assertTrue(resume.await(1, TimeUnit.SECONDS));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            if (!policyAllows.get()) {
                                throw new ActionDispatcher.DispatchDeferredException("policy changed");
                            }
                        });
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        caller.start();
        try {
            assertTrue(checking.await(1, TimeUnit.SECONDS));
            policyAllows.set(false);
        } finally {
            resume.countDown();
            caller.join(3_000);
        }
        assertEquals(0, sends.get());
        assertTrue(failure.get() instanceof ActionDispatcher.DispatchDeferredException,
                String.valueOf(failure.get()));
    }

    private static Action action(ActionType type) {
        Action action = new Action();
        action.setName(type.name().toLowerCase() + " transport");
        action.setActionType(type);
        return action;
    }
}
