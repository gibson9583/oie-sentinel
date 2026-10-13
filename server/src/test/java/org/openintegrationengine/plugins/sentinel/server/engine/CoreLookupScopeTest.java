package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.ChannelTag;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;

class CoreLookupScopeTest {
    @Test
    void groupDefinitionsRequireCompleteNonemptyAuthorizedMembership() {
        ChannelGroup allowed = group("allowed-group", List.of(channel("allowed")));
        ChannelGroup mixed = group("mixed", List.of(channel("allowed"), channel("forbidden")));
        ChannelGroup incomplete = group("incomplete", Arrays.asList(channel("allowed"), null));
        ChannelGroup unidentified = group("unidentified", List.of(channel("allowed"), new Channel()));
        try (MockedStatic<ChannelController> controller = mockStatic(ChannelController.class)) {
            ChannelController channels = mock(ChannelController.class);
            controller.when(ChannelController::getInstance).thenReturn(channels);
            List<ChannelGroup> groups = List.of(allowed, mixed, incomplete,
                    unidentified, group("empty", List.of()), group("missing", null),
                    group(" ", List.of(channel("allowed"))));
            when(channels.getChannelGroups(null)).thenReturn(groups);
            var visible = ScopeResolver.listGroups(Set.of("allowed"));
            assertEquals(List.of("allowed-group"), visible.stream().map(g -> g.getId()).toList());
            assertEquals(List.of("allowed"), visible.get(0).getChannelIds());
            assertTrue(ScopeResolver.listGroups(Set.of()).isEmpty());
        }
    }

    @Test
    void groupMembershipIsCapturedOnceAndRecheckedOnTheNextRequest() {
        ChannelGroup changing = mock(ChannelGroup.class);
        when(changing.getId()).thenReturn("changing");
        when(changing.getName()).thenReturn("Changing group");
        when(changing.getChannels()).thenReturn(List.of(channel("allowed")),
                List.of(channel("allowed"), channel("forbidden")));
        try (MockedStatic<ChannelController> controller = mockStatic(ChannelController.class)) {
            ChannelController channels = mock(ChannelController.class);
            controller.when(ChannelController::getInstance).thenReturn(channels);
            when(channels.getChannelGroups(null)).thenReturn(List.of(changing));
            assertEquals(List.of("allowed"), ScopeResolver.listGroups(Set.of("allowed")).get(0).getChannelIds());
            verify(changing, times(1)).getChannels();
            assertTrue(ScopeResolver.listGroups(Set.of("allowed")).isEmpty());
            verify(changing, times(2)).getChannels();
        }
    }

    @Test
    void tagDefinitionsCannotExposeForbiddenOrUnidentifiedMembers() {
        try (TagFixture f = new TagFixture()) {
            Set<ChannelTag> tags = Set.of(tag("allowed-tag", Set.of("allowed")),
                    tag("mixed", Set.of("allowed", "forbidden")), tag("empty", Set.of()),
                    tag("missing", null), tag(" ", Set.of("allowed")),
                    tag("incomplete", new HashSet<>(Arrays.asList("allowed", null))));
            when(f.config.getChannelTags()).thenReturn(tags);
            var visible = ScopeResolver.listTags(Set.of("allowed"));
            assertEquals(List.of("allowed-tag"), visible.stream().map(t -> t.getId()).toList());
            assertEquals(List.of("allowed"), visible.get(0).getChannelIds());
            assertTrue(ScopeResolver.listTags(Set.of()).isEmpty());
        }
    }

    @Test
    void tagMembershipIsCapturedOnceAndDetachedFromHostState() {
        ChannelTag changing = mock(ChannelTag.class);
        when(changing.getId()).thenReturn("changing");
        Set<String> first = new HashSet<>(Set.of("allowed"));
        when(changing.getChannelIds()).thenReturn(first, Set.of("forbidden"));
        try (TagFixture f = new TagFixture()) {
            when(f.config.getChannelTags()).thenReturn(Set.of(changing));
            var visible = ScopeResolver.listTags(Set.of("allowed"));
            verify(changing, times(1)).getChannelIds();
            first.add("forbidden");
            assertEquals(List.of("allowed"), visible.get(0).getChannelIds());
            assertTrue(ScopeResolver.listTags(Set.of("allowed")).isEmpty());
        }
    }

    @Test
    void unrestrictedLookupRetainsGlobalAndEmptyDefinitions() {
        try (TagFixture f = new TagFixture()) {
            Set<ChannelTag> tags = Set.of(tag("empty", Set.of()),
                    tag("mixed", Set.of("allowed", "forbidden")));
            when(f.config.getChannelTags()).thenReturn(tags);
            assertEquals(2, ScopeResolver.listTags().size());
        }
        try (MockedStatic<ChannelController> controller = mockStatic(ChannelController.class)) {
            ChannelController channels = mock(ChannelController.class);
            controller.when(ChannelController::getInstance).thenReturn(channels);
            List<ChannelGroup> groups = List.of(group("empty", List.of()),
                    group("mixed", List.of(channel("allowed"), channel("forbidden"))));
            when(channels.getChannelGroups(null)).thenReturn(groups);
            assertEquals(2, ScopeResolver.listGroups().size());
        }
    }

    static Channel channel(String id) { Channel c = new Channel(); c.setId(id); return c; }
    static ChannelGroup group(String id, List<Channel> members) {
        ChannelGroup g = mock(ChannelGroup.class);
        when(g.getId()).thenReturn(id); when(g.getChannels()).thenReturn(members); return g;
    }
    static ChannelTag tag(String id, Set<String> members) {
        ChannelTag t = mock(ChannelTag.class);
        when(t.getId()).thenReturn(id); when(t.getChannelIds()).thenReturn(members); return t;
    }
    static class TagFixture implements AutoCloseable {
        final MockedStatic<ControllerFactory> host = mockStatic(ControllerFactory.class);
        final ConfigurationController config = mock(ConfigurationController.class);
        TagFixture() {
            ControllerFactory factory = mock(ControllerFactory.class);
            host.when(ControllerFactory::getFactory).thenReturn(factory);
            when(factory.createConfigurationController()).thenReturn(config);
        }
        @Override public void close() { host.close(); }
    }
}
