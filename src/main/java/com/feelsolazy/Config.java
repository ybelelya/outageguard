package com.feelsolazy;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings from ~/.outageguard/config.properties, overridable with --key=value arguments.
 */
record Config(
        Path dir,
        String group,
        int region,
        int dso,
        Duration warnBefore,
        Duration shutdownBefore,
        Set<String> outageTypes,
        boolean dryRun,
        String shutdownCommand) {

    /** просп. Павличка Дмитра, 10А (Kyiv). */
    private static final String DEFAULT_GROUP = "4.1";

    private static final String TEMPLATE = """
            # OutageGuard settings
            #
            # Your outage queue (group), e.g. 3.1. Find it on https://yasno.com.ua by your address.
            group=4.1

            # Yasno region / DSO ids. 25 / 902 = Kyiv city, 3 / 301 (DnEM) or 3 / 303 (TsEK) = Dnipro.
            # Full list: https://app.yasno.ua/api/blackout-service/public/shutdowns/addresses/v2/regions
            region=25
            dso=902

            # Minutes before an outage to show the notification and to shut the PC down.
            warnMinutes=10
            shutdownMinutes=5

            # Slot types treated as an outage (comma separated).
            outageTypes=Definite

            # true = only log/notify, never really shut down (for testing).
            dryRun=false

            # Optional custom shutdown command. Empty = default:
            #   Windows: shutdown /s /f /t 0     Linux: systemctl poweroff
            # e.g. "shutdown /h" to hibernate on Windows, "systemctl hibernate" on Linux.
            shutdownCommand=
            """;

    Path pauseFile() {
        return dir.resolve("pause");
    }

    static Config load(String[] args) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".outageguard");
        Files.createDirectories(dir);
        Path file = dir.resolve("config.properties");
        if (!Files.exists(file)) {
            Files.writeString(file, TEMPLATE);
        }

        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        }
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 2) {
                p.setProperty(arg.substring(2, eq), arg.substring(eq + 1));
            }
        }

        String group = p.getProperty("group", "").trim();
        if (group.isEmpty()) {
            group = DEFAULT_GROUP;
        }
        int warn = intProp(p, "warnMinutes", 10);
        int shutdown = intProp(p, "shutdownMinutes", 5);
        if (shutdown > warn) {
            throw new IllegalStateException("shutdownMinutes must not be greater than warnMinutes");
        }
        Set<String> types = Arrays.stream(p.getProperty("outageTypes", "Definite").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        return new Config(
                dir,
                group,
                intProp(p, "region", 25),
                intProp(p, "dso", 902),
                Duration.ofMinutes(warn),
                Duration.ofMinutes(shutdown),
                types,
                Boolean.parseBoolean(p.getProperty("dryRun", "false").trim()),
                p.getProperty("shutdownCommand", "").trim());
    }

    private static int intProp(Properties p, String key, int def) {
        String v = p.getProperty(key, "").trim();
        return v.isEmpty() ? def : Integer.parseInt(v);
    }
}
