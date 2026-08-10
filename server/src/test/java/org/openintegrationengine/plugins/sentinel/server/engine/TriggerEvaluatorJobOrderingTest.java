/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/**
 * The dependency ordering that runs at the top of every evaluator tick:
 * monitors must be evaluated after the monitor that suppresses them, because
 * a dependent's at-creation suppression check reads its parent's trigger
 * state — evaluated in the wrong order, the check reads the parent's state
 * from the <em>previous</em> tick, and a child alert born in the same tick
 * as its parent's problem escapes suppression exactly once, on exactly the
 * tick the feature exists for.
 *
 * <p>The other property worth pinning is the failure mode. MonitorService
 * rejects cycles at save time, but {@code suppressed_by_monitor_id} is a
 * plain column and a direct database edit can produce one — and the ordering
 * must then degrade, never drop. A monitor silently omitted from the
 * returned list is a monitor that stops being evaluated at all: its open
 * problems freeze, its breaches go unseen, and nothing in any log says why.
 * Every case here therefore asserts membership as well as order.</p>
 *
 * <p>{@code orderByDependency} is deliberately package-private for exactly
 * this test; it is pure (list in, list out, no repository access), so no
 * mocks are involved.</p>
 */
@DisplayName("TriggerEvaluatorJob.orderByDependency")
class TriggerEvaluatorJobOrderingTest {

    // ---------------------------------------------------------------- helpers

    private static Monitor monitor(int id, Integer suppressedByMonitorId) {
        Monitor m = new Monitor();
        m.setId(id);
        m.setName("monitor-" + id);
        m.setSuppressedByMonitorId(suppressedByMonitorId);
        return m;
    }

    private static List<Integer> ids(List<Monitor> monitors) {
        List<Integer> ids = new ArrayList<>();
        for (Monitor monitor : monitors) {
            ids.add(monitor.getId());
        }
        return ids;
    }

    /** Asserts {@code parentId} is evaluated before {@code childId} in the result. */
    private static void assertBefore(List<Monitor> ordered, int parentId, int childId) {
        List<Integer> ids = ids(ordered);
        int parentIndex = ids.indexOf(parentId);
        int childIndex = ids.indexOf(childId);
        assertTrue(parentIndex >= 0, "parent " + parentId + " missing from " + ids);
        assertTrue(childIndex >= 0, "child " + childId + " missing from " + ids);
        assertTrue(parentIndex < childIndex,
                "parent " + parentId + " must precede child " + childId + " in " + ids);
    }

    @Nested
    @DisplayName("acyclic configurations")
    class Acyclic {

        @Test
        @DisplayName("no dependencies preserves natural order")
        void independentMonitorsKeepNaturalOrder() {
            // Natural (repository) order is part of the contract: operators
            // reason about tick behavior from the monitor list, and an
            // ordering pass that shuffled independent monitors would make
            // every tick's log a fresh puzzle.
            List<Monitor> ordered = TriggerEvaluatorJob.orderByDependency(
                    List.of(monitor(1, null), monitor(2, null), monitor(3, null)));
            assertEquals(List.of(1, 2, 3), ids(ordered));
        }

        @Test
        @DisplayName("a linear chain listed child-first comes out parents-first")
        void linearChainIsReversedIntoDependencyOrder() {
            // 3 is suppressed by 2, 2 by 1, and the repository returned them
            // in the worst order. Every link must flip: a partially sorted
            // chain would leave the middle monitor reading its parent's
            // previous-tick state.
            List<Monitor> ordered = TriggerEvaluatorJob.orderByDependency(
                    List.of(monitor(3, 2), monitor(2, 1), monitor(1, null)));
            assertEquals(List.of(1, 2, 3), ids(ordered));
        }

        @Test
        @DisplayName("a parent outside the enabled set is no dependency at all")
        void missingParentIsIgnored() {
            // The parent may be deleted, or merely disabled — either way it is
            // absent from the enabled list this method receives, its trigger
            // states are not being refreshed, and the suppression check fails
            // open. Holding the child back waiting for it would order the tick
            // around a monitor that is not in the tick.
            List<Monitor> ordered = TriggerEvaluatorJob.orderByDependency(
                    List.of(monitor(5, 99), monitor(6, null)));
            assertEquals(List.of(5, 6), ids(ordered));
        }

        @Test
        @DisplayName("two children of one parent both land after it")
        void sharedParentPrecedesBothChildren() {
            List<Monitor> ordered = TriggerEvaluatorJob.orderByDependency(
                    List.of(monitor(10, 30), monitor(20, 30), monitor(30, null)));
            assertBefore(ordered, 30, 10);
            assertBefore(ordered, 30, 20);
            assertEquals(3, ordered.size());
        }
    }

    @Nested
    @DisplayName("cycles degrade, never drop")
    class Cycles {

        @Test
        @DisplayName("a two-node cycle falls back to natural order with nothing lost")
        void twoNodeCycleKeepsEveryMonitor() {
            // Neither order is right, so the tie-break is the predictable one
            // — but the load-bearing assertion is membership: a dropped
            // monitor is one that silently stops being evaluated forever.
            List<Monitor> ordered = TriggerEvaluatorJob.orderByDependency(
                    List.of(monitor(1, 2), monitor(2, 1)));
            assertEquals(List.of(1, 2), ids(ordered));
        }

        @Test
        @DisplayName("a cycle does not disturb the acyclic monitors around it")
        void cyclePlusAcyclicPrefixDegradesLocally() {
            // The healthy dependency (8 before 7) must still be honored, the
            // cycle members (3, 4) appended in natural order after it, and
            // all four monitors present. Degrading the whole tick to natural
            // order because two monitors are miswired would reintroduce the
            // one-tick suppression escape for every correctly configured pair.
            List<Monitor> ordered = TriggerEvaluatorJob.orderByDependency(
                    List.of(monitor(7, 8), monitor(8, null), monitor(3, 4), monitor(4, 3)));
            assertEquals(List.of(8, 7, 3, 4), ids(ordered));
        }

        @Test
        @DisplayName("a monitor suppressed by itself is kept, not orphaned")
        void selfSuppressionIsAOneNodeCycle() {
            // suppressed_by_monitor_id = its own id is reachable by direct DB
            // edit. It can never be "placed after its parent", so it must ride
            // the cycle path — evaluated in natural position, never dropped.
            List<Monitor> ordered = TriggerEvaluatorJob.orderByDependency(
                    List.of(monitor(1, null), monitor(2, 2), monitor(3, null)));
            assertEquals(List.of(1, 3, 2), ids(ordered));
        }
    }
}
