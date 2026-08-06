/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * CHANNEL_GROUP and CHANNEL_TAG condition matching, with the engine cut out.
 *
 * <p>These two fields are the only part of the condition language that reads
 * state outside the payload: membership is resolved on every evaluation
 * through {@link ScopeResolver}, which answers from the running engine's
 * in-memory channel caches. That live resolution is the feature — an operator
 * who adds a channel to the "Production" group expects the next dispatch to
 * respect it without reopening the action — but it also means these cases
 * cannot be exercised the way the rest of {@code ActionConditionMatcherTest}
 * is, from a payload alone.</p>
 *
 * <p>Rather than stand up an engine, each test replaces the two resolver
 * lookups for the duration of one {@code try}-with-resources block. Mockito 5
 * uses the inline mock maker by default, so this needs no dependency beyond
 * the {@code mockito-core} already declared in the root pom. Unstubbed
 * resolver calls return an empty set, which is also the production behaviour
 * for a group or tag that no longer exists — so an unstubbed group in these
 * tests reads exactly as a deleted one.</p>
 */
@DisplayName("ActionConditionMatcher — CHANNEL_GROUP / CHANNEL_TAG")
class ActionConditionMatcherScopeTest {

    private static final String CHANNEL_ID = "1c8f0a7e-5b3d-4d21-9a6f-2e7c4b91d0aa";
    private static final String OTHER_CHANNEL_ID = "9d4e6b12-0a77-4c58-8f3b-6ac1de205f47";

    private static final AlertPayload PAYLOAD = new AlertPayload(1001L, 7, "Nightly ADT feed",
            MonitorType.INACTIVITY, CHANNEL_ID, "ADT Inbound", null, Severity.HIGH, "PROBLEM",
            "No messages received in 30m", Instant.parse("2026-01-01T03:00:00Z"), null);

    private static Action action(String conditionJson) {
        Action action = new Action();
        action.setId(42);
        action.setName("Page on-call");
        action.setConditionJson(conditionJson.replace('\'', '"'));
        return action;
    }

    private static boolean matches(String conditionJson) {
        return ActionConditionMatcher.matches(action(conditionJson), PAYLOAD);
    }

    @Nested
    @DisplayName("CHANNEL_GROUP")
    class ChannelGroup {

