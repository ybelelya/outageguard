# OutageGuard

Shuts your PC down safely before scheduled power outages.

It downloads your group's schedule from the public [Yasno](https://yasno.com.ua) API and:

- **10 minutes before** an outage, plays an alarm sound and shows a desktop notification;
- **5 minutes before**, shuts the PC down.

Works on **Windows** and **Ubuntu**. Written in Java 25 with Gson, SLF4J + Logback and Lombok.

## Supported regions

Any region and grid operator (DSO) that Yasno publishes schedules for. Set them with `region` and `dso` in the config. Currently:

| Region | `region` | `dso` | Groups |
|---|---|---|---|
| Kyiv (city) | `25` | `902`: DTEK Kyiv Electric Networks (default) | `1.1` … `60.1` |
| Dnipro | `3` | `301`: DnEM, or `303`: TsEK | `1.1` … `6.2` |

Yasno's current list is at `https://app.yasno.ua/api/blackout-service/public/shutdowns/addresses/v2/regions`.

Other regions (Kyiv oblast, Lviv, Odesa, Kharkiv, …) publish their schedules through their own utilities, not Yasno, so they aren't supported yet. Adding one means writing another schedule client next to `YasnoClient`.

## How it works

- **Schedule checks.** At startup and then every hour at hh:01 (00:01, 01:01, …). This picks up tomorrow's schedule once it's published, plus any changes made during the day. If a download fails (for example, no internet), that check is skipped and the last downloaded schedule stays in use until the next hourly check.
- **Back-to-back blocks** are treated as one outage. For example, 22:30–24:00 today plus 00:00–03:00 tomorrow is a single 22:30–03:00 outage.
- **Turned on during an outage** (on battery, UPS or power bank): the current outage is ignored. Only outages that haven't started yet trigger the warning and shutdown.
- **Started late:** if the app learns about an outage less than 5 minutes before it starts, you still get the warning and at least 60 seconds before shutdown.
- **Sleep and hibernate:** the app checks the clock every 15 seconds, so timing stays correct after the PC wakes up.
- **Emergency outages** are not in the planned schedule and can't be predicted.

## Requirements

- **Jar mode:** Java 25 on the PC (Windows: Amazon Corretto or Temurin; Ubuntu: `sudo apt install openjdk-25-jre`).
- **Exe mode (Windows):** nothing. The Java runtime is bundled.

## Configuration

