package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The disk-usage atom, started the way the runner starts it.
 *
 * <p>The sizes here are exact: the fixtures are written by the test, so "4 KiB" is 4096 bytes and not "whatever the
 * workspace happens to weigh".
 */
class DiskUsageIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("the workspace is measured exactly, and the report breaks it down by directory")
    void measuresTheWorkspace() throws Exception {
        AtomResult run = atom.fileBytes("src/blob.bin", zeros(4 * 1024)).input("maxMb", "0").run();

        run.assertOk();
        assertEquals("1", run.field("files"));
        assertEquals("4096", run.field("bytes"));
        assertTrue(run.exists("out/disk/index.html"), "no report\n" + run.console());
        assertTrue(run.file("out/disk/index.html").contains("src"),
                "the directory breakdown is missing:\n" + run.file("out/disk/index.html"));
    }

    @Test
    @DisplayName("extra paths outside the workspace are measured too")
    void measuresExtraPaths() throws Exception {
        Path extra = Files.createTempDirectory("disk-extra-");
        Files.write(extra.resolve("more.bin"), zeros(2 * 1024));

        AtomResult run = atom.fileBytes("a.bin", zeros(1024))
                .input("extraPaths", extra.toString())
                .run();

        run.assertOk();
        assertEquals("3072", run.field("bytes"), String.valueOf(run.document()));
    }

    @Test
    @DisplayName("a workspace over the budget fails, and says what the budget was")
    void refusesOverBudget() throws Exception {
        AtomResult run = atom.fileBytes("big.bin", zeros(2 * 1024 * 1024)).input("maxMb", "1").run();

        run.assertRefused();
        assertEquals("failure", run.status());
        String message = String.valueOf(run.document().getOrDefault("message", ""));
        assertTrue(message.contains("the budget is 1.0 MB"), "message: " + message);
    }

    private static byte[] zeros(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 0);
        return bytes;
    }
}
