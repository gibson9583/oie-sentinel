/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

class MonitorNameNormalizationTest extends MonitorIdentityDatabaseTestSupport {
    @Test
    void createStoresTrimmedNameAndRejectsWhitespaceOnlyName() {
        Monitor created = MonitorService.create(monitor(" \tPager \n", "{}", null), 99);
        assertEquals("Pager", created.getName());
        assertEquals("Pager", MonitorRepository.getMonitor(created.getId()).getName());
        assertThrows(IllegalArgumentException.class,
                () -> MonitorService.create(monitor(" \t\n", "{}", null), 99));
        assertEquals(1, MonitorRepository.listMonitors(null, null, null, null).size());
    }

    @Test
    void updateTrimsNamePreservesIdentityAndAllowsUniqueLegacySelfNormalization() {
        Monitor legacy = legacyMonitor(" Old name", "{}", null);
        Monitor updated = MonitorService.update(legacy.getId(), monitor(" Old name ", "{}", null), 100);
        assertEquals("Old name", updated.getName());
        assertEquals(legacy.getId(), updated.getId());
        assertEquals(legacy.getCreatedTime(), updated.getCreatedTime());
        assertEquals(legacy.getCreatedBy(), updated.getCreatedBy());
        assertEquals(100, updated.getUpdatedBy());
        assertEquals("Old name", MonitorRepository.getMonitor(legacy.getId()).getName());
    }

    @Test
    void createAndUpdateCompareNormalizedLegacyNamesWithoutChangingExistingRows() {
        legacyMonitor(" Pager", "{}", null);
        Monitor other = legacyMonitor("Other", "{}", null);
        String before = snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> MonitorService.create(monitor(" pAgEr ", "{}", null), 99));
        assertThrows(IllegalArgumentException.class,
                () -> MonitorService.update(other.getId(), monitor(" PAGER ", "{}", null), 99));
        assertEquals(before, snapshot());
    }

    @Test
    void explicitRenameCanRepairLegacyCollisionWithoutRebindingDependents() {
        Monitor intended = legacyMonitor(" Pager", "{}", null);
        legacyMonitor("Pager", "{}", null);
        Monitor child = legacyMonitor("Child", "{}", intended.getId());
        assertThrows(IllegalArgumentException.class,
                () -> MonitorService.update(intended.getId(), monitor("Pager", "{}", null), 99));
        Monitor renamed = MonitorService.update(intended.getId(), monitor(" Distinct pager ", "{}", null), 99);
        assertEquals("Distinct pager", renamed.getName());
        assertEquals(intended.getId(), MonitorRepository.getMonitor(child.getId()).getSuppressedByMonitorId());
        assertEquals(3, MonitorRepository.listMonitors(null, null, null, null).size());
    }

    @Test
    void nameKeyUsesRootLocaleConsistentlyWithPortableImport() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            MonitorService.create(monitor(" INDIGO ", "{}", null), 99);
            assertThrows(IllegalArgumentException.class,
                    () -> MonitorService.create(monitor("indigo", "{}", null), 99));
            // ROOT lower-case keeps dotless i distinct. equalsIgnoreCase does
            // not: using it here disagrees with import's portable name key.
            Monitor distinct = MonitorService.create(monitor("\u0131ndigo", "{}", null), 99);
            assertNotNull(distinct.getId());
            assertEquals(2, ExportImportService.export().path("monitors").size());
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8, 27})
    void controlOnlyNameIsRejectedAfterTrimmingWithoutChangingStoredRows(int character) {
        Monitor existing = legacyMonitor("Existing", "{}", null);
        String before = snapshot();
        String controlOnly = String.valueOf((char) character);
        assertThrows(IllegalArgumentException.class,
                () -> MonitorService.create(monitor(controlOnly, "{}", null), 99));
        assertThrows(IllegalArgumentException.class,
                () -> MonitorService.update(existing.getId(), monitor(controlOnly, "{}", null), 99));
        assertEquals(before, snapshot());
    }
}
