# LinuxDesk

A native desktop SSH GUI and remote management workspace built for Linux system administration with complete command transparency.

## Status

Early v1 in active development. The login window, SSH/SFTP connection, remote folder browser, file editor, file management (copy/paste/rename/delete/new), an interactive SSH terminal, and a remote task manager are built and UI-verified locally, but not yet tested end-to-end against a real VM. Everything else in the [product analysis / SRS doc](Documentation/LinuxDesk-Product-Analysis-SRS.md) is still ahead of us.

## Tech stack

- **Java 21 + JavaFX** — desktop UI
- **Apache MINA SSHD** — SSH/SFTP client (key-based auth, no external `ssh` binary required)
- **Maven** — build

## What's implemented so far

- **Login window** — Host/IP, port, username, private key file (with file browser), optional passphrase (never persisted), and a live preview of the equivalent `ssh` command.
- **Saved connection profile** — host/port/username/key path persist to `~/.linuxdesk/profile.properties` between runs (passphrase is always excluded).
- **SSH/SFTP connection** — key-based authentication via Apache MINA SSHD, run off the UI thread so the window never freezes while connecting.
- **Remote folder browser** — after a successful connection, the user's home directory opens as a desktop-style icon grid (folders/files as icons, double-click to navigate into a folder, Back button, breadcrumb path, Disconnect).
- **File editor** — double-click a remote text file (up to 2 MB) to open it in a simple text editor window and save changes back over SFTP.
- **File/folder context menu (right-click)** — Copy, Paste, Rename, and Delete on any file or folder. Delete and copy both recurse into directories; paste auto-suffixes `(copy)` on name collisions. Works on any file type, including zips, since copy/delete operate on raw bytes/paths.
- **Background context menu (right-click empty space)** — Refresh, Sort by (Name/Size), New Folder/File, Paste, and Open Terminal Here (opens a terminal already `cd`'d into that folder).
- **Interactive SSH terminal** — a real PTY-backed shell window (Terminal button in the toolbar) for running commands directly on the remote host, with ANSI escape codes filtered out for clean plain-text output.
- **Task manager** — a Windows Task Manager–style window (Task Manager button in the toolbar) listing remote processes (Name, PID, User, Status, CPU%, Memory) via `ps` over SSH, sortable by column, searchable by name/PID, auto-refreshing every 2 seconds, with an End Task button (`kill -9`) behind a confirmation dialog.
- **Custom dark/light UI** — undecorated window with our own title bar (drag to move, minimize/maximize/close) instead of the native OS chrome, plus a light/dark theme toggle button that persists across restarts and is applied consistently across every window and dialog.

## Known limitations (by design, for now)

- Host key verification is currently disabled (`AcceptAllServerKeyVerifier`) — accepts any server key. This needs to become a real known_hosts / trust-on-first-use check before pointing it at untrusted networks.
- No window edge-resizing yet (undecorated window can still be maximized/minimized/moved, just not resized by dragging an edge).
- Single saved profile only — no multi-profile management UI yet.
- SFTP has no server-side copy command, so Paste streams file bytes through the client; large files/directories will be slower than a native `cp` on the server.
- The terminal is a simple line-based PTY console, not a full terminal emulator — full-screen interactive programs (vim, top, less) won't render correctly in it.

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
  ssh/TerminalSession.java    # wraps a PTY shell channel for the terminal window
  ui/LoginController.java
  ui/DesktopController.java   # remote folder icon-grid view + file/folder context menus
  ui/EditorController.java    # remote text file viewer/editor
  ui/TerminalController.java  # interactive SSH terminal window
  ui/TaskManagerController.java # remote process list + End Task
  ui/AnsiFilter.java          # strips ANSI escape codes for plain-text terminal output
  ui/TitleBar.java            # custom title bar (drag, min/max/close, theme toggle)
  ui/ThemeManager.java        # light/dark theme switching + persistence (windows + dialogs)
  ui/IconFactory.java         # drawn folder/file icons (no image assets)
src/main/resources/com/linuxdesk/
  login.fxml
  desktop.fxml
  editor.fxml
  terminal.fxml
  task-manager.fxml
  dark-theme.css
  light-theme.css
```
