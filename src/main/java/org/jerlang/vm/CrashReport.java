package org.jerlang.vm;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.jerlang.Process;
import org.jerlang.ProcessExit;
import org.jerlang.ProcessSignals;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * The error report Erlang/OTP prints when a process terminates because of
 * an uncaught exception.
 *
 * The report has the form
 *
 * <pre>
 * =ERROR REPORT==== 2026-10-28::17:00:00.000000 ===
 * Error in process &lt;0.33.0&gt; with exit value:
 * {{badmatch,2},[{crash_demo,crash_now,1,[{file,"crash_demo.erl"},{line,19}]}]}
 * </pre>
 *
 * An uncaught `exit` is a normal way to stop a process and prints nothing,
 * and neither does a process that was killed by an exit signal.
 */
public final class CrashReport {

    private static final Atom EXIT = Atom.of("exit");
    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd::HH:mm:ss.SSSSSS");

    private CrashReport() {
    }

    /**
     * Print the report for a process that died from an uncaught exception.
     * Like Erlang/OTP's default logger handler, the report goes to standard
     * output.
     */
    public static void print(Process process) {
        Throwable failure = process.failure();
        if (failure == null || failure instanceof ProcessExit
            || process.pendingExitReason() != null) {
            return;
        }
        if (failure instanceof Error && EXIT.equals(((Error) failure).exceptionClass())) {
            return;
        }
        // The termination reason of an uncaught error or throw already
        // carries its captured stacktrace; only a Java exception still
        // needs the process stack appended.
        Term value = failure instanceof Error
            ? ProcessSignals.reason(failure)
            : Tuple.of(ProcessSignals.reason(failure), process.stacktrace());

        // One print call, so that reports from different schedulers do not
        // interleave line by line.
        StringBuilder report = new StringBuilder();
        report.append("=ERROR REPORT==== ")
            .append(LocalDateTime.now().format(TIMESTAMP))
            .append(" ===\n");
        report.append("Error in process ").append(process.pid())
            .append(" with exit value:\n");
        report.append(value).append('\n');
        System.out.print(report);
        System.out.flush();
    }

}
