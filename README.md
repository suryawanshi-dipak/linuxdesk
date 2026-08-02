# LinuxDesk

A native desktop SSH GUI and remote management workspace built for Linux system administration with complete command transparency.

## Status

Early v1 in active development. The login window with multi-profile management and connection history, SSH/SFTP connection with host key verification, remote folder browser with recent folders/files, drag-and-drop, a syntax-highlighting file editor with find/replace, file management (copy/paste/rename/delete/new), permissions/ownership editing, archive compress/extract, local↔remote upload/download, an interactive SSH terminal, a remote task manager, a systemd service manager, a live log viewer, a system monitor dashboard, a tamper-evident audit log, and the first slice of the deployment workflow (local↔remote comparison) are built and UI-verified locally, tested end-to-end against a real VM (Oracle VM OCI). Everything else in the [product analysis / SRS doc](Documentation/LinuxDesk-Product-Analysis-SRS.md) is still ahead of us.

## Tech stack

- **Java 21 + JavaFX** — desktop UI
- **Apache MINA SSHD** — SSH/SFTP client (key- or password-based auth, no external `ssh` binary required)
- **BouncyCastle + net.i2p.crypto:eddsa** — widen private-key format support beyond what the JDK's own providers parse (RSA/ECDSA/Ed25519/DSA, PEM/PKCS#1/PKCS#8, OpenSSH format, encrypted or not)
- **RichTextFX** — the code editor's syntax highlighting and line numbers (plain JavaFX `TextArea` has no per-character styling API)
- **Maven** — build

## What's implemented so far

- **Login window** — Host/IP, port, username, a Private Key / Password toggle for the auth method, private key file (with file browser) with optional passphrase or a plain password (neither ever persisted), and a live preview of the equivalent `ssh` command. Selecting a `.pub` file or a nonexistent key path fails fast with a clear message instead of a raw SSH exception. The client is explicitly configured to use *only* the credential entered in the form — Apache MINA SSHD's default behavior of also trying the user's `~/.ssh/id_*` files (mirroring the `ssh` CLI) is disabled, so a wrong password can't silently "succeed" via an unrelated key.
- **Multi-profile management** — a sidebar lists every saved profile (colour dot + name + PROD badge), with an incremental search box and New/Duplicate/Delete actions. Each profile has a name, colour tag (6-swatch picker), and a Production flag, persisted to `~/.linuxdesk/profiles.properties` (passphrase always excluded); the last-used profile is reselected automatically on the next launch. An old single-profile `profile.properties` from before this feature is migrated in automatically as a "Default" profile the first time the app runs. A profile marked Production shows a red "PRODUCTION" badge in the desktop toolbar once connected, and recursive folder deletes on that connection require typing the folder's name to confirm instead of a plain Yes/No click.
- **Connection history & recent items** — the login sidebar toggles between "Profiles" and "Recent": the latter lists the last 20 successful connections (deduped by host/port/username, newest first, relative timestamps like "2h ago"), where selecting one and clicking "Reconnect" fills the form and connects in one action; "Remove"/"Clear All" delete individual or all entries. Once connected, clearing the desktop search box (or focusing it while empty) shows up to 15 recently visited folders and 15 recently opened files *for that host*, one click to jump back in, with a "Clear recent items" option — all persisted to `~/.linuxdesk/history.properties` and `~/.linuxdesk/recent.properties`.
- **SSH/SFTP connection** — key-based authentication via Apache MINA SSHD, run off the UI thread so the window never freezes while connecting.
- **Host key verification** — trust-on-first-use against a per-user known_hosts store (`~/.linuxdesk/known_hosts`, standard OpenSSH format). First connection to a host shows its SHA-256/MD5 fingerprint and requires explicit "Trust and Connect"; if a previously-trusted host later presents a *different* key, the connection is blocked behind a distinct, harder-to-dismiss warning dialog (default button is Cancel, and the "trust anyway" button stays disabled until you tick a box confirming the change is expected) rather than a routine "click OK" prompt.
- **Remote folder browser** — after a successful connection, the user's home directory opens as a desktop-style icon grid (folders/files as icons, double-click to navigate into a folder, Back button, breadcrumb path).
- **Windows-style taskbar** — a bottom taskbar with a "LinuxDesk" Start button that opens a popup panel (Task Manager, Terminal, Disconnect) anchored above it, plus a compact (220px) Windows-search-style field: typing shows a live dropdown of matches — app commands (Task Manager/Terminal/Disconnect) first, then files/folders in the current directory (icon + name, capped at 20), separated visually — without touching the icon grid behind it. Click a result or press Enter (opens the top match) to run/open it, Escape or clicking away dismisses the dropdown, and it reopens on refocus if there's still a query. Focusing the field while it's empty shows recently visited folders/files for this host instead (see Connection history & recent items below).
- **File editor** — double-click a remote text file (up to 2 MB) to open it in a RichTextFX-based code editor with line numbers, save-back-over-SFTP, and:
  - **Syntax highlighting** — comments/strings/numbers highlighted generically across file types, plus a keyword set for `.java`, `.py`, `.js`/`.ts`, and shell scripts (`.sh`/`.bash`/`.bashrc`/`.profile`). Recomputed 150ms after you stop typing, not on every keystroke.
  - **Find/Replace** — Ctrl+F opens a bar with Find/Previous/Next, a live match count, Replace/Replace All, and a case-sensitivity toggle; Escape closes it.
