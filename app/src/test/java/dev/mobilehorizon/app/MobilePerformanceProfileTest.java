package dev.mobilehorizon.app;

import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public final class MobilePerformanceProfileTest {
    @Test
    public void defaultOptionsReceiveConservativeMobileValues() {
        String input = "version:4000\nrenderDistance:12\nsimulationDistance:12\nmaxFps:120\n"
                + "ao:true\nrenderClouds:\"true\"\nparticles:0\nentityShadows:true\n"
                + "entityDistanceScaling:1.0\nbiomeBlendRadius:2\ngraphicsPreset:\"fancy\"\n"
                + "mipmapLevels:4\nweatherRadius:10\nvignette:true\ncutoutLeaves:true\n"
                + "menuBackgroundBlurriness:5\nlang:en_us\n";
        String tuned = MobilePerformanceProfile.tuneOptionsText(input);
        assertTrue(tuned.contains("renderDistance:4\n"));
        assertTrue(tuned.contains("simulationDistance:4\n"));
        assertTrue(tuned.contains("maxFps:30\n"));
        assertTrue(tuned.contains("graphicsPreset:\"fast\"\n"));
        assertTrue(tuned.contains("renderClouds:\"false\"\n"));
        assertTrue(tuned.contains("mipmapLevels:0\n"));
        assertFalse(tuned.contains("renderDistance:12\n"));
    }

    @Test
    public void previousAutomaticMobileValuesMigrateWithoutResettingCustomOptions() {
        String input = "version:4000\nrenderDistance:6\nsimulationDistance:5\nmaxFps:30\n"
                + "graphicsPreset:\"fast\"\nlang:pt_br\n";
        String tuned = MobilePerformanceProfile.tuneOptionsText(input);
        assertTrue(tuned.contains("renderDistance:4\n"));
        assertTrue(tuned.contains("simulationDistance:4\n"));
        assertTrue(tuned.contains("maxFps:30\n"));
        assertTrue(tuned.contains("lang:pt_br\n"));
    }

    @Test
    public void customOptionsArePreserved() {
        String input = "version:4000\nrenderDistance:10\nsimulationDistance:7\nmaxFps:90\n"
                + "graphicsPreset:\"fast\"\nlang:pt_br\n";
        String tuned = MobilePerformanceProfile.tuneOptionsText(input);
        assertTrue(tuned.contains("renderDistance:10\n"));
        assertTrue(tuned.contains("simulationDistance:7\n"));
        assertTrue(tuned.contains("maxFps:90\n"));
        assertTrue(tuned.contains("graphicsPreset:\"fast\"\n"));
        assertTrue(tuned.contains("lang:pt_br\n"));
    }
}
