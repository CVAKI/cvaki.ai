package com.braingods.cva.termux;

import android.content.Context;
import com.braingods.cva.termux.BootstrapPostInstall;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * TermuxInstaller
 * ─────────────────────────────────────────────────────────────────────────────
 * Downloads and installs the official Termux bootstrap ZIP into
 *   /data/data/com.braingods.cva/files/usr   ($PREFIX)
 *
 * This is a direct port/adaptation of Termux's own TermuxInstaller.java.
 * After install, your terminal has full bash, busybox, python3, vim, git,
 * curl, wget, nano, tmux, openssh, and all the other Termux packages — the
 * exact same binaries that run in the real Termux app.
 *
 * HOW IT WORKS:
 *   1. Detect device ABI (arm64/arm/x86_64)
 *   2. Download bootstrap-<abi>.zip from Termux's GitHub releases
 *   3. Extract into a staging directory first (atomic install)
 *   4. Process SYMLINKS.txt to create all necessary symlinks
 *   5. chmod 0700 on all bin/ and libexec/ executables
 *   6. Atomically rename staging → $PREFIX
 *   7. Write ~/.bashrc and ~/.tmux.conf
 *
 * USAGE:
 *   TermuxInstaller installer = new TermuxInstaller(context);
 *   installer.install(new TermuxInstaller.InstallCallback() {
 *       public void onProgress(String message) { ... }
 *       public void onSuccess() { // terminal is ready! }
 *       public void onFailure(String error) { ... }
 *   });
 *
 * Must be called OFF the main thread (e.g., from an Executor).
 */
public class TermuxInstaller {

    private static final String TAG = "TermuxInstaller";

    public interface InstallCallback {
        void onProgress(String message);
        void onSuccess();
        void onFailure(String error);
    }

    private final Context ctx;

    public TermuxInstaller(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true if the bootstrap has already been installed.
     * Checks for bash in $PREFIX/bin — if it's there, we're good.
     */
    public boolean isInstalled() {
        return TermuxShellUtils.isCvaBootstrapInstalled();
    }

    /**
     * Install the Termux bootstrap.  Calls back on whatever thread this runs on.
     * Wrap this in an Executor or background thread — NEVER call on main thread.
     */
    public void install(InstallCallback cb) {
        try {
            installInternal(cb);
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
            cb.onFailure(e.getMessage());
        }
    }

    /**
     * Force reinstall — deletes $PREFIX first, then re-downloads.
     * Use when installation is corrupted.
     */
    public void reinstall(InstallCallback cb) {
        cb.onProgress("Removing existing installation…");
        TermuxShellUtils.deletePrefix();
        install(cb);
    }

    // ── Install internals ─────────────────────────────────────────────────────

    private void installInternal(InstallCallback cb) throws Exception {
        // Step 1: ensure directories exist
        cb.onProgress("Creating directory structure…");
        TermuxShellUtils.ensureDirectories();

        // Step 2: download bootstrap ZIP
        String arch = TermuxShellUtils.getArch();
        cb.onProgress("Device ABI: " + arch);

        File zipFile = new File(ctx.getCacheDir(), "bootstrap-" + arch + ".zip");

        // Try GitHub primary, then CDN mirror
        String[] urls = {
                TermuxShellUtils.getBootstrapUrl(),
                TermuxShellUtils.getBootstrapCdnUrl()
        };

        boolean downloaded = false;
        for (String url : urls) {
            cb.onProgress("Downloading bootstrap from:\n  " + url);
            try {
                downloadWithProgress(url, zipFile, cb);
                if (zipFile.exists() && zipFile.length() > TermuxConstants.BOOTSTRAP_MIN_SIZE) {
                    cb.onProgress("Download complete ✓  (" + fmt(zipFile.length()) + ")");
                    downloaded = true;
                    break;
                } else {
                    cb.onProgress("File too small (" + zipFile.length() + " B) — trying mirror…");
                }
            } catch (Exception e) {
                cb.onProgress("Source failed: " + e.getMessage() + " — trying mirror…");
            }
        }

        if (!downloaded) {
            throw new Exception("All download sources failed — check internet connection");
        }

        // Step 3: extract to staging directory
        File stagingDir = new File(TermuxConstants.STAGING_PREFIX_PATH);
        if (stagingDir.exists()) TermuxShellUtils.deleteStagingPrefix();
        stagingDir.mkdirs();

        cb.onProgress("Extracting bootstrap to staging…");
        extractBootstrap(zipFile, stagingDir, cb);

        // Step 4: delete the ZIP (save space)
        zipFile.delete();

        // Step 5: process SYMLINKS.txt
        cb.onProgress("Processing symlinks…");
        processSymlinks(stagingDir, cb);

        // Step 6: set execute permissions
        cb.onProgress("Setting execute permissions…");
        setExecutePermissions(stagingDir, cb);

        // Step 6b: patch shebang lines that reference com.termux package paths
        cb.onProgress("Patching shebang lines…");
        patchShebangs(stagingDir, cb);

        // Step 7: atomically rename staging → PREFIX
        // FIX: File.delete() only removes EMPTY directories. The old code silently
        // failed to delete PREFIX (which already had bin/, etc/ from ensureDirectories()),
        // causing renameTo() to also fail, then falling back to copyDirectory() which
        // does NOT preserve symlinks — so bash (a symlink) became a broken regular file.
        //
        // Correct fix: recursively delete the old PREFIX first, then rename staging.
        // If rename still fails (cross-filesystem), use symlink-aware copy.
        File prefixDir = new File(TermuxConstants.PREFIX_PATH);
        if (prefixDir.exists()) {
            cb.onProgress("Removing old PREFIX before install…");
            deleteRecursive(prefixDir);
        }
        cb.onProgress("Installing to PREFIX: " + TermuxConstants.PREFIX_PATH);

        if (!stagingDir.renameTo(prefixDir)) {
            // renameTo can fail if src and dst are on different mount points.
            // Use symlink-aware copy so that bash and other symlinks are preserved.
            cb.onProgress("Rename failed — using symlink-aware copy (may take a moment)…");
            copyDirectoryWithSymlinks(stagingDir, prefixDir);
            deleteRecursive(stagingDir);
        }

        // Step 8: ensure HOME exists
        new File(TermuxConstants.HOME_PATH).mkdirs();
        new File(TermuxConstants.TMP_PATH).mkdirs();

        // Step 9: write dotfiles
        cb.onProgress("Writing .bashrc and .tmux.conf…");
        writeDotFiles();

        cb.onProgress("Bootstrap installation complete ✓");

        // Step 10: post-install fixup — writes apt.conf, dpkg database,
        // pkg wrapper script, and terminfo config so that:
        //   • apt update / apt install / pkg install work inside CVA
        //   • nano, vim, tmux (ncurses) can find the terminfo database
        cb.onProgress("Running post-install configuration…");
        BootstrapPostInstall.run(ctx, msg -> cb.onProgress(msg));

        cb.onSuccess();
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private void downloadWithProgress(String urlStr, File dest, InstallCallback cb)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "CVA-Terminal/1.0");
        conn.connect();

        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);

        long total = conn.getContentLengthLong();
        long done  = 0;
        int  lastPct = -1;

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32_768]; int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int)(done * 100 / total);
                    if (pct != lastPct && pct % 5 == 0) {
                        cb.onProgress("  " + pct + "%  (" + fmt(done) + " / " + fmt(total) + ")");
                        lastPct = pct;
                    }
                }
            }
        }
    }

    // ── ZIP extraction ────────────────────────────────────────────────────────

    private void extractBootstrap(File zipFile, File destDir, InstallCallback cb)
            throws Exception {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    new File(destDir, entry.getName()).mkdirs();
                    zis.closeEntry();
                    continue;
                }

                // SYMLINKS.txt is processed separately
                if (entry.getName().equals("SYMLINKS.txt")) {
                    // Save it as a temp file in dest root
                    File symlinkFile = new File(destDir, "SYMLINKS.txt");
                    writeStreamToFile(zis, symlinkFile);
                    zis.closeEntry();
                    continue;
                }

                File outFile = new File(destDir, entry.getName());
                outFile.getParentFile().mkdirs();
                writeStreamToFile(zis, outFile);

                zis.closeEntry();
                count++;
                if (count % 200 == 0) {
                    cb.onProgress("  Extracted " + count + " files…");
                }
            }
        }
        cb.onProgress("  Extracted " + count + " files ✓");
    }

    private void writeStreamToFile(InputStream in, File dest) throws IOException {
        try (FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    // ── Symlink processing ────────────────────────────────────────────────────

    /**
     * Processes SYMLINKS.txt from the bootstrap ZIP.
     *
     * Format: one symlink per line:  <target>←<source>
     * Example: ../lib/libz.so.1←usr/lib/libz.so
     *
     * This is the exact same format Termux's own TermuxInstaller uses.
     */
    private void processSymlinks(File stagingDir, InstallCallback cb) throws Exception {
        File symlinksTxt = new File(stagingDir, "SYMLINKS.txt");
        if (!symlinksTxt.exists()) {
            cb.onProgress("  SYMLINKS.txt not found — skipping symlink step");
            return;
        }

        int created = 0, failed = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(symlinksTxt)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Format: <target>←<source>  (UTF-8 left arrow U+2190)
                int arrowIdx = line.indexOf("←");
                if (arrowIdx < 0) {
                    // Fallback: try ASCII pipe | separator (some bootstrap versions)
                    arrowIdx = line.indexOf("|");
                    if (arrowIdx < 0) continue;
                }

                String target = line.substring(0, arrowIdx);
                String source = line.substring(arrowIdx + (line.contains("←") ? 3 : 1));

                File symlinkFile = new File(stagingDir, source);
                symlinkFile.getParentFile().mkdirs();

                if (symlinkFile.exists()) symlinkFile.delete();

                try {
                    Os.symlink(target, symlinkFile.getAbsolutePath());
                    created++;
                } catch (Exception e) {
                    Log.w(TAG, "symlink failed: " + source + " → " + target + ": " + e.getMessage());
                    failed++;
                }
            }
        }

        symlinksTxt.delete(); // clean up
        cb.onProgress("  Symlinks created: " + created + "  failed: " + failed);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    /**
     * Recursively chmod 0755 on files in bin/, sbin/, libexec/, and lib/
     * directories — matching what Termux does on first install.
     */
    private void setExecutePermissions(File stagingDir, InstallCallback cb) {
        String[] execDirs = { "bin", "sbin", "libexec",
                "lib/apt/methods", "lib/apt/solvers",
                "lib/dpkg/methods" };

        int count = 0;
        for (String dir : execDirs) {
            File d = new File(stagingDir, dir);
            if (!d.isDirectory()) continue;
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile()) {
                    try {
                        Os.chmod(f.getAbsolutePath(), 0755);
                        count++;
                    } catch (Exception e) {
                        // try Runtime fallback
                        try {
                            Runtime.getRuntime().exec(
                                    new String[]{"chmod", "755", f.getAbsolutePath()}).waitFor();
                            count++;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        cb.onProgress("  chmod 0755 on " + count + " executables ✓");
    }

    // ── Dotfiles ──────────────────────────────────────────────────────────────

    private void writeDotFiles() {
        File home = new File(TermuxConstants.HOME_PATH);
        home.mkdirs();

        String prefix = TermuxConstants.PREFIX_PATH;
        String binPath = prefix + "/bin";

        // ~/.bashrc
        String bashrc =
                "# CVA Terminal .bashrc — auto-generated by TermuxInstaller\n" +
                        "case $- in *i*) ;; *) return;; esac\n\n" +

                        "# ── History ───────────────────────────────────────────────\n" +
                        "HISTCONTROL=ignoreboth\nHISTSIZE=5000\nHISTFILE=$HOME/.bash_history\n" +
                        "shopt -s histappend checkwinsize 2>/dev/null\n\n" +

                        "# ── Environment ───────────────────────────────────────────\n" +
                        "export PREFIX=\"" + prefix + "\"\n" +
                        "export HOME=\"" + TermuxConstants.HOME_PATH + "\"\n" +
                        "export TMPDIR=\"" + prefix + "/tmp\"\n" +
                        "export PATH=\"" + binPath + ":/system/bin:/system/xbin\"\n" +
                        "export LD_LIBRARY_PATH=\"" + prefix + "/lib\"\n" +
                        "export TERM=xterm-256color\n" +
                        "export COLORTERM=truecolor\n" +
                        "export LANG=en_US.UTF-8\n\n" +

                        "# ── Aliases ────────────────────────────────────────────────\n" +
                        "alias ll='ls -alF --color=auto'\n" +
                        "alias la='ls -A'\n" +
                        "alias l='ls -CF'\n" +
                        "alias cls='clear'\n" +
                        "alias ..='cd ..'\n" +
                        "alias grep='grep --color=auto'\n\n" +

                        "# ── CVA PS1 ────────────────────────────────────────────────\n" +
                        "PS1='\\[\\e[1;94m\\]┌─×××××[\\[\\e[1;97m\\]\\T\\[\\e[1;94m\\]]" +
                        "\\[\\e[1;95m\\]§§§§§§\\[\\e[1;94m\\][\\[\\e[1;92m\\]" +
                        "{CVA✓}\\[\\e[1;94m\\]]\\[\\e[0;94m\\]:::::::[\\[\\e[1;96m\\]\\#" +
                        "\\[\\e[1;92m\\]-wings\\[\\e[1;94m\\]]\\n\\[\\e[1;94m\\]│\\n" +
                        "\\[\\e[0;94m\\]└==[\\[\\e[0;95m\\]\\W\\[\\e[0;94m\\]]" +
                        "\\[\\e[1;93m\\]-species\\[\\e[1;91m\\]§§[{\\[\\e[1;92m\\]✓\\[\\e[1;91m\\]}]\\n" +
                        "\\[\\e[1;94m\\]│\\n\\[\\e[0;94m\\]└==[\\[\\e[1;93m\\]≈≈≈≈≈" +
                        "\\[\\e[0;94m\\]]™[✓]=\\[\\e[1;91m\\]►\\[\\e[0m\\] '\\n";

        writeFile(new File(home, ".bashrc"), bashrc);

        // ~/.bash_profile
        String bashProfile = "# CVA .bash_profile\n[ -f ~/.bashrc ] && . ~/.bashrc\n";
        writeFile(new File(home, ".bash_profile"), bashProfile);

        // ~/.tmux.conf
        String tmuxConf =
                "# CVA .tmux.conf\n" +
                        "set -g default-terminal \"screen-256color\"\n" +
                        "set -ga terminal-overrides \",xterm-256color:Tc\"\n" +
                        "set -g history-limit 50000\n" +
                        "set -g base-index 1\n" +
                        "set -g mouse on\n" +
                        "setw -g mode-keys vi\n" +
                        "unbind C-b\nset -g prefix C-a\nbind C-a send-prefix\n" +
                        "bind | split-window -h -c \"#{pane_current_path}\"\n" +
                        "bind - split-window -v -c \"#{pane_current_path}\"\n" +
                        "bind h select-pane -L\nbind j select-pane -D\n" +
                        "bind k select-pane -U\nbind l select-pane -R\n" +
                        "set -g status-style 'bg=#020805 fg=#00FF41'\n" +
                        "set -g pane-active-border-style 'fg=#00FF41'\n" +
                        "run '~/.tmux/plugins/tpm/tpm'\n";

        writeFile(new File(home, ".tmux.conf"), tmuxConf);
    }

    private void writeFile(File f, String content) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "writeFile " + f.getName() + ": " + e.getMessage());
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * FIX: Symlink-aware directory copy.
     *
     * Java's File.isDirectory() follows symlinks, and FileInputStream follows
     * symlinks too — meaning the old copyDirectory() would silently convert every
     * symlink into a real file copy, losing the symlink relationship.
     *
     * In the Termux bootstrap, bash is a symlink to busybox (and many other
     * binaries are symlinks too). Converting them to regular file copies means
     * they lack execute permission AND the shared-binary optimisation breaks.
     *
     * We use Os.readlink() to detect symlinks and Os.symlink() to recreate them.
     */
    private void copyDirectoryWithSymlinks(File src, File dst) throws IOException {
        dst.mkdirs();
        // Use listFiles(false) equivalent — we need raw names, not resolved paths
        String[] names = src.list();
        if (names == null) return;
        for (String name : names) {
            File srcChild  = new File(src, name);
            File destChild = new File(dst, name);
            try {
                // Check if this entry is a symlink (not a real directory/file)
                String linkTarget = null;
                try { linkTarget = Os.readlink(srcChild.getAbsolutePath()); }
                catch (Exception ignored) {} // not a symlink

                if (linkTarget != null) {
                    // Recreate symlink at destination
                    if (destChild.exists()) destChild.delete();
                    try { Os.symlink(linkTarget, destChild.getAbsolutePath()); }
                    catch (Exception e) {
                        Log.w(TAG, "symlink copy failed: " + name + " → " + linkTarget + ": " + e.getMessage());
                    }
                } else if (srcChild.isDirectory()) {
                    copyDirectoryWithSymlinks(srcChild, destChild);
                } else {
                    copyFile(srcChild, destChild);
                }
            } catch (Exception e) {
                Log.w(TAG, "copyDirectoryWithSymlinks: " + name + ": " + e.getMessage());
            }
        }
    }

    private void copyDirectory(File src, File dst) throws IOException {
        dst.mkdirs();
        File[] children = src.listFiles();
        if (children == null) return;
        for (File child : children) {
            File destChild = new File(dst, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, destChild);
            } else {
                copyFile(child, destChild);
            }
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /**
     * Rewrite ALL com.termux path references in bin/ scripts.
     * Previously only patched the shebang (#!) line — now replaces every occurrence
     * in the entire file body. Required to fix wrapper scripts like pkg that call
     * /data/data/com.termux/... binaries internally (not just in the shebang).
     *
     * The Termux bootstrap ships wrapper scripts (am, pm, input, cmd, etc.) with:
     *   #!/data/data/com.termux/files/usr/bin/sh
     * When CVA's process tries to exec that interpreter, SELinux denies it because
     * it belongs to a different app's data directory.
     *
     * We rewrite every such shebang to point at CVA's own prefix instead.
     */
    private void patchShebangs(File stagingDir, InstallCallback cb) {
        final String TERMUX_DATA = "/data/data/com.termux/";
        final String CVA_DATA    = "/data/data/" + TermuxConstants.CVA_PACKAGE + "/";

        String[] dirsToScan = { "bin", "libexec", "lib/apt/methods", "lib/apt/solvers" };
        int patched = 0;

        for (String dirName : dirsToScan) {
            File dir = new File(stagingDir, dirName);
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;

            for (File f : files) {
                if (!f.isFile() || f.length() < 4 || f.length() > 524_288) continue; // skip >512KB
                try {
                    // Peek at first 2 bytes — skip ELF binaries and non-scripts immediately
                    byte[] magic = new byte[2];
                    try (FileInputStream fis = new FileInputStream(f)) {
                        if (fis.read(magic) < 2) continue;
                    }
                    if (magic[0] != '#' || magic[1] != '!') continue;

                    // Read full file
                    byte[] data = readAllBytes(f);
                    String content = new String(data, "UTF-8");
                    if (!content.contains(TERMUX_DATA)) continue;

                    // FIX: patch ALL occurrences of com.termux in the file, not just the shebang.
                    //
                    // The Termux bootstrap's `pkg` wrapper script (and others like
                    // termux-setup-package-manager, termux-change-repo, etc.) have
                    // hardcoded /data/data/com.termux/... paths in their BODY, not
                    // just the shebang line. The old code only patched line 1, so
                    // line 11:  /data/data/com.termux/files/usr/bin/termux-setup-package-manager
                    // still ran → "Permission denied" because SELinux blocks cross-app data access.
                    //
                    // We now replace every occurrence in the whole file.
                    // The size guard (>512KB) already excludes large binaries, so all
                    // remaining files are small shell scripts where this is safe.
                    String patchedContent = content.replace(TERMUX_DATA, CVA_DATA);

                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(patchedContent.getBytes("UTF-8"));
                    }
                    patched++;
                    Log.v(TAG, "patched script: " + f.getName());
                } catch (Exception e) {
                    Log.w(TAG, "patchShebangs skip " + f.getName() + ": " + e.getMessage());
                }
            }
        }
        cb.onProgress("  Patched " + patched + " scripts (all com.termux refs) ✓");
    }

    private byte[] readAllBytes(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int total = 0, n;
            while (total < buf.length &&
                    (n = fis.read(buf, total, buf.length - total)) != -1) {
                total += n;
            }
        }
        return buf;
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
    }
}