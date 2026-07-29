# LinuxDesk

A native desktop SSH GUI and remote management workspace built for Linux system administration with complete command transparency.

## Status

Early v1 in active development. The login window, SSH/SFTP connection, remote folder browser, a syntax-highlighting file editor with find/replace, file management (copy/paste/rename/delete/new), permissions/ownership editing, archive compress/extract, local↔remote upload/download, an interactive SSH terminal, a remote task manager, a systemd service manager, a live log viewer, and a system monitor dashboard are built and UI-verified locally, but not yet tested end-to-end against a real VM. Everything else in the [product analysis / SRS doc](Documentation/LinuxDesk-Product-Analysis-SRS.md) is still ahead of us.

## Tech stack

- **Java 21 + JavaFX** — desktop UI
- **Apache MINA SSHD** — SSH/SFTP client (key-based auth, no external `ssh` binary required)
- **RichTextFX** — the code editor's syntax highlighting and line numbers (plain JavaFX `TextArea` has no per-character styling API)
- **Maven** — build

## What's implemented so far

- **Login window** — Host/IP, port, username, private key file (with file browser), optional passphrase (never persisted), and a live preview of the equivalent `ssh` command.
- **Saved connection profile** — host/port/username/key path persist to `~/.linuxdesk/profile.properties` between runs (passphrase is always excluded).
- **SSH/SFTP connection** — key-based authentication via Apache MINA SSHD, run off the UI thread so the window never freezes while connecting.
- **Remote folder browser** — after a successful connection, the user's home directory opens as a desktop-style icon grid (folders/files as icons, double-click to navigate into a folder, Back button, breadcrumb path).
- **Windows-style taskbar** — a bottom taskbar with a "LinuxDesk" Start button that opens a popup panel (Task Manager, Terminal, Disconnect) anchored above it, plus a compact (220px) Windows-search-style field: typing shows a live dropdown of matches — app commands (Task Manager/Terminal/Disconnect) first, then files/folders in the current directory (icon + name, capped at 20), separated visually — without touching the icon grid behind it. Click a result or press Enter (opens the top match) to run/open it, Escape or clicking away dismisses the dropdown, and it reopens on refocus if there's still a query.
- **File editor** — double-click a remote text file (up to 2 MB) to open it in a RichTextFX-based code editor with line numbers, save-back-over-SFTP, and:
  - **Syntax highlighting** — comments/strings/numbers highlighted generically across file types, plus a keyword set for `.java`, `.py`, `.js`/`.ts`, and shell scripts (`.sh`/`.bash`/`.bashrc`/`.profile`). Recomputed 150ms after you stop typing, not on every keystroke.
  - **Find/Replace** — Ctrl+F opens a bar with Find/Previous/Next, a live match count, Replace/Replace All, and a case-sensitivity toggle; Escape closes it.