The first run creates `~/.outageguard/config.properties` (`C:\Users\<you>\.outageguard\` on Windows). The default group is `4.1`. Change `group` to your own:

```properties
# Your outage queue. Look it up by your address on https://yasno.com.ua
group=4.1

# Yasno region / DSO ids (see "Supported regions"). 25 / 902 = Kyiv city, 3 / 301 or 3 / 303 = Dnipro.
region=25
dso=902

warnMinutes=10
shutdownMinutes=5

# Slot types treated as an outage (comma separated)
outageTypes=Definite

# true = only log and notify, never really shut down (for testing)
dryRun=false

# Empty = default (Windows: shutdown /s /f /t 0, Linux: systemctl poweroff)
# e.g. "shutdown /h" to hibernate on Windows, "systemctl hibernate" on Linux
shutdownCommand=
```

Any setting can also be passed on the command line, e.g. `--group=3.1 --dryRun=true`. Command-line values apply to that run only and override the file.

### Changing the group

Settings are read once, at startup, so restart the app after editing them.

1. **Find your group.** Enter your address on [yasno.com.ua](https://yasno.com.ua). It shows your queue, e.g. "Черга 3.1" → `3.1`.
2. **Edit the config** and set `group=3.1`:
   ```powershell
   notepad "$env:USERPROFILE\.outageguard\config.properties"     # Windows
   ```
   ```bash
   nano ~/.outageguard/config.properties                           # Ubuntu
   ```
3. **Check it (optional).** `java -jar build/libs/outageguard.jar --status` should print `Group 3.1:` with its outages. An unknown group prints a list of the valid ones.
4. **Restart the app:**
   ```powershell
   Stop-ScheduledTask OutageGuard; Start-ScheduledTask OutageGuard   # Windows (or tray → Exit, then Start-ScheduledTask)
   ```
   ```bash
   systemctl --user restart outageguard                                # Ubuntu
   ```
5. **Confirm.** After the restart, `outageguard.log` should show `OutageGuard started: group 3.1, ...`.

Reinstalling or updating the app (and `uninstall-windows.ps1 -KeepConfig`) doesn't touch `config.properties`, so your group is kept. To change city, edit `region` and `dso` the same way (see [Supported regions](#supported-regions)).

> The default Windows command uses `/f`, which force-closes apps. **Unsaved work is lost**, so save when you get the warning.

## Can anything block the shutdown?

On Windows, `shutdown /s /f /t 0` closes apps by force, so the "This app is preventing shutdown" screen doesn't appear:

| Situation | What happens |
|---|---|
| Unsaved document (Word, Notepad, …) | Closed without asking. **Changes are lost.** |
| Hung or frozen app | Killed. |
| App that tries to block shutdown (some games, installers, backup tools) | Its block is ignored. |
| Windows services | Get a few seconds to stop, then are killed. |
| Pending Windows updates | Shutdown may take longer, but it still happens. |

### What can still stop it

1. **An installer or Windows Update holding a system lock.** In rare cases Windows refuses a new shutdown with an error such as "a system shutdown is in progress" or "the system is busy".
2. **A work PC with a policy** that removes the user's right to shut down. Not an issue on a normal home PC.
3. **On Ubuntu, a shutdown inhibitor.** Some apps (download managers, media players) hold a lock that makes `systemctl poweroff` fail with "Operation inhibited". To ignore those locks, set `shutdownCommand=systemctl poweroff --check-inhibitors=no` (systemd 248+, which Ubuntu 22.04 and later have).

If the shutdown command fails, OutageGuard logs the exit code and error message, notifies you, and tries again after 30 seconds. If the second try also fails, it notifies you to shut down manually before the outage starts.

## Build

To build you need **JDK 25**, the full JDK rather than only the runtime, because it includes `jpackage`. Gradle itself is downloaded by the wrapper (`gradlew`).

- **Windows:** [Amazon Corretto 25](https://aws.amazon.com/corretto/) or [Temurin 25](https://adoptium.net/).
- **Ubuntu:** `sudo apt install openjdk-25-jdk`.

| What | Windows | Ubuntu | Result |
|---|---|---|---|
| Jar | `.\gradlew.bat build` | `./gradlew build` | `build/libs/outageguard.jar`, a single runnable jar. Runs anywhere with Java 25. |
| Native app | `.\gradlew.bat packageApp` | `./gradlew packageApp` | `build/dist/OutageGuard/` with a bundled runtime (~78 MB), see below |

`build` also runs the tests. Add `-x test` to skip them.

What `packageApp` produces depends on the OS it runs on. jpackage can't cross-build, so build the Windows version on Windows and the Ubuntu version on Ubuntu:

| | Windows | Ubuntu |
|---|---|---|
| Background app (tray) | `OutageGuard\OutageGuard.exe` | `OutageGuard/bin/OutageGuard` |
| Console (`--status`, …) | `OutageGuard\outageguard-cli.exe` | `OutageGuard/bin/outageguard-cli` |

Copy the whole `OutageGuard` folder, not just the launcher, because the launcher needs the `app` and `runtime` folders next to it.

On Ubuntu, if `./gradlew` says *Permission denied*, run `chmod +x gradlew` once.

The bundled runtime contains only the JDK modules listed in `--add-modules` in `build.gradle.kts`. After adding a dependency, rerun `jdeps --print-module-deps --ignore-missing-deps --multi-release 25 build/libs/outageguard.jar` and add any new modules there. Otherwise the exe can fail at runtime with `NoClassDefFoundError`, even though the jar works.

**IDE:** the code uses Lombok (`@Slf4j`, `@UtilityClass`). IntelliJ IDEA supports it out of the box. Enable *Settings → Build → Compiler → Annotation Processors* if it asks.

## Logging

Configured in `src/main/resources/logback.xml`. Logs go to the console and to `~/.outageguard/outageguard.log`. The file rotates at 1 MB and keeps 3 old files. Errors include stack traces. For more detail while troubleshooting, change `<root level="INFO">` to `DEBUG` and rebuild.

> The Ubuntu native app hasn't been tested yet. The jar is the tested path on Ubuntu, and `outageguard.service` uses it.

## Usage

```bash
java -jar build/libs/outageguard.jar                 # run (stays in background / tray)
java -jar build/libs/outageguard.jar --status        # print your group's schedule and exit
java -jar build/libs/outageguard.jar --test-notify   # show a sample warning with sound and exit
```

The exe build has two launchers in the same folder, using the same bundled runtime:

| Launcher | Console | Use for |
|---|---|---|
| `OutageGuard.exe` | no | running in the background / tray (what autostart uses) |
| `outageguard-cli.exe` | yes | `--status`, `--test-notify`, trying settings |

```powershell
build\dist\OutageGuard\outageguard-cli.exe --status
build\dist\OutageGuard\outageguard-cli.exe --test-notify

# after install-windows.ps1 -Exe
& "$env:USERPROFILE\.outageguard\app\outageguard-cli.exe" --status
```

Only one instance runs at a time.

## Autostart

### Windows

From the project folder:

```powershell
# jar mode
.\gradlew.bat build
powershell -ExecutionPolicy Bypass -File scripts\install-windows.ps1

# exe mode
.\gradlew.bat packageApp
powershell -ExecutionPolicy Bypass -File scripts\install-windows.ps1 -Exe
```

The script copies the app into `~/.outageguard` and adds an **OutageGuard** task to Task Scheduler that starts at login, with no console window. To update after a code change, rebuild and run the script again: it stops the running app before replacing it. If you get *Access is denied*, run PowerShell as Administrator.

The app only runs after you log in to Windows. While the PC sits at the login screen, nothing is watching for outages.

### Ubuntu

```bash
./gradlew build
mkdir -p ~/.outageguard ~/.config/systemd/user
cp build/libs/outageguard.jar ~/.outageguard/
cp scripts/outageguard.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now outageguard
```

Logs: `journalctl --user -u outageguard -f`.

`systemctl poweroff` normally works without a password for the logged-in user. If another user is logged in at the same time, Ubuntu may ask for authorization and the shutdown won't happen.

## Tray icon and pausing

On **Windows** the app shows a tray icon (a yellow lightning bolt):

- hover over it to see the next outage;
- right-click → **Pause auto-shutdown** to toggle shutdowns off and on (warnings still appear);
- right-click → **Exit OutageGuard** to stop the app.

Warnings on Windows are toast notifications titled **OutageGuard**, and they stay on screen until you dismiss them. To get that title instead of "OpenJDK Platform binary", the app registers a notification identity under `HKCU\Software\Classes\AppUserModelId\OutageGuard` (current user only, no admin needed). If the toast can't be shown, the app falls back to a tray popup.

On **Ubuntu GNOME** there's usually no tray icon. Java's built-in tray doesn't work with Ubuntu's AppIndicator. Notifications (`notify-send`) and sound still work.

Pausing works through a file on every OS. The tray checkbox and the file always stay in sync:

```bash
touch ~/.outageguard/pause    # pause   (Windows: New-Item "$env:USERPROFILE\.outageguard\pause")
rm ~/.outageguard/pause       # resume  (Windows: Remove-Item "$env:USERPROFILE\.outageguard\pause")
```

The pause stays on until you turn it off. It's not a one-time skip.

## Files

Everything lives in `~/.outageguard/`:

| File | Purpose |
|---|---|
| `config.properties` | settings |
| `outageguard.log` | log. Rotated while running at 1 MB into `outageguard.1.log` … `outageguard.3.log` (oldest is deleted) |
| `schedule-cache-<region>-<dso>.json` | last downloaded schedule, used when offline |
| `pause` | present = auto-shutdown paused |
| `instance.lock` | prevents a second instance from running |
| `icon.png` | icon for Windows toast notifications |
| `outageguard.jar` / `app/` | installed app (jar / exe mode) |

## Uninstall

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File scripts\uninstall-windows.ps1 [-KeepConfig]
```

```bash
# Ubuntu
bash scripts/uninstall-linux.sh [--keep-config]
```

These scripts stop the app, remove autostart, and delete `~/.outageguard`. With the keep option, `config.properties` is kept.

## API

```
GET https://app.yasno.ua/api/blackout-service/public/shutdowns/regions/{region}/dsos/{dso}/planned-outages
```

No authentication needed. For each group (`"1.1"`, `"3.2"`, …), the response has `today` and `tomorrow`. Each slot gives `start` and `end` in minutes from local midnight, plus a `type` (`Definite` = planned outage, `NotPlanned` = power on). Tomorrow shows `status: "WaitingForSchedule"` until it's published.

This is an unofficial use of a public endpoint. If Yasno changes it, the app logs the fetch errors and keeps using the last saved schedule.
