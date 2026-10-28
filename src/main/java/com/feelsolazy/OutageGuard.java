package com.feelsolazy;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main loop. Everything is driven by a wall-clock tick (not long sleeps), so it keeps
 * working correctly after the PC wakes from sleep or the clock is adjusted.
 * <p>
 * The schedule is refreshed at startup and then every hour shortly after hh:00 (so 00:01, 01:01, ...),
 * which picks up tomorrow's schedule whenever it is published, plus any same-day changes.
 * A failed fetch is just skipped: the last fetched (cached) schedule stays in use until the next hourly check.
 */
@Slf4j
final class OutageGuard {

    private static final Duration TICK = Duration.ofSeconds(15);
    private static final Duration SHUTDOWN_RETRY = Duration.ofSeconds(30);
    /** Minimum time between the warning and the shutdown, if the app learns about an outage late. */
    private static final Duration MIN_WARNING = Duration.ofSeconds(60);

    private final Config cfg;
    private final YasnoClient client;
    private final Notifier notifier;
    private final Clock clock = Clock.system(YasnoClient.KYIV);

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Instant nextRefresh = Instant.EPOCH;
    private volatile List<Outage> outages = List.of();

    /** Outage start -> moment the warning was shown. */
    private final Map<Instant, Instant> warned = new ConcurrentHashMap<>();
    private final Set<Instant> handled = ConcurrentHashMap.newKeySet();

    OutageGuard(Config cfg, Notifier notifier) {
        this.cfg = cfg;
        this.client = new YasnoClient(cfg);
        this.notifier = notifier;
    }

    void start() {
        log.info("OutageGuard started: group {}, warn {} min, shutdown {} min before outage{}",
                cfg.group(), cfg.warnBefore().toMinutes(), cfg.shutdownBefore().toMinutes(),
                cfg.dryRun() ? " (DRY RUN)" : "");
        client.loadCached().ifPresent(cached -> {
            outages = cached;
            log.info("Loaded cached schedule: {}", cached);
            updateStatus();
        });
        ticker.scheduleWithFixedDelay(this::tick, 0, TICK.toSeconds(), TimeUnit.SECONDS);
    }

    private void tick() {
        try {
            Instant now = clock.instant();
            notifier.refreshTray();
            if (!now.isBefore(nextRefresh) && refreshing.compareAndSet(false, true)) {
                fetcher.execute(this::refresh);
            }

            Outage next = outages.stream()
                    .filter(o -> o.start().toInstant().isAfter(now))
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                return;
            }
            Instant start = next.start().toInstant();

            if (!now.isBefore(start.minus(cfg.warnBefore())) && !warned.containsKey(start)) {
                warned.put(start, now);
                long minutes = Math.max(1, Duration.between(now, start).toSeconds() / 60);
                String action = Files.exists(cfg.pauseFile())
                        ? "Auto-shutdown is paused (pause file exists)."
                        : "PC will shut down " + cfg.shutdownBefore().toMinutes() + " min before. Save your work!";
                notifier.alert("Power outage in " + minutes + " min (" + next + ")", action);
            }

            Instant warnedAt = warned.get(start);
            if (warnedAt != null
                    && !handled.contains(start)
                    && !now.isBefore(start.minus(cfg.shutdownBefore()))
                    && !now.isBefore(warnedAt.plus(MIN_WARNING))) {
                handled.add(start);
                if (Files.exists(cfg.pauseFile())) {
                    log.info("Skipping shutdown for {}: {} exists", next, cfg.pauseFile());
                } else {
                    log.info("Shutting down before outage {}", next);
                    shutdown(next, true);
                }
            }
        } catch (Throwable t) {
            log.error("Tick failed", t);
        }
    }

    private void shutdown(Outage outage, boolean firstAttempt) {
        if (Os.shutdown(cfg)) {
            return;
        }
        if (firstAttempt) {
            notifier.alert("Auto-shutdown failed", "Retrying in " + SHUTDOWN_RETRY.toSeconds() + " seconds. Details in outageguard.log.");
            ticker.schedule(() -> {
                if (Files.exists(cfg.pauseFile())) {
                    log.info("Shutdown retry skipped: auto-shutdown paused");
                } else {
                    shutdown(outage, false);
                }
            }, SHUTDOWN_RETRY.toSeconds(), TimeUnit.SECONDS);
        } else {
            notifier.alert("Auto-shutdown FAILED",
                    "Shut down the PC manually before %tR! Details in outageguard.log.".formatted(outage.start()));
        }
    }

    private void refresh() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        nextRefresh = now.truncatedTo(ChronoUnit.HOURS).plusHours(1).plusMinutes(1).toInstant();
        try {
            List<Outage> fresh = client.fetch();
            if (!fresh.equals(outages)) {
                log.info("Schedule for group {}: {}", cfg.group(), fresh.isEmpty() ? "no outages" : fresh);
            }
            outages = fresh;
            updateStatus();
        } catch (IOException e) {
            // Expected when offline: one line, no stack trace.
            log.warn("Schedule fetch failed ({}), keeping last known schedule until next hourly check", e.toString());
        } catch (Exception e) {
            log.error("Schedule fetch failed, keeping last known schedule until next hourly check", e);
        } finally {
            refreshing.set(false);
        }
    }

    private void updateStatus() {
        Instant now = clock.instant();
        String next = outages.stream()
                .filter(o -> o.end().toInstant().isAfter(now))
                .findFirst()
                .map(o -> "Next outage: " + o)
                .orElse("No outages planned");
        notifier.setStatus("OutageGuard (" + cfg.group() + ") - " + next);
    }
}
