package com.feelsolazy;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Usage:
 *   java -jar outageguard.jar                 run in background (default)
 *   java -jar outageguard.jar --status        print the schedule for your group and exit
 *   java -jar outageguard.jar --test-notify   show a test notification with sound and exit
 * Any setting can be overridden, e.g. --group=3.1 --dryRun=true
 */
@Slf4j
public class Main {

    /** Holds the single-instance lock, see {@link #acquireInstanceLock}. */
    private static FileChannel instanceLockChannel;

    public static void main(String[] args) throws Exception {
        Config cfg;
        try {
            cfg = Config.load(args);
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        List<String> flags = List.of(args);

        if (flags.contains("--status")) {
            List<Outage> outages = new YasnoClient(cfg).fetch();
            System.out.println("Group " + cfg.group() + ": " + (outages.isEmpty() ? "no outages planned" : ""));
            outages.forEach(o -> System.out.println("  " + o));
            return;
        }

        Notifier notifier = new Notifier(cfg.pauseFile(), () -> {
            log.info("Exit requested from tray");
            System.exit(0);
        });

        if (flags.contains("--test-notify")) {
            notifier.alert("Power outage in 10 min (test)", "This is how the warning will look and sound.");
            Thread.sleep(6000);
            System.exit(0);
        }

        if (!acquireInstanceLock(cfg)) {
            log.warn("Another OutageGuard instance is already running, exiting");
            System.exit(1);
        }
        new OutageGuard(cfg, notifier).start();
    }

    /**
     * Deliberately never closed: the lock only lives while the channel is open, so it must stay open
     * for the lifetime of the process. The OS releases it when the JVM exits.
     */
    private static boolean acquireInstanceLock(Config cfg) throws IOException {
        instanceLockChannel = FileChannel.open(cfg.dir().resolve("instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        return instanceLockChannel.tryLock() != null;
    }
}
