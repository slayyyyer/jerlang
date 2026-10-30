package org.jerlang.stdlib;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * A small stand-in for OTP's logger.
 *
 * The real logger needs the kernel application: its tables are created by the
 * application controller and its configuration comes from the application
 * environment. A runtime that does not boot applications can not run it, but
 * OTP's own library modules log through it. The ?LOG_ERROR macro of
 * logger.hrl, which the supervisor and gen_server use, expands to
 * `logger:allow/2` followed by `logger:log/3`; without a working logger a
 * child report raises a second exception and takes the supervisor down.
 *
 * So this module answers the calls and prints reports in the shape of the
 * default handler. It is deliberately a terminal handler: filters, handlers
 * and formatters are accepted and ignored.
 *
 * http://www.erlang.org/doc/man/logger.html
 */
public class Logger {

    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd::HH:mm:ss.SSSSSS");

    private static final Atom OK = Atom.of("ok");
    private static final Atom MSG = Atom.of("msg");

    private static final java.util.List<String> LEVELS = java.util.Arrays.asList(
        "debug", "info", "notice", "warning", "error", "critical", "alert", "emergency");

    public static Term log(Term level, Term report) {
        return log(level, report, Maps._new());
    }

    public static Term log(Term level, Term report, Term metadata) {
        print(level, report);
        return OK;
    }

    /**
     * The form the ?LOG_* macros of logger.hrl use:
     * logger:macro_log(Level, Report, Meta, Module).
     */
    public static Term macro_log(Term level, Term report, Term metadata, Term module) {
        return log(level, report, metadata);
    }

    public static Term allow(Term level, Term module) {
        return Boolean.am_true;
    }

    public static Term allow(Term level, Term module, Term metadata) {
        return Boolean.am_true;
    }

    /**
     * The comparison the logger uses to filter events: lt, eq or gt.
     */
    public static Term compare_levels(Term first, Term second) {
        int a = LEVELS.indexOf(first.toString());
        int b = LEVELS.indexOf(second.toString());
        if (a < 0 || b < 0) {
            throw org.jerlang.erts.erlang.Error.badarg;
        }
        return Atom.of(a < b ? "lt" : a == b ? "eq" : "gt");
    }

    private static void print(Term level, Term report) {
        StringBuilder text = new StringBuilder();
        text.append('=').append(level.toString().toUpperCase()).append(" REPORT==== ")
            .append(LocalDateTime.now().format(TIMESTAMP)).append(" ===\n");
        text.append(message(report));
        System.out.println(text);
        System.out.flush();
    }

    /**
     * The message of a report, without the metadata a formatter would use.
     */
    private static String message(Term report) {
        Term value = report;
        if (value instanceof Tuple && value.toTuple().arity() == 2) {
            Term tag = value.toTuple().element(1);
            if (tag.toString().equals("report") || tag.toString().equals("string")) {
                value = value.toTuple().element(2);
            }
        }
        if (value instanceof Map && value.toMap().is_key(MSG)) {
            return message(value.toMap().get(MSG));
        }
        return String.valueOf(value);
    }

}
