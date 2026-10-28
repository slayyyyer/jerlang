package org.jerlang.erts.dist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The magic cookie used to authenticate a distribution connection.
 *
 * The cookie is read from, in order:
 *
 * - a cookie set with setCookie (for example by -setcookie),
 * - the ERLANG_COOKIE environment variable,
 * - $HOME/.erlang.cookie,
 * - $XDG_CONFIG_HOME/erlang/.erlang.cookie (or ~/.config/...).
 */
public final class DistCookie {

    private static volatile String cookie = null;

    private DistCookie() {
    }

    public static void setCookie(String value) {
        cookie = value;
    }

    public static void reset() {
        cookie = null;
    }

    public static String getCookie() throws IOException {
        if (cookie != null) {
            return cookie;
        }

        String environment = System.getenv("ERLANG_COOKIE");
        if (environment != null && !environment.trim().isEmpty()) {
            return environment.trim();
        }

        Path home = Paths.get(System.getProperty("user.home", ".")).resolve(".erlang.cookie");
        if (Files.isRegularFile(home)) {
            return read(home);
        }

        String configHome = System.getenv("XDG_CONFIG_HOME");
        Path config = (configHome == null || configHome.isEmpty())
            ? Paths.get(System.getProperty("user.home", "."), ".config")
            : Paths.get(configHome);
        Path xdg = config.resolve("erlang").resolve(".erlang.cookie");
        if (Files.isRegularFile(xdg)) {
            return read(xdg);
        }

        throw new IOException("No Erlang cookie found: create ~/.erlang.cookie, "
            + "set ERLANG_COOKIE, or start with -setcookie");
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
    }

}