        @Test
        @DisplayName("'=' matches when the payload's channel is in the named group")
        void equalsMatchesMember() {
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.groupChannelIds("Production"))
                        .thenReturn(Set.of(CHANNEL_ID, OTHER_CHANNEL_ID));
                assertTrue(matches("[{'field':'CHANNEL_GROUP','operator':'=','value':'Production'}]"));
            }
        }

        @Test
        @DisplayName("'=' does not match a group the channel is absent from")
        void equalsRejectsNonMember() {
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.groupChannelIds("Production"))
                        .thenReturn(Set.of(OTHER_CHANNEL_ID));
                assertFalse(matches("[{'field':'CHANNEL_GROUP','operator':'=','value':'Production'}]"));
            }
        }

        @Test
        @DisplayName("'!=' excludes members of the named group")
        void notEqualsExcludesMembers() {
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.groupChannelIds("Production"))
                        .thenReturn(Set.of(CHANNEL_ID));
                scope.when(() -> ScopeResolver.groupChannelIds("Staging"))
                        .thenReturn(Set.of(OTHER_CHANNEL_ID));
                assertFalse(matches("[{'field':'CHANNEL_GROUP','operator':'!=','value':'Production'}]"));
                assertTrue(matches("[{'field':'CHANNEL_GROUP','operator':'!=','value':'Staging'}]"));
            }
        }

        @Test
        @DisplayName("'IN' matches membership of any listed group")
        void inMatchesAnyGroup() {
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.groupChannelIds("Staging"))
                        .thenReturn(Set.of(OTHER_CHANNEL_ID));
                scope.when(() -> ScopeResolver.groupChannelIds("Production"))
                        .thenReturn(Set.of(CHANNEL_ID));
                assertTrue(matches(
                        "[{'field':'CHANNEL_GROUP','operator':'IN','value':['Staging','Production']}]"));
                assertFalse(
                        matches("[{'field':'CHANNEL_GROUP','operator':'IN','value':['Staging']}]"));
            }
        }

        @Test
        @DisplayName("a deleted or unknown group matches nothing")
        void unknownGroupMatchesNothing() {
            // ScopeResolver answers an unknown group with an empty set, and
            // "member of nothing" is the only safe reading — an action scoped
            // to a group somebody deleted should stop firing, not start
            // firing for every channel.
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.groupChannelIds("Deleted")).thenReturn(Set.of());
                assertFalse(matches("[{'field':'CHANNEL_GROUP','operator':'=','value':'Deleted'}]"));
                assertFalse(matches("[{'field':'CHANNEL_GROUP','operator':'IN','value':['Deleted']}]"));
            }
        }

        @Test
        @DisplayName("membership is re-read on every evaluation")
        void membershipIsResolvedLive() {
            // The stored action never changes here; only the group's
            // membership does, between two evaluations. This is the contract
            // that lets an operator fix an alert routing problem by editing
            // the channel group instead of hunting down every action that
            // references it.
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.groupChannelIds("Production")).thenReturn(Set.of());
                assertFalse(matches("[{'field':'CHANNEL_GROUP','operator':'=','value':'Production'}]"));

                scope.when(() -> ScopeResolver.groupChannelIds("Production"))
                        .thenReturn(Set.of(CHANNEL_ID));
                assertTrue(matches("[{'field':'CHANNEL_GROUP','operator':'=','value':'Production'}]"));
            }
        }

        @Test
        @DisplayName("an unsupported operator fails the condition")
        void unknownOperatorFailsClosed() {
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.groupChannelIds("Production"))
                        .thenReturn(Set.of(CHANNEL_ID));
                // Fail closed even though the channel *is* in the group: the
                // matcher does not know what ">=" means over group
                // membership, and inventing a meaning is how a narrow
                // condition turns into a broad one.
                assertFalse(matches("[{'field':'CHANNEL_GROUP','operator':'>=','value':'Production'}]"));
            }
        }
    }

    @Nested
    @DisplayName("CHANNEL_TAG")
    class ChannelTag {

        @Test
        @DisplayName("'=' matches when the payload's channel bears the tag")
        void equalsMatchesTaggedChannel() {
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.tagChannelIds("pii")).thenReturn(Set.of(CHANNEL_ID));
                assertTrue(matches("[{'field':'CHANNEL_TAG','operator':'=','value':'pii'}]"));
            }
        }

        @Test
        @DisplayName("'=' does not match an untagged channel")
        void equalsRejectsUntaggedChannel() {
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.tagChannelIds("pii")).thenReturn(Set.of(OTHER_CHANNEL_ID));
                assertFalse(matches("[{'field':'CHANNEL_TAG','operator':'=','value':'pii'}]"));
            }
        }

        @Test
        @DisplayName("'!=' excludes channels bearing the tag")
        void notEqualsExcludesTagged() {
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.tagChannelIds("pii")).thenReturn(Set.of(CHANNEL_ID));
                scope.when(() -> ScopeResolver.tagChannelIds("lab")).thenReturn(Set.of(OTHER_CHANNEL_ID));
                assertFalse(matches("[{'field':'CHANNEL_TAG','operator':'!=','value':'pii'}]"));
                assertTrue(matches("[{'field':'CHANNEL_TAG','operator':'!=','value':'lab'}]"));
            }
        }

        @Test
        @DisplayName("'IN' matches any of several tags")
        void inMatchesAnyTag() {
            // The tag multi-select is an OR across tags, unlike the AND
            // across separate condition rows.
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.tagChannelIds("lab")).thenReturn(Set.of(OTHER_CHANNEL_ID));
                scope.when(() -> ScopeResolver.tagChannelIds("pii")).thenReturn(Set.of(CHANNEL_ID));
                assertTrue(matches("[{'field':'CHANNEL_TAG','operator':'IN','value':['lab','pii']}]"));
                assertFalse(matches("[{'field':'CHANNEL_TAG','operator':'IN','value':['lab']}]"));
            }
        }

        @Test
        @DisplayName("an empty tag list matches nothing")
        void emptyTagListMatchesNothing() {
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.tagChannelIds("pii")).thenReturn(Set.of(CHANNEL_ID));
                assertFalse(matches("[{'field':'CHANNEL_TAG','operator':'IN','value':[]}]"));
            }
        }

        @Test
        @DisplayName("an unsupported operator fails the condition")
        void unknownOperatorFailsClosed() {
            try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
                scope.when(() -> ScopeResolver.tagChannelIds("pii")).thenReturn(Set.of(CHANNEL_ID));
                assertFalse(matches("[{'field':'CHANNEL_TAG','operator':'LIKE','value':'pii'}]"));
            }
        }
    }
}
