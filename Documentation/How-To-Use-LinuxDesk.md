# How to Use LinuxDesk

A practical, step-by-step guide to actually using the app — not just what's built (see the [README](../README.md) for that), but how to drive each feature. Requires JDK 21 + Maven to run locally (`mvn javafx:run`).

## 1. Logging in

1. Launch the app. You land on **Connect to VM**, with a **Profiles** / **Recent** sidebar on the left.
2. Fill in **Host / IP**, **Port** (defaults to `22`), and **Username**.
3. Choose an auth method:
   - **Private Key** — Browse to your key file. If it's encrypted, fill in **Key passphrase**. Selecting the wrong file (e.g. the `.pub` file by mistake) fails immediately with a clear message instead of a cryptic SSH error.
   - **Password** — just type it in. Neither the passphrase nor the password is ever saved to disk.
4. The **EQUIVALENT TO** line under the form shows the plain `ssh` command your inputs correspond to — useful for double-checking exactly what will run.
5. Optionally give the connection a **Profile name**, pick a colour tag, and check **Production** if this is a host where mistakes are expensive (this unlocks extra confirmation prompts later — see §6).
6. Click **Save profile** to keep it in the sidebar for next time, or just click **Test connection** to connect once.
7. **First time connecting to a host**, you'll see its SSH key fingerprint and have to explicitly click **Trust and Connect**. If a *previously trusted* host ever presents a different key, you'll see a much harder-to-dismiss warning — that's not a bug, it's the point (could mean the server was rebuilt, or could mean something worse).

Use the **Recent** tab in the sidebar to reconnect to one of your last 20 connections in one click.

## 2. Browsing and managing files

Once connected, the remote home directory opens as a desktop-style icon grid with a taskbar at the bottom.

- **Navigate**: double-click a folder to enter it; **← Back** to go up.
- **Search / recent items**: click the taskbar search field. Typing filters files/folders in the current directory and app commands (Task Manager, Terminal, etc.); an empty, focused search box shows your recently visited folders and opened files instead.
- **Right-click a file or folder** for Copy, Paste, Rename, Delete, Permissions, Compress to (zip/tar.gz), Extract Here (on recognized archives), and Download.
- **Right-click empty space** for Refresh, Sort by, New Folder/File, Upload, Paste, and Open Terminal Here.
- **Drag and drop**: drag files from Windows Explorer onto the grid (or a specific folder icon) to upload; drag a remote file out to Explorer to download it.
- **Permissions dialog**: right-click → Permissions. Toggle the rwx checkboxes or type an octal mode directly (they stay in sync), set owner/group, and optionally apply recursively — recursive changes show you *how many files* will be affected before you confirm.

## 3. The Start panel

Click the **LinuxDesk** button at the bottom-left (or the taskbar search) to open:

- **Terminal** — a real interactive shell on the remote host.
- **Task Manager** — toggle between **Processes** (`ps`, sortable, searchable, End Task) and **Services** (`systemctl`, Start/Stop/Restart/Enable/Disable). Stopping/restarting/disabling `ssh`/`sshd` requires typing the exact unit name first, since that could cut off your own session.
- **Log Viewer** — tail the system journal or an arbitrary log file, with a live substring filter and auto-scroll.
- **Monitor** — CPU%/Memory% line charts and a disk usage table, refreshing every 2 seconds.
- **Deploy** — see §4, the biggest feature.
- **Audit Log** — a searchable, tamper-evident record of every connect/disconnect, delete, permission change, service action, process kill, deploy, and rollback. Click **Verify Integrity** to confirm no entry has been edited or deleted after the fact.

## 4. Deploying

Open **Deploy** from the Start panel. This is a local↔remote sync workflow with backup and rollback built in.

### 4.1 Compare