- **File/folder context menu (right-click)** — Copy, Paste, Rename, and Delete on any file or folder. Delete and copy both recurse into directories; paste auto-suffixes `(copy)` on name collisions. Works on any file type, including zips, since copy/delete operate on raw bytes/paths.
- **Compress / Extract** — "Compress to" (Zip or tar.gz) on any file or folder, and "Extract Here" on recognized archives (`.zip`, `.tar`, `.tar.gz`/`.tgz`, `.tar.bz2`/`.tbz2`, `.tar.xz`/`.txz`), both via the file's context menu. Runs the server's native `zip`/`tar`/`unzip` rather than streaming bytes through the SFTP client, and extraction always lands in a fresh subfolder named after the archive (never directly into the current folder) so any path-traversal entries in a malicious archive stay contained to that subtree. Both prompt for confirmation on a name collision.
- **Permissions / Ownership** — "Permissions..." on the file context menu opens a chmod/chown dialog: a 9-checkbox rwx matrix plus setuid/setgid/sticky (with tooltips), bidirectionally synced with a live octal field (edit either one, the other updates), a warning that appears for world-writable or setuid-on-executable combinations, owner/group text fields, and (for folders) an "apply recursively" option that first counts affected items (`find | wc -l`) and shows that count in a confirmation before running `chmod`/`chown -R`.
- **Background context menu (right-click empty space)** — Refresh, Sort by (Name/Size), New Folder/File, Upload (File.../Folder...), Paste, and Open Terminal Here (opens a terminal already `cd`'d into that folder).
- **Upload / Download** — Upload a local file or folder (recursively) into the current remote directory via the background menu, with an overwrite confirmation on name collisions; Download any remote file or folder (recursively) to a chosen local folder via the file's context menu.
- **Drag and drop** — drag files/folders from Windows Explorer onto the icon grid (uploads into the current directory) or directly onto a folder icon (uploads into that folder), with a highlight on the drop target while dragging over it. Drag a remote *file* icon out to Explorer to download it — since JavaFX's drag API needs a real local file before an OS drag can start, this downloads it to a temp folder synchronously the moment you start dragging, then hands that local file to the OS drag (not a true virtual-file/delayed-render drag). Directories can't be dragged out this way, only in.
- **Interactive SSH terminal** — a real PTY-backed shell window (opened from the Start panel) for running commands directly on the remote host, with ANSI escape codes filtered out for clean plain-text output.
- **Task manager** — a Windows Task Manager–style window (opened from the Start panel) with a Processes/Services toggle in one shared window:
  - **Processes** — remote processes (Name, PID, User, Status, CPU%, Memory) via `ps` over SSH, sortable by column, searchable by name/PID, auto-refreshing every 2 seconds, with an End Task button (`kill -9`) behind a confirmation dialog.
  - **Services** — systemd units (Name, Active, Sub, Enabled, Description) via `systemctl`, searchable, with Start/Stop/Restart/Enable/Disable actions (via passwordless sudo) behind a confirmation dialog. Stopping, restarting, or disabling `ssh`/`sshd` requires typing the exact unit name to confirm, since that could cut off the SSH session managing it. Loaded lazily on first switching to this view; auto-refresh only runs for the active view.
- **Log viewer** — a live-tailing window (opened from the Start panel) over either the system journal (`journalctl -f`) or an arbitrary log file path (`tail -F`, so it follows across log rotation by filename). Streams new lines in as they arrive, with a substring filter that re-scans everything already loaded (not just new lines), an auto-scroll toggle, and Start/Stop/Clear controls to restart against a different source.
- **System monitor** — a dashboard window (opened from the Start panel) polling one batched command every 2 seconds (`/proc/stat`, `free -b`, `df -h`, in a single SSH exec round-trip): live CPU% and Memory% line charts (rolling 2-minute / 60-sample window, CPU computed client-side as a delta between consecutive `/proc/stat` jiffie snapshots), plus a disk usage table (Filesystem/Size/Used/Avail/Use%/Mounted on).
- **Deploy — local↔remote compare, selective sync, and ignore patterns** (opened from the Start panel) — the first slices of the SRS's Deployment workflow (§5.4). Pick a local folder and a remote target path (prefilled with the current desktop folder), click Compare, and every file is classified as Identical, Modified, New (local), or Remote only in a colour-coded table with sizes and modified times, each with a checkbox (Modified/New-local default checked; Remote-only defaults unchecked, since deleting is opt-in). **Deploy Selected** uploads the checked files — creating any missing remote subdirectories first — and deletes checked remote-only files, then re-compares automatically. An editable **ignore patterns** box (`.gitignore`-style: comments, `*`/`?` wildcards, trailing `/` for directory-only, `/` in a pattern anchors it to the root) is prefilled with the SRS's default set and can be extended with an **Import .gitignore** button that reads the local folder's own `.gitignore`; ignored entries are pruned from both the local walk and the remote SFTP walk (matching directories are never even descended into). The identical/modified decision is size-only (not mtime) — nothing yet preserves timestamps across an upload, so comparing by mtime made byte-identical files show as "Modified" purely from upload-time drift; this is a deliberate, documented simplification pending real content comparison later. No automatic backup or rollback yet.
- **Audit log** — a local, tamper-evident record (opened from the Start panel) of every connect/disconnect, delete, chmod/chown, service start/stop/restart/enable/disable, and process kill LinuxDesk performs, across every host — timestamp, host, remote user, action, outcome (success/failure), and an error excerpt on failure. Each entry's hash covers its own fields plus the previous entry's hash, so a "Verify Integrity" button can detect any edited or deleted line; passwords/passphrases/key material are never logged. Searchable by host/user/action/detail. Stored at `~/.linuxdesk/audit.log`.
- **Custom dark/light UI** — undecorated window with our own title bar (drag to move, minimize/maximize/close) instead of the native OS chrome, plus a light/dark theme toggle button that persists across restarts and is applied consistently across every window and dialog.

## Known limitations (by design, for now)

- Only private-key and plain-password authentication are supported — no keyboard-interactive/MFA (`FR-CON-023–024`), SSH agent/Pageant integration (`FR-CON-025–026`), or OpenSSH certificate auth (`FR-CON-027`).
- Host key verification uses LinuxDesk's own known_hosts store, not `~/.ssh/known_hosts` — it won't see hosts you've already trusted via OpenSSH/PuTTY (and vice versa), and there's no UI yet to view/remove stored entries (`FR-CON-043–044`) or an org-wide strict mode (`FR-CON-045`).
- No window edge-resizing yet (undecorated window can still be maximized/minimized/moved, just not resized by dragging an edge).
- Profiles are a flat list — no nested folders/groups, free-form tags, or a health/reachability indicator (`FR-CON-052–053, 060`). No import from PuTTY/WinSCP/`~/.ssh/config`/Termius, no encrypted export (`FR-CON-057–058`). The Production flag only gates recursive folder deletes; it doesn't (yet) extend to service stop/restart or package removal, and there's no title-bar-wide red tint — just a toolbar badge.
- No explicit bookmarks (`FR-CON-070–071`) — recent folders cover the common case for a single-user tool, but there's no way to pin a directory you don't happen to have visited recently. Connection history doesn't track session duration or disconnect reason (`FR-CON-072`), just host/port/username/timestamp. Recent folders/files can only be cleared all-at-once per host, not removed one at a time (connection history *does* support single-item removal).
- SFTP has no server-side copy command, so Paste streams file bytes through the client; large files/directories will be slower than a native `cp` on the server.
- Upload/Download has no progress bar, pause/resume, or parallel streams — it's a single blocking transfer per file with only a status-bar message until it finishes.
- The terminal is a simple line-based PTY console, not a full terminal emulator — full-screen interactive programs (vim, top, less) won't render correctly in it.
- Service control actions assume passwordless sudo (`sudo -n`) for the connected user; on a host without it configured, Start/Stop/Restart/Enable/Disable will fail with a permission error surfaced in the status bar rather than prompting for a password.
- The log viewer keeps every loaded line in memory for the life of the window (no windowed/bounded loading) and filters by plain substring match, not regex — fine for typical sessions, but not meant for tailing a firehose log for hours or matching complex patterns.
- The system monitor covers CPU, memory, and disk only — no network throughput, no per-core CPU breakdown, no configurable poll interval (fixed at 2s), and no historical retention beyond the in-window 60 samples (closing the window loses the chart history).
- Compress/Extract shells out to `zip`/`unzip`/`tar` on the remote host, so it fails with a plain error if those tools aren't installed there. No progress reporting for large archives, no browsing archive contents without extracting, and no password-protected zips.
- Ownership changes assume passwordless sudo (`sudo -n chown`), same caveat as service control; owner/group are free-text fields rather than pickers populated from the remote host's actual users/groups, and there's no ACL/xattr/SELinux-context support.
- The editor's syntax highlighting is regex-based, not a real parser — it doesn't understand nested/multi-line constructs beyond `/* */` block comments, and the keyword set only covers Java/Python/JS/shell; other file types still get comment/string/number coloring but no keyword highlighting. Find/Replace is plain substring, not regex.
- Drag-out to Explorer blocks the UI thread while the file downloads to a temp folder (no progress indicator, and it can be slow for large files); drag-out only works for files, not folders. This is the exact risk the SRS itself flags as the highest-risk drag/drop item — a real virtual-file (delayed-render) drag needs native OS shell integration that JavaFX doesn't provide.
- The audit log only covers connect/disconnect, delete, chmod/chown, service control, and process kill — not every action (e.g. rename, upload/download, compress/extract, edits made in the file editor aren't logged). It's a local file with `Files.readAllLines`-based tamper detection, not a real database — no retention policy/rotation, no CSV/JSON export, no syslog/SIEM forwarding, and command text/exit codes/durations from the SRS's full record shape aren't captured, just action/outcome/detail.
- Deploy has no automatic backup, no one-click rollback, no pre/post-deploy hooks, no health checks, and no named deployment profiles/history yet (`FR-DEP-030–060` mostly still ahead). Ignore patterns are a deliberately simple `.gitignore` subset — no negation (`!`), no `**`, no character classes. Comparison is size-only, so two different files that happen to share a size would be misclassified as identical — no checksum fallback yet (`FR-DEP-003`).

## Getting started

Requires JDK 21 and Maven.

```powershell
mvn javafx:run
```

## Project layout

```
src/main/java/com/linuxdesk/
  App.java                    # JavaFX entry point, scene/theme/title-bar wiring
  model/ConnectionProfile.java # name, colour tag, Production flag, connection fields
  model/ConnectionHistoryEntry.java # one past connection: host/port/user/key path/timestamp
  profile/ProfileStore.java   # persists the named profile list (never the passphrase), migrates the old single-profile store
  profile/ConnectionHistoryStore.java # persists recent successful connections for the login screen's Recent tab
  profile/RecentPathsStore.java # persists recently visited folders/opened files, per host
  audit/AuditLogEntry.java    # one audit record: host/user/action/outcome/detail + hash chain fields
  audit/AuditLogStore.java    # append-only, hash-chained audit log persistence and integrity check
  audit/AuditRecorder.java    # small callback interface so controllers don't depend on AuditLogStore directly
  ssh/SshSessionManager.java  # SSH + SFTP session handling (Apache MINA SSHD)
  ssh/HostKeyPrompt.java      # callback for TOFU/host-key-changed decisions, implemented by LoginController
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
  deploy/DeployDiffEntry.java # one file's compare result: identical/modified/local-only/remote-only
  deploy/DeployComparer.java  # walks local + remote trees and classifies every file
  deploy/IgnorePatterns.java  # small .gitignore-subset matcher (wildcards, dir-only, anchored)
  ui/LoginController.java
  ui/DesktopController.java   # remote folder icon-grid view, taskbar, Start panel, file/folder context menus
  ui/PermissionsDialog.java   # chmod/chown modal dialog
  ui/EditorController.java    # RichTextFX code editor + find/replace
  ui/SyntaxHighlighter.java   # regex-based comment/string/number/keyword highlighting
  ui/TerminalController.java  # interactive SSH terminal window
  ui/TaskManagerController.java # merged process/service manager with a view toggle
  ui/LogViewerController.java # journalctl/log file live tail with substring filter
  ui/MonitorController.java   # CPU/memory line charts + disk usage table
  ui/AuditLogController.java  # searchable audit log table + integrity-check button
  ui/DeployController.java    # local↔remote compare window
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
  audit-log.fxml
  deploy.fxml
  dark-theme.css
  light-theme.css
```