- **File/folder context menu (right-click)** — Copy, Paste, Rename, and Delete on any file or folder. Delete and copy both recurse into directories; paste auto-suffixes `(copy)` on name collisions. Works on any file type, including zips, since copy/delete operate on raw bytes/paths.
- **Compress / Extract** — "Compress to" (Zip or tar.gz) on any file or folder, and "Extract Here" on recognized archives (`.zip`, `.tar`, `.tar.gz`/`.tgz`, `.tar.bz2`/`.tbz2`, `.tar.xz`/`.txz`), both via the file's context menu. Runs the server's native `zip`/`tar`/`unzip` rather than streaming bytes through the SFTP client, and extraction always lands in a fresh subfolder named after the archive (never directly into the current folder) so any path-traversal entries in a malicious archive stay contained to that subtree. Both prompt for confirmation on a name collision.
- **Permissions / Ownership** — "Permissions..." on the file context menu opens a chmod/chown dialog: a 9-checkbox rwx matrix plus setuid/setgid/sticky (with tooltips), bidirectionally synced with a live octal field (edit either one, the other updates), a warning that appears for world-writable or setuid-on-executable combinations, owner/group text fields, and (for folders) an "apply recursively" option that first counts affected items (`find | wc -l`) and shows that count in a confirmation before running `chmod`/`chown -R`.
- **Background context menu (right-click empty space)** — Refresh, Sort by (Name/Size), New Folder/File, Upload (File.../Folder...), Paste, and Open Terminal Here (opens a terminal already `cd`'d into that folder).
- **Upload / Download** — Upload a local file or folder (recursively) into the current remote directory via the background menu, with an overwrite confirmation on name collisions; Download any remote file or folder (recursively) to a chosen local folder via the file's context menu.
- **Interactive SSH terminal** — a real PTY-backed shell window (opened from the Start panel) for running commands directly on the remote host, with ANSI escape codes filtered out for clean plain-text output.
- **Task manager** — a Windows Task Manager–style window (opened from the Start panel) with a Processes/Services toggle in one shared window:
  - **Processes** — remote processes (Name, PID, User, Status, CPU%, Memory) via `ps` over SSH, sortable by column, searchable by name/PID, auto-refreshing every 2 seconds, with an End Task button (`kill -9`) behind a confirmation dialog.
  - **Services** — systemd units (Name, Active, Sub, Enabled, Description) via `systemctl`, searchable, with Start/Stop/Restart/Enable/Disable actions (via passwordless sudo) behind a confirmation dialog. Stopping, restarting, or disabling `ssh`/`sshd` requires typing the exact unit name to confirm, since that could cut off the SSH session managing it. Loaded lazily on first switching to this view; auto-refresh only runs for the active view.
- **Log viewer** — a live-tailing window (opened from the Start panel) over either the system journal (`journalctl -f`) or an arbitrary log file path (`tail -F`, so it follows across log rotation by filename). Streams new lines in as they arrive, with a substring filter that re-scans everything already loaded (not just new lines), an auto-scroll toggle, and Start/Stop/Clear controls to restart against a different source.
- **System monitor** — a dashboard window (opened from the Start panel) polling one batched command every 2 seconds (`/proc/stat`, `free -b`, `df -h`, in a single SSH exec round-trip): live CPU% and Memory% line charts (rolling 2-minute / 60-sample window, CPU computed client-side as a delta between consecutive `/proc/stat` jiffie snapshots), plus a disk usage table (Filesystem/Size/Used/Avail/Use%/Mounted on).
- **Custom dark/light UI** — undecorated window with our own title bar (drag to move, minimize/maximize/close) instead of the native OS chrome, plus a light/dark theme toggle button that persists across restarts and is applied consistently across every window and dialog.

## Known limitations (by design, for now)

- Host key verification is currently disabled (`AcceptAllServerKeyVerifier`) — accepts any server key. This needs to become a real known_hosts / trust-on-first-use check before pointing it at untrusted networks.
- No window edge-resizing yet (undecorated window can still be maximized/minimized/moved, just not resized by dragging an edge).
- Single saved profile only — no multi-profile management UI yet.
- SFTP has no server-side copy command, so Paste streams file bytes through the client; large files/directories will be slower than a native `cp` on the server.
- Upload/Download has no progress bar, pause/resume, or parallel streams — it's a single blocking transfer per file with only a status-bar message until it finishes.
- The terminal is a simple line-based PTY console, not a full terminal emulator — full-screen interactive programs (vim, top, less) won't render correctly in it.
- Service control actions assume passwordless sudo (`sudo -n`) for the connected user; on a host without it configured, Start/Stop/Restart/Enable/Disable will fail with a permission error surfaced in the status bar rather than prompting for a password.
- The log viewer keeps every loaded line in memory for the life of the window (no windowed/bounded loading) and filters by plain substring match, not regex — fine for typical sessions, but not meant for tailing a firehose log for hours or matching complex patterns.
- The system monitor covers CPU, memory, and disk only — no network throughput, no per-core CPU breakdown, no configurable poll interval (fixed at 2s), and no historical retention beyond the in-window 60 samples (closing the window loses the chart history).
- Compress/Extract shells out to `zip`/`unzip`/`tar` on the remote host, so it fails with a plain error if those tools aren't installed there. No progress reporting for large archives, no browsing archive contents without extracting, and no password-protected zips.
- Ownership changes assume passwordless sudo (`sudo -n chown`), same caveat as service control; owner/group are free-text fields rather than pickers populated from the remote host's actual users/groups, and there's no ACL/xattr/SELinux-context support.
- The editor's syntax highlighting is regex-based, not a real parser — it doesn't understand nested/multi-line constructs beyond `/* */` block comments, and the keyword set only covers Java/Python/JS/shell; other file types still get comment/string/number coloring but no keyword highlighting. Find/Replace is plain substring, not regex.

## Getting started

Requires JDK 21 and Maven.

```powershell
mvn javafx:run
```

## Project layout

```
src/main/java/com/linuxdesk/
  App.java                    # JavaFX entry point, scene/theme/title-bar wiring
  model/ConnectionProfile.java
  profile/ProfileStore.java   # persists connection details (never the passphrase)
  ssh/SshSessionManager.java  # SSH + SFTP session handling (Apache MINA SSHD)
  ssh/RemoteEntry.java
  ssh/RemotePermissions.java  # parsed `stat` mode/owner/group for the permissions dialog
  ssh/ArchiveFormat.java      # ZIP / TAR_GZ enum for Compress to
  ssh/RemoteProcess.java      # parsed `ps` row for the task manager
  ssh/RemoteService.java      # parsed `systemctl` row for the service manager
  ssh/TerminalSession.java    # wraps a PTY shell channel for the terminal window
  ssh/LogSession.java         # wraps a non-interactive exec channel for live log tailing
  ssh/CpuTimes.java           # raw /proc/stat jiffie counters for CPU% delta computation
  ssh/MemoryInfo.java         # parsed `free -b` snapshot
  ssh/DiskUsage.java          # parsed `df -h` row
  ssh/SystemSnapshot.java     # one batched CPU+memory+disk sample for the monitor
  ui/LoginController.java
  ui/DesktopController.java   # remote folder icon-grid view, taskbar, Start panel, file/folder context menus
  ui/PermissionsDialog.java   # chmod/chown modal dialog
  ui/EditorController.java    # RichTextFX code editor + find/replace
  ui/SyntaxHighlighter.java   # regex-based comment/string/number/keyword highlighting
  ui/TerminalController.java  # interactive SSH terminal window
  ui/TaskManagerController.java # merged process/service manager with a view toggle
  ui/LogViewerController.java # journalctl/log file live tail with substring filter
  ui/MonitorController.java   # CPU/memory line charts + disk usage table
  ui/AnsiFilter.java          # strips ANSI escape codes for plain-text terminal output
  ui/TitleBar.java            # custom title bar (drag, min/max/close, theme toggle)
  ui/ThemeManager.java        # light/dark theme switching + persistence (windows, dialogs, popups)
  ui/IconFactory.java         # drawn folder/file icons (no image assets)
src/main/resources/com/linuxdesk/
  login.fxml
  desktop.fxml
  editor.fxml
  terminal.fxml
  task-manager.fxml
  log-viewer.fxml
  monitor.fxml
  dark-theme.css
  light-theme.css
```
