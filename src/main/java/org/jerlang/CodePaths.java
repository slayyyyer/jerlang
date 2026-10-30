package org.jerlang;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The code path: the directories the runtime searches, in order, for a
 * module's BEAM file.
 *
 * The working directory is the default entry, which is where the loader has
 * always looked. `-pa` and `code:add_patha/1` put a directory in front of the
 * path, `-pz` and `code:add_pathz/1` put it at the end, and `ERL_LIBS` adds
 * the `ebin` directory of every application below the library directories it
 * names.
 *
 * Paths are kept with forward slashes. Erlang accepts them on every platform
 * and it keeps `code:which/1` comparable across operating systems.
 */
public class CodePaths {

    private static final String CURRENT_DIRECTORY = ".";

    private static final List<String> paths = new ArrayList<>();

    // Directories whose modules may not be replaced by a file from another
    // directory. This is the code server's sticky directory facility.
    private static final java.util.Set<String> sticky = new java.util.LinkedHashSet<>();

    static {
        paths.add(CURRENT_DIRECTORY);
        addErlLibs(System.getenv("ERL_LIBS"));
    }

    private CodePaths() {
    }

    /**
     * Put a directory at the front of the code path, if it is not there yet.
     */
    public static synchronized void addPatha(String directory) {
        add(-1, directory);
    }

    /**
     * Put a directory at the end of the code path, if it is not there yet.
     */
    public static synchronized void addPathz(String directory) {
        add(paths.size(), directory);
    }

    /**
     * Put several directories at the front of the code path. Their relative
     * order is kept, so `-pa a b` searches a before b, like `erl` does.
     */
    public static synchronized void addPathsa(List<String> directories) {
        for (int index = directories.size() - 1; index >= 0; index--) {
            addPatha(directories.get(index));
        }
    }

    /**
     * Put several directories at the end of the code path, keeping their
     * relative order.
     */
    public static synchronized void addPathsz(List<String> directories) {
        for (String directory : directories) {
            addPathz(directory);
        }
    }

    /**
     * The directories of the code path, in search order.
     */
    public static synchronized List<String> get() {
        return new ArrayList<>(paths);
    }

    /**
     * Replace one entry of the code path with another directory. The old
     * entry has to be on the path; the new one is inserted at its position so
     * the relative order of the path is kept.
     */
    public static synchronized boolean replace(String from, String to) {
        int index = paths.indexOf(normalizePath(from));
        if (index < 0) {
            return false;
        }
        paths.set(index, normalizePath(to));
        return true;
    }

    /**
     * Remove a directory from the code path. Returns false when it was not
     * part of it, like code:del_path/1.
     */
    public static synchronized boolean delete(String directory) {
        return paths.remove(normalizePath(directory));
    }

    /**
     * The path of the first BEAM file for the module on the code path, or
     * null when no directory has one.
     */
    public static synchronized String find(String module) {
        for (String directory : paths) {
            File file = new File(directory, module + ".beam");
            if (file.isFile()) {
                return normalizePath(file.getPath());
            }
        }
        return null;
    }

    /**
     * The path of the first file with the given name on the code path, or
     * null when no directory has it. This is code:where_is_file/1, which is
     * how the application controller finds a .app resource file.
     */
    public static synchronized String findFile(String fileName) {
        for (String directory : paths) {
            File file = new File(directory, fileName);
            if (file.isFile()) {
                return normalizePath(file.getPath());
            }
        }
        return null;
    }

    /**
     * The library directory of an application. The code path is the source
     * of truth: an entry ending in {@code <app>/ebin} or
     * {@code <app>-<vsn>/ebin} names the application, the same way OTP finds
     * it. When the path has no such entry, {@code <root>/lib} is searched for
     * a matching directory.
     *
     * Returns null when the application is not installed.
     */
    public static synchronized String libDir(String application) {
        for (String directory : paths) {
            String parent = parentOfEbin(directory);
            if (parent == null) {
                continue;
            }
            if (applicationName(parent).equals(application)) {
                return parent;
            }
        }
        File lib = new File(rootDir(), "lib");
        String[] entries = lib.list();
        if (entries == null) {
            return null;
        }
        for (String entry : entries) {
            if (applicationName(entry).equals(application)) {
                return normalizePath(new File(lib, entry).getPath());
            }
        }
        return null;
    }

    /**
     * The private directory of an application, whether or not it exists.
     * Returns null when the application is not installed.
     */
    public static synchronized String privDir(String application) {
        String libDir = libDir(application);
        if (libDir == null) {
            return null;
        }
        return libDir + "/priv";
    }

