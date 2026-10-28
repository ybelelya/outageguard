package com.feelsolazy.outageguard;

import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.awt.CheckboxMenuItem;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Desktop notification + alarm sound.
 * Windows: tray balloon/toast. Linux: notify-send (falls back to tray if available).
 */
@Slf4j
final class Notifier {

    private final Path pauseFile;
    private TrayIcon tray;
    private CheckboxMenuItem pause;
    private String status = "OutageGuard";

    Notifier(Path pauseFile, Runnable onExit) {
        this.pauseFile = pauseFile;
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();
            pause = new CheckboxMenuItem("Pause auto-shutdown", Files.exists(pauseFile));
            pause.addItemListener(e -> setPaused(pause.getState()));
            menu.add(pause);
            menu.addSeparator();
            MenuItem exit = new MenuItem("Exit OutageGuard");
            exit.addActionListener(e -> onExit.run());
            menu.add(exit);
            // Drawn at the exact tray size (sharper than auto-scaling, especially on HiDPI screens).
            int size = SystemTray.getSystemTray().getTrayIconSize().width;
            tray = new TrayIcon(Icons.draw(size), "OutageGuard", menu);
            SystemTray.getSystemTray().add(tray);
        } catch (Exception e) {
            log.warn("System tray unavailable", e);
            tray = null;
        }
    }

    void setStatus(String text) {
        status = text;
        refreshTray();
    }

    /** Keeps the menu checkbox in sync when the pause file is created/removed by hand. */
    void refreshTray() {
        if (tray == null) {
            return;
        }
        boolean paused = Files.exists(pauseFile);
        if (pause.getState() != paused) {
            pause.setState(paused);
        }
        String text = paused ? "[PAUSED] " + status : status;
        tray.setToolTip(text.length() > 120 ? text.substring(0, 120) : text);
    }

    private void setPaused(boolean paused) {
        try {
            if (paused) {
                Files.writeString(pauseFile, "");
            } else {
                Files.deleteIfExists(pauseFile);
            }
            log.info("Auto-shutdown {} from tray", paused ? "paused" : "resumed");
        } catch (IOException e) {
            log.error("Cannot change pause state", e);
        }
        refreshTray();
    }

    void alert(String title, String message) {
        log.warn("ALERT: {} - {}", title, message);
        Thread sound = new Thread(Notifier::playAlarm, "alarm");
        sound.setDaemon(true);
        sound.start();

        boolean shown = Os.WINDOWS ? windowsToast(title, message) : notifySend(title, message);
        if (!shown && tray != null) {
            tray.displayMessage(title, message, TrayIcon.MessageType.WARNING);
        }
    }

    private static boolean notifySend(String title, String message) {
        try {
            Process p = new ProcessBuilder("notify-send", "-u", "critical", "-a", "OutageGuard",
                    "-i", "dialog-warning", title, message).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("notify-send failed", e);
            return false;
        }
    }

    /**
     * Windows toast under our own AppUserModelID (registered in HKCU), so the toast says "OutageGuard"
     * instead of "OpenJDK Platform binary" (the javaw.exe description). Stays on screen until dismissed;
     * silent because we play our own alarm.
     */
    private boolean windowsToast(String title, String message) {
        try {
            Path icon = pauseFile.resolveSibling("icon.png");
            Icons.writePng(icon, 128);
            String xml = """
                    <toast scenario="reminder"><visual><binding template="ToastGeneric">\
                    <text>%s</text><text>%s</text></binding></visual><audio silent="true"/>\
                    <actions><action activationType="system" arguments="dismiss" content=""/></actions></toast>"""
                    .formatted(xmlEscape(title), xmlEscape(message));
            String script = """
                    $ErrorActionPreference = 'Stop'
                    $ProgressPreference = 'SilentlyContinue'
                    trap { Write-Output $_; exit 1 }
                    $id = 'OutageGuard'
                    $key = "HKCU:\\Software\\Classes\\AppUserModelId\\$id"
                    New-Item -Path $key -Force | Out-Null
                    Set-ItemProperty -Path $key -Name DisplayName -Value 'OutageGuard'
                    Set-ItemProperty -Path $key -Name IconUri -Value '%s'
                    [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
                    [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
                    $xml = New-Object Windows.Data.Xml.Dom.XmlDocument
                    $xml.LoadXml('%s')
                    [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($id).Show([Windows.UI.Notifications.ToastNotification]::new($xml))
                    """.formatted(psQuote(icon.toString()), psQuote(xml));
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Windows toast timed out");
                return false;
            }
            if (p.exitValue() != 0) {
                String output = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
                log.warn("Windows toast failed (exit {}): {}", p.exitValue(), output);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Windows toast failed", e);
            return false;
        }
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Escapes for a single-quoted PowerShell string. */
    private static String psQuote(String s) {
        return s.replace("'", "''");
    }

    /** Synthesized two-tone siren, so no audio files or OS-specific players are needed. */
    static void playAlarm() {
        float rate = 44_100f;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) {
            tone(pcm, rate, 880, 0.25);
            tone(pcm, rate, 660, 0.25);
            tone(pcm, rate, 0, 0.15);
        }
        byte[] data = pcm.toByteArray();
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();
            line.write(data, 0, data.length);
            line.drain();
        } catch (Exception e) {
            log.warn("Audio unavailable ({}), using system beep", e.toString());
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private static void tone(ByteArrayOutputStream out, float rate, double hz, double seconds) {
        int samples = (int) (rate * seconds);
        int fade = (int) (rate * 0.01);
        for (int i = 0; i < samples; i++) {
            double envelope = Math.min(1.0, Math.min(i, samples - i) / (double) fade);
            short v = (short) (hz == 0 ? 0 : Math.sin(2 * Math.PI * hz * i / rate) * envelope * 0.7 * Short.MAX_VALUE);
            out.write(v & 0xff);
            out.write((v >> 8) & 0xff);
        }
    }
}
