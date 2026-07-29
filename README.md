# LinuxDesk

A native desktop SSH GUI and remote management workspace built for Linux system administration with complete command transparency.

## Status

Early v1 in active development. The login window, SSH/SFTP connection, remote folder browser, file editor, file management (copy/paste/rename/delete/new), local↔remote upload/download, an interactive SSH terminal, a remote task manager, a systemd service manager, and a live log viewer are built and UI-verified locally, but not yet tested end-to-end against a real VM. Everything else in the [product analysis / SRS doc](Documentation/LinuxDesk-Product-Analysis-SRS.md) is still ahead of us.

## Tech stack

- **Java 21 + JavaFX** — desktop UI
- **Apache MINA SSHD** — SSH/SFTP client (key-based auth, no external `ssh` binary required)
- **Maven** — build

## What's implemented so far

- **Login window** — Host/IP, port, username, private key file (with file browser), optional passphrase (never persisted), and a live preview of the equivalent `ssh` command.
- **Saved connection profile** — host/port/username/key path persist to `~/.linuxdesk/profile.properties` between runs (passphrase is always excluded).
- **SSH/SFTP connection** — key-based authentication via Apache MINA SSHD, run off the UI thread so the window never freezes while connecting.
- **Remote folder browser** — after a successful connection, the user's home directory opens as a desktop-style icon grid (folders/files as icons, double-click to navigate into a folder, Back button, breadcrumb path).
- **Windows-style taskbar** — a bottom taskbar with a "LinuxDesk" Start button that opens a popup panel (Task Manager, Terminal, Disconnect) anchored above it, plus a compact (220px) Windows-search-style field: typing shows a live dropdown of matches — app commands (Task Manager/Terminal/Disconnect) first, then files/folders in the current directory (icon + name, capped at 20), separated visually — without touching the icon grid behind it. Click a result or press Enter (opens the top match) to run/open it, Escape or clicking away dismisses the dropdown, and it reopens on refocus if there's still a query.
- **File editor** — double-click a remote text file (up to 2 MB) to open it in a simple text editor window and save changes back over SFTP.
- **File/folder context menu (right-click)** — Copy, Paste, Rename, and Delete on any file or folder. Delete and copy both recurse into directories; paste auto-suffixes `(copy)` on name collisions. Works on any file type, including zips, since copy/delete operate on raw bytes/paths.
- **Background context menu (right-click empty space)** — Refresh, Sort by (Name/Size), New Folder/File, Upload (File.../Folder...), Paste, and Open Terminal Here (opens a terminal already `cd`'d into that folder).
- **Upload / Download** — Upload a local file or folder (recursively) into the current remote directory via the background menu, with an overwrite confirmation on name collisions; Download any remote file or folder (recursively) to a chosen local folder via the file's context menu.
- **Interactive SSH terminal** — a real PTY-backed shell window (opened from the Start panel) for running commands directly on the remote host, with ANSI escape codes filtered out for clean plain-text output.
- **Task manager** — a Windows Task Manager–style window (opened from the Start panel) with a Processes/Services toggle in one shared window:
  - **Processes** — remote processes (Name, PID, User, Status, CPU%, Memory) via `ps` over SSH, sortable by column, searchable by name/PID, auto-refreshing every 2 seconds, with an End Task button (`kill -9`) behind a confirmation dialog.
  - **Services** — systemd units (Name, Active, Sub, Enabled, Description) via `systemctl`, searchable, with Start/Stop/Restart/Enable/Disable actions (via passwordless sudo) behind a confirmation dialog. Stopping, restarting, or disabling `ssh`/`sshd` requires typing the exact unit name to confirm, since that could cut off the SSH session managing it. Loaded lazily on first switching to this view; auto-refresh only runs for the active view.
- **Log viewer** — a live-tailing window (opened from the Start panel) over either the system journal (`journalctl -f`) or an arbitrary log file path (`tail -F`, so it follows across log rotation by filename). Streams new lines in as they arrive, with a substring filter that re-scans everything already loaded (not just new lines), an auto-scroll toggle, and Start/Stop/Clear controls to restart against a different source.
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
  ssh/RemoteProcess.java      # parsed `ps` row for the task manager
  ssh/RemoteService.java      # parsed `systemctl` row for the service manager
  ssh/TerminalSession.java    # wraps a PTY shell channel for the terminal window
  ssh/LogSession.java         # wraps a non-interactive exec channel for live log tailing
  ui/LoginController.java
  ui/DesktopController.java   # remote folder icon-grid view, taskbar, Start panel, file/folder context menus
  ui/EditorController.java    # remote text file viewer/editor
  ui/TerminalController.java  # interactive SSH terminal window
  ui/TaskManagerController.java # merged process/service manager with a view toggle
  ui/LogViewerController.java # journalctl/log file live tail with substring filter
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
  dark-theme.css
  light-theme.css
```
