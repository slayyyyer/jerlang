package org.jerlang.erts.erlang;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.ProcessState;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.type.stack.StackFrame;

/**
 * erlang:process_info/1,2.
 *
 * The items this runtime can answer are implemented; an item it does not know
 * is a badarg, as in OTP. Two of them are approximations: there is no
 * reduction counter and no word count for a JVM object graph, so reductions,
 * heap_size and stack_size report 0. They are present because the crash report
 * asks for them, and a report that fails while reporting a failure is worse
 * than one with a zero in it.
 *
 * process_info(Pid, registered_name) is the odd one: OTP returns the bare
 * empty list for a process without a name, not a {registered_name, []} tuple,
 * and proc_lib depends on it.
 */
public class ErlangProcessInfo {

    private static final Atom REGISTERED_NAME = Atom.of("registered_name");
    private static final Atom DICTIONARY = Atom.of("dictionary");
    private static final Atom MESSAGE_QUEUE_LEN = Atom.of("message_queue_len");
    private static final Atom MESSAGES = Atom.of("messages");
    private static final Atom LINKS = Atom.of("links");
    private static final Atom TRAP_EXIT = Atom.of("trap_exit");
    private static final Atom STATUS = Atom.of("status");
    private static final Atom CURRENT_FUNCTION = Atom.of("current_function");
    private static final Atom REDUCTIONS = Atom.of("reductions");
    private static final Atom HEAP_SIZE = Atom.of("heap_size");
    private static final Atom STACK_SIZE = Atom.of("stack_size");

    private ErlangProcessInfo() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return process_info_1(params.head());
        case 2:
            Term pid = params.head();
            params = params.tail();
            return process_info_2(pid, params.head());
        default:
            throw badarg;
        }
    }

    /**
     * Returns the implemented items as a list of `{Item, Value}` tuples, or
     * `undefined` when the process is not alive.
     *
     * http://www.erlang.org/doc/man/erlang.html#process_info-1
     */
    public static Term process_info_1(Term pid) {
        Process process = alive(pid);
        if (process == null) {
            return Atom.of("undefined");
        }
        Term[] items = { REGISTERED_NAME, DICTIONARY, MESSAGE_QUEUE_LEN, MESSAGES,
                         LINKS, TRAP_EXIT, STATUS, CURRENT_FUNCTION, REDUCTIONS,
                         HEAP_SIZE, STACK_SIZE };
        List info = List.nil;
        for (Term item : items) {
            info = new List(entry(process, item), info);
        }
        return info;
    }

    /**
     * Returns `{Item, Value}`, a list of those tuples when `item` is a list of
     * items, or `undefined` when the process is not alive.
     *
     * http://www.erlang.org/doc/man/erlang.html#process_info-2
     */
    public static Term process_info_2(Term pid, Term item) {
        Process process = alive(pid);
        if (process == null) {
            return Atom.of("undefined");
        }
        if (item.isList()) {
            List items = item.toList();
            List info = List.nil;
            while (items.length() > 0) {
                info = new List(entry(process, items.head()), info);
                items = items.tail();
            }
            return info;
        }
        if (!(item instanceof Atom)) {
            throw badarg;
        }
        Tuple entry = entry(process, item);
        if (REGISTERED_NAME.equals(item) && ((List) entry.element(2)).length() == 0) {
            // OTP answers a nameless process with the empty list here only.
            return List.nil;
        }
        return entry;
    }

    private static Process alive(Term pid) {
        if (!(pid instanceof PID)) {
            throw badarg;
        }
        ProcessOrPort process = ProcessRegistry.resolve((PID) pid);
        if (!(process instanceof Process) || process.state() == ProcessState.EXITING) {
            return null;
        }
        return (Process) process;
    }

    /**
     * One `{Item, Value}` pair. An item that is not implemented is a badarg,
     * which is what OTP raises for an item it does not know.
     */
    private static Tuple entry(Process process, Term item) {
        if (!(item instanceof Atom)) {
            throw badarg;
        }
        return Tuple.of(item, value(process, (Atom) item));
    }

    private static Term value(Process process, Atom item) {
        if (item.equals(REGISTERED_NAME)) {
            Atom name = ProcessRegistry.registeredName(process.pid());
            return name == null ? List.nil : name;
        } else if (item.equals(DICTIONARY)) {
            return process.dictionary().get();
        } else if (item.equals(MESSAGE_QUEUE_LEN)) {
            return Integer.of(process.mailboxSize());
        } else if (item.equals(MESSAGES)) {
            return process.messages();
        } else if (item.equals(LINKS)) {
            return process.links();
        } else if (item.equals(TRAP_EXIT)) {
            return Boolean.of(process.trapExit());
        } else if (item.equals(STATUS)) {
            // A process asking about itself is, by definition, the one that is
            // running; its scheduler state is RUNNABLE while it executes.
            return process == ProcessRegistry.self()
                ? Atom.of("running") : status(process.state());
        } else if (item.equals(CURRENT_FUNCTION)) {
            StackFrame frame = process.currentFrame();
            return frame == null ? Atom.of("undefined")
                : Tuple.of(frame.module(), frame.func(), Integer.of(frame.arity()));
        } else if (item.equals(REDUCTIONS) || item.equals(HEAP_SIZE)
            || item.equals(STACK_SIZE)) {
            return Integer.of(0);
        }
        throw badarg;
    }

    private static Atom status(ProcessState state) {
        switch (state) {
        case RUNNING:
            return Atom.of("running");
        case RUNNABLE:
            return Atom.of("runnable");
        case WAITING:
            return Atom.of("waiting");
        case SUSPENDED:
            return Atom.of("suspended");
        case GARBAGE_COLLECTING:
            return Atom.of("garbage_collecting");
        default:
            return Atom.of("exiting");
        }
    }

}
