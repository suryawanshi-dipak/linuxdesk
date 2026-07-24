# LinuxDesk

A native desktop SSH GUI and remote management workspace built for Linux system administration with complete command transparency.

## Status

Early v1 in active development. The login window, SSH/SFTP connection, and remote folder browser are built and UI-verified locally, but not yet tested end-to-end against a real VM. Everything else in the [product analysis / SRS doc](Documentation/LinuxDesk-Product-Analysis-SRS.md) is still ahead of us.

## Tech stack

- **Java 21 + JavaFX** — desktop UI
- **Apache MINA SSHD** — SSH/SFTP client (key-based auth, no external `ssh` binary required)
- **Maven** — build

## What's implemented so far

- **Login window** — Host/IP, port, username, private key file (with file browser), optional passphrase (never persisted), and a live preview of the equivalent `ssh` command.
- **Saved connection profile** — host/port/username/key path persist to `~/.linuxdesk/profile.properties` between runs (passphrase is always excluded).
- **SSH/SFTP connection** — key-based authentication via Apache MINA SSHD, run off the UI thread so the window never freezes while connecting.
- **Remote folder browser** — after a successful connection, the user's home directory opens as a desktop-style icon grid (folders/files as icons, double-click to navigate into a folder, Back button, breadcrumb path, Disconnect).
- **Custom dark/light UI** — undecorated window with our own title bar (drag to move, minimize/maximize/close) instead of the native OS chrome, plus a light/dark theme toggle button that persists across restarts.

## Known limitations (by design, for now)

- Host key verification is currently disabled (`AcceptAllServerKeyVerifier`) — accepts any server key. This needs to become a real known_hosts / trust-on-first-use check before pointing it at untrusted networks.
- No window edge-resizing yet (undecorated window can still be maximized/minimized/moved, just not resized by dragging an edge).
- Single saved profile only — no multi-profile management UI yet.

## Getting started

Requires JDK 21 and Maven.

```powershell
mvn javafx:run
```

## Project layout

```
src/main/java/com/linuxdesk/
  App.java                 # JavaFX entry point, scene/theme/title-bar wiring
  model/ConnectionProfile.java
  profile/ProfileStore.java   # persists connection details (never the passphrase)
  ssh/SshSessionManager.java  # SSH + SFTP session handling (Apache MINA SSHD)
  ssh/RemoteEntry.java
  ui/LoginController.java
  ui/DesktopController.java   # remote folder icon-grid view
  ui/TitleBar.java            # custom title bar (drag, min/max/close, theme toggle)
  ui/ThemeManager.java        # light/dark theme switching + persistence
  ui/IconFactory.java         # drawn folder/file icons (no image assets)
src/main/resources/com/linuxdesk/
  login.fxml
  desktop.fxml
  dark-theme.css
  light-theme.css
```
