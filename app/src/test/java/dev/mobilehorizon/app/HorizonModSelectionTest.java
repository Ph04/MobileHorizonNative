package dev.mobilehorizon.app;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.TreeSet;

import static org.junit.Assert.*;

public final class HorizonModSelectionTest {
    @Test public void readsOnlyEnabledModRowsAndIgnoresComments() throws Exception {
        File home = Files.createTempDirectory("horizon-mod-selection-").toFile();
        Files.write(new File(home, "horizon-mods.txt").toPath(), Arrays.asList(
                "# desktop-compatible selection",
                "mod.zeta:true",
                "mod.alpha:false",
                "mod.beta:true",
                "other.setting:true",
                "malformed",
                "mod.invalid id:true"
        ), StandardCharsets.UTF_8);

        assertEquals(new TreeSet<>(Arrays.asList("beta", "zeta")),
                HorizonModSelection.readEnabledIds(home));
    }

    @Test public void enablesAndDisablesOneNativeModWithoutChangingOthers() throws Exception {
        File home = Files.createTempDirectory("horizon-mod-toggle-").toFile();
        HorizonModSelection.setEnabled(home, "mod_menu", true);
        HorizonModSelection.setEnabled(home, "fps_counter", true);
        assertTrue(HorizonModSelection.isEnabled(home, "mod_menu"));
        assertTrue(HorizonModSelection.isEnabled(home, "fps_counter"));

        HorizonModSelection.setEnabled(home, "mod_menu", false);
        assertFalse(HorizonModSelection.isEnabled(home, "mod_menu"));
        assertTrue(HorizonModSelection.isEnabled(home, "fps_counter"));
        assertEquals("mod.fps_counter:true\n",
                new String(Files.readAllBytes(new File(home, "horizon-mods.txt").toPath()),
                        StandardCharsets.UTF_8));
    }

    @Test public void emptySelectionDisablesAllNativeMods() throws Exception {
        File home = Files.createTempDirectory("horizon-mod-empty-").toFile();
        HorizonModSelection.setEnabled(home, "mod_menu", true);
        HorizonModSelection.setEnabled(home, "mod_menu", false);
        assertTrue(HorizonModSelection.readEnabledIds(home).isEmpty());
        assertEquals(0, Files.size(new File(home, "horizon-mods.txt").toPath()));
    }
}
