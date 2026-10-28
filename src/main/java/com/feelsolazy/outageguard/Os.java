package com.feelsolazy.outageguard;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@UtilityClass
class Os {

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /** Runs the shutdown command. Returns false, with the reason logged, if the OS refused it. */
    static boolean shutdown(Config cfg) {
        List<String> cmd;
        if (!cfg.shutdownCommand().isEmpty()) {
            cmd = List.of(cfg.shutdownCommand().split("\\s+"));
        } else if (WINDOWS) {
            cmd = List.of("shutdown", "/s", "/f", "/t", "0");
        } else {
            cmd = List.of("systemctl", "poweroff");
        }

        if (cfg.dryRun()) {
            log.info("DRY RUN: would execute {}", cmd);
            return true;
        }
        log.info("Executing {}", cmd);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                log.info("Shutdown command still running after 30 s, assuming shutdown is in progress");
                return true;
            }
            String output = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
            if (p.exitValue() == 0) {
                return true;
            }
            log.error("Shutdown command failed with exit code {}: {}", p.exitValue(), output);
        } catch (IOException e) {
            log.error("Cannot run shutdown command {}", cmd, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
