package dev.mobilehorizon.app;

import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

public final class FabricBridgeRuntimeManagerTest {
    @Test
    public void appendsLwjglLibraryPathWithoutDroppingJavaRuntimeOptions() {
        File directory = new File("/data/app/mobilehorizon/lib/arm64");
        String options = FabricBridgeRuntimeManager.appendLwjglLibraryPathOption(
                "-XX:-UsePerfData -XX:ActiveProcessorCount=4", directory);

        assertTrue(options.contains("-XX:-UsePerfData"));
        assertTrue(options.contains("-XX:ActiveProcessorCount=4"));
        assertTrue(options.endsWith(
                ""-Dorg.lwjgl.librarypath=" + directory.getAbsolutePath() + """));
    }

    @Test
    public void quotesLibraryDirectoriesContainingWhitespace() {
        File directory = new File("/data/user/0/example/files/native libs");
        String options = FabricBridgeRuntimeManager.appendLwjglLibraryPathOption(null, directory);

        assertTrue(options.startsWith(""-Dorg.lwjgl.librarypath="));
        assertTrue(options.endsWith(directory.getAbsolutePath() + """));
    }
}