    /**
     * The installation directory of the runtime.
     *
     * An explicit `jerlang.root` system property or `ERL_ROOT` environment
     * variable wins. Otherwise a code path entry below a `lib` directory
     * names the root as the directory above it, which is how a real OTP
     * installation is recognised. Without either, the working directory is
     * the root.
     */
    public static synchronized String rootDir() {
        String property = System.getProperty("jerlang.root");
        if (property != null && !property.isEmpty()) {
            return normalizePath(property);
        }
        String environment = System.getenv("ERL_ROOT");
        if (environment != null && !environment.isEmpty()) {
            return normalizePath(environment);
        }
        for (String directory : paths) {
            String parent = parentOfEbin(directory);
            if (parent == null) {
                continue;
            }
            int lib = parent.lastIndexOf("/lib/");
            if (lib < 0) {
                if (parent.startsWith("lib/")) {
                    return CURRENT_DIRECTORY;
                }
                continue;
            }
            return parent.substring(0, lib);
        }
        String workingDirectory = new File(CURRENT_DIRECTORY).getAbsolutePath()
            .replace('\\', '/');
        if (workingDirectory.endsWith("/.")) {
            workingDirectory = workingDirectory.substring(0, workingDirectory.length() - 2);
        }
        return workingDirectory;
    }

    /**
     * Add the applications below the directories named by ERL_LIBS, as the
     * runtime system does at startup. A directory that has an `ebin` of its
     * own is an application; otherwise every subdirectory with an `ebin` is.
     */
    public static synchronized void addErlLibs(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        for (String base : value.split(File.pathSeparator)) {
            if (base.isEmpty()) {
                continue;
            }
            File baseDirectory = new File(base);
            if (new File(baseDirectory, "ebin").isDirectory()) {
                addPathz(new File(baseDirectory, "ebin").getPath());
                continue;
            }
            String[] entries = baseDirectory.list();
            if (entries == null) {
                continue;
            }
            List<String> sorted = new ArrayList<>();
            Collections.addAll(sorted, entries);
            Collections.sort(sorted);
            for (String entry : sorted) {
                File ebin = new File(new File(baseDirectory, entry), "ebin");
                if (ebin.isDirectory()) {
                    addPathz(ebin.getPath());
                }
            }
        }
    }

    /**
     * Make a directory sticky, so a module already loaded from it can not be
     * replaced by a file from another directory. Returns false when the
     * argument is not a directory.
     */
    public static synchronized boolean stick(String directory) {
        if (!new File(directory).isDirectory()) {
            return false;
        }
        sticky.add(normalizePath(directory));
        return true;
    }

    /**
     * Remove the sticky mark from a directory. Returns false when the
     * argument is not a directory.
     */
    public static synchronized boolean unstick(String directory) {
        if (!new File(directory).isDirectory()) {
            return false;
        }
        sticky.remove(normalizePath(directory));
        return true;
    }

    public static synchronized boolean isSticky(String directory) {
        return sticky.contains(normalizePath(directory));
    }

    /**
     * Back to the default path. Used by tests.
     */
    public static synchronized void reset() {
        paths.clear();
        sticky.clear();
        paths.add(CURRENT_DIRECTORY);
    }

    private static void add(int index, String directory) {
        String normalized = normalizePath(directory);
        if (paths.contains(normalized)) {
            return;
        }
        paths.add(index < 0 ? 0 : index, normalized);
    }

    /**
     * The directory above an `ebin` entry, or null when the entry is not one.
     */
    private static String parentOfEbin(String directory) {
        if (!directory.endsWith("/ebin")) {
            return null;
        }
        String parent = directory.substring(0, directory.length() - "/ebin".length());
        return parent.isEmpty() ? null : parent;
    }

    /**
     * The application name of an application directory: `lists-6.0.3` is
     * lists, `myapp` is myapp.
     */
    private static String applicationName(String directory) {
        String name = directory.substring(directory.lastIndexOf('/') + 1);
        int dash = name.indexOf('-');
        return dash < 0 ? name : name.substring(0, dash);
    }

    /**
     * The directory that contains a file. An empty string when there is none,
     * which is what the sticky directory check needs for a bare file name.
     */
    public static String parentOf(String path) {
        File parent = new File(path).getParentFile();
        return parent == null ? "" : parent.getPath();
    }

    /**
     * A path as the runtime reports it: forward slashes, no trailing slash.
     */
    public static String normalizePath(String path) {
        String result = path.replace('\\', '/');
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

}
