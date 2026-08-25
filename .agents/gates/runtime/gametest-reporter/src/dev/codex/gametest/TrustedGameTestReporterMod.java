package dev.codex.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.TestReporter;
import net.neoforged.fml.common.Mod;

/**
 * Runtime-side GameTest reporter used by the toolkit's L4 gate.
 *
 * <p>The surrounding Python control process creates a fresh external event
 * path and injects it through a control-digested Gradle init script. The gate rejects
 * aggregate console markers unless this reporter records the exact runtime
 * test-name set and a terminal event. This is process-local observation, not a
 * security boundary against another mod in the same JVM.</p>
 */
@Mod(TrustedGameTestReporterMod.MOD_ID)
public final class TrustedGameTestReporterMod implements TestReporter {
    public static final String MOD_ID = "codex_gametest_evidence";
    private static final String PROTOCOL = "codex-gametest-events-v1";
    private static final String PATH_PROPERTY = "codex.gametest.events";
    private static final String NONCE_PROPERTY = "codex.gametest.nonce";

    private final Path eventPath;
    private final String nonce;
    private final AtomicInteger sequence = new AtomicInteger();
    private int passed;
    private int failed;

    public TrustedGameTestReporterMod() {
        String rawPath = requiredProperty(PATH_PROPERTY);
        this.nonce = requiredProperty(NONCE_PROPERTY);
        this.eventPath = Path.of(rawPath).toAbsolutePath().normalize();

        try {
            Path parent = this.eventPath.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                throw new IOException("event parent does not exist");
            }
            Files.writeString(
                this.eventPath,
                event("run_started", "", true, ""),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
        } catch (IOException error) {
            throw new IllegalStateException(
                "Cannot initialize GameTest event stream at "
                    + this.eventPath,
                error
            );
        }

        GlobalTestReporter.replaceWith(this);
    }

    @Override
    public synchronized void onTestFailed(GameTestInfo testInfo) {
        this.failed++;
        append(
            event(
                "test_failed",
                testInfo.getTestName(),
                testInfo.isRequired(),
                testInfo.getError() == null
                    ? "unknown failure"
                    : String.valueOf(testInfo.getError().getMessage())
            )
        );
    }

    @Override
    public synchronized void onTestSuccess(GameTestInfo testInfo) {
        this.passed++;
        append(event("test_passed", testInfo.getTestName(), testInfo.isRequired(), ""));
    }

    @Override
    public synchronized void finish() {
        append(
            "{"
                + field("protocol", PROTOCOL) + ","
                + field("nonce", this.nonce) + ","
                + "\"sequence\":" + this.sequence.incrementAndGet() + ","
                + field("event", "run_finished") + ","
                + "\"passed\":" + this.passed + ","
                + "\"failed\":" + this.failed
                + "}\n"
        );
    }

    private String event(
        String event,
        String testName,
        boolean required,
        String detail
    ) {
        return "{"
            + field("protocol", PROTOCOL) + ","
            + field("nonce", this.nonce) + ","
            + "\"sequence\":" + this.sequence.incrementAndGet() + ","
            + field("event", event) + ","
            + field("test_name", testName) + ","
            + "\"required\":" + required + ","
            + field("detail", detail)
            + "}\n";
    }

    private void append(String line) {
        try {
            Files.writeString(
                this.eventPath,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
            );
        } catch (IOException error) {
            throw new IllegalStateException(
                "Cannot append GameTest event evidence",
                error
            );
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Missing required GameTest evidence property: " + name
            );
        }
        return value;
    }

    private static String field(String name, String value) {
        return "\"" + name + "\":\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < 0x20) {
                        result.append(String.format("\\u%04x", (int) current));
                    } else {
                        result.append(current);
                    }
                }
            }
        }
        return result.toString();
    }
}