1. **Browse...** to pick a local folder.
2. Check/edit the **Remote** path (prefilled with whatever folder you had open).
3. Optionally tweak the **Ignore** box — it's pre-filled with sensible defaults (`.git`, `node_modules`, `*.log`, etc.) in `.gitignore` syntax; click **Import .gitignore** to pull in the local folder's own rules too.
4. Click **Compare**. Every file gets classified: **Identical**, **Modified**, **New (local)**, or **Remote only** — shown in a colour-coded table with sizes and modified times.

By default, same-size files are assumed identical (fast). If you want certainty instead of speed, expand **Hooks & Health Check (optional)** and check **Verify checksums during Compare** — this hashes both sides and re-runs Compare, catching the rare case where two different files happen to be the same size.

### 4.2 Select what to deploy

Each row has a checkbox. **Modified** and **New (local)** files are checked by default (safe, additive). **Remote only** files are *unchecked* by default — deleting something is opt-in, never automatic. Use **Select All** / **Select None** to bulk-toggle, or click individual checkboxes.

### 4.3 (Optional) hooks and health check

Expand **Hooks & Health Check** to configure:

- **Pre-deploy hook** — a command that runs first. If it exits nonzero, the deploy stops before touching anything (no backup, no upload).
- **Post-deploy hook** — runs after a successful upload/delete.
- **Health check** — pick a type from the dropdown and fill in the target (see the table below), set **Retries**/**Interval (s)**, and optionally check **Auto-rollback if health check fails**.

| Type | Target field | Runs |
|---|---|---|
| HTTP | a URL, e.g. `http://your-server/health` | client-side (from your machine) |
| TCP_PORT | `host:port` | client-side |
| PROCESS | a process name | server-side (`pgrep -f`) |
| SYSTEMD_UNIT | a unit name | server-side (`systemctl is-active`) |
| COMMAND | any shell command | server-side (checks exit code) |

The health check only runs as part of an actual **Deploy Selected** — there's no standalone "test" button, and it does *not* run during a dry run (see below).

### 4.4 Deploy

Click **Deploy Selected**. You'll always see a numbered plan first (what gets backed up/uploaded/deleted, hook commands, the health check config) before anything happens:

- Check **Dry run** first if you just want to see the plan without executing it.
- On a profile marked **Production**, confirming requires *typing the host name*, not just clicking a button.
- Otherwise, a plain Yes/No confirmation is enough.

Once confirmed, it runs in order: pre-hook → backup files about to be overwritten → upload → delete → post-hook → health check. The table re-compares automatically afterward.

### 4.5 Rolling back

Click **Rollback** (enabled once at least one backup exists for the current target) to open a picker of the last 5 retained backups, newest first. Pick one, confirm, and it restores those files to their pre-deploy content. Note: rollback only undoes *modified* files from that deploy — it doesn't remove files that deploy newly uploaded, or bring back files that deploy deleted.

## 5. Switching themes

Click the sun/moon icon in the title bar to toggle dark/light — it applies instantly across every open window and dialog, and is remembered next launch.

## 6. What "Production" actually changes

Marking a saved profile **Production** currently affects two things:
- Recursive folder deletes require typing the folder's exact name to confirm.
- Deploying to that target requires typing the host's exact name to confirm, on top of the normal deployment plan review.

It does *not* (yet) extend to service restarts or other destructive actions — see the [Known Limitations wiki page](../wiki/Known-Limitations.md) for the current scope.

## Where things are stored

| What | Where |
|---|---|
| Saved connection profiles | `~/.linuxdesk/profiles.properties` |
| Connection history / recent items | `~/.linuxdesk/history.properties`, `~/.linuxdesk/recent.properties` |
| Host key store | `~/.linuxdesk/known_hosts` |
| Deploy backup pointers (last 5 per target) | `~/.linuxdesk/deploy-backups.properties` |
| Audit log | `~/.linuxdesk/audit.log` |
| Theme choice | Java `Preferences` (Windows registry) |

None of these ever store a passphrase or password.
