package dev.mobilehorizon.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.fail;

public final class PojavRuntimeInstallerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsSmallJavaLauncherWhenRuntimeMetadataIsValid() throws Exception {
        File root = createRuntime("small-launcher", new byte[] {0x7f});
        PojavRuntimeInstaller.validateRuntime(new PojavRuntimeInstaller.RuntimePaths(root));
    }

    @Test
    public void rejectsEmptyJavaLauncher() throws Exception {
        File root = createRuntime("empty-launcher", new byte[0]);
        try {
            PojavRuntimeInstaller.validateRuntime(new PojavRuntimeInstaller.RuntimePaths(root));
            fail("Um executável Java vazio não deve ser aceito.");
        } catch (IOException expected) {
            // Expected: the launcher file must contain at least one byte.
        }
    }

    private File createRuntime(String name, byte[] javaBytes) throws Exception {
        File root = temporaryFolder.newFolder(name);
        File bin = new File(root, "bin");
        File server = new File(root, "lib/server");
        if (!bin.mkdirs() || !server.mkdirs()) {
            throw new IOException("Não foi possível criar a estrutura JRE de teste.");
        }
        Files.write(new File(bin, "java").toPath(), javaBytes);
        try (FileOutputStream output = new FileOutputStream(new File(server, "libjvm.so"))) {
            output.getChannel().position(1024L * 1024L - 1);
            output.write(0);
        }
        Files.write(new File(root, "release").toPath(),
                ("JAVA_VERSION=\"25.0.1\"\nOS_ARCH=\"aarch64\"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return root;
    }
}
