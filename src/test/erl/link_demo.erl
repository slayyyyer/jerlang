%% Exercises local links, monitors and exit signals.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/link_demo.erl
-module(link_demo).

-export([linked_crash/0, trapped_crash/0, exit_signal/0, kill/0,
         unlink_test/0, demonitor_test/0, monitor_dead/0, link_dead/0,
         registry/0]).

-export([link_and_wait/1, crash_after_go/0, normal_return/0,
         wait/0, wait_plain/0]).

%% A process linked to a crashing process dies with the same reason, and a
%% monitor on the middle process reports that reason.
linked_crash() ->
    Parent = self(),
    Middle = spawn(link_demo, link_and_wait, [Parent]),
    Ref = monitor(process, Middle),
    receive
        {linked, Crasher} -> Crasher ! go
    after 2000 ->
        error(no_link)
    end,
    receive
        {'DOWN', Ref, process, Middle, boom} -> ok
    after 2000 ->
        error(no_down)
    end.

link_and_wait(Parent) ->
    Crasher = spawn(link_demo, crash_after_go, []),
    link(Crasher),
    Parent ! {linked, Crasher},
    receive never -> ok end.

crash_after_go() ->
    receive go -> exit(boom) end.

%% trap_exit turns the exit signal into a message, including a normal exit.
trapped_crash() ->
    false = process_flag(trap_exit, true),
    Crasher = spawn(link_demo, crash_after_go, []),
    link(Crasher),
    Crasher ! go,
    receive
        {'EXIT', Crasher, boom} -> ok
    after 2000 ->
        error(no_exit_message)
    end,
    Normal = spawn(link_demo, normal_return, []),
    link(Normal),
    receive
        {'EXIT', Normal, normal} -> ok
    after 2000 ->
        error(no_normal_exit)
    end,
    true = process_flag(trap_exit, false),
    ok.

normal_return() ->
    ok.

%% exit/2 terminates a peer that does not trap exits.
exit_signal() ->
    Victim = spawn(link_demo, wait_plain, []),
    Ref = monitor(process, Victim),
    true = exit(Victim, die),
    receive
        {'DOWN', Ref, process, Victim, die} -> ok
    after 2000 ->
        error(no_exit)
    end.

%% kill is uncatchable, even for a process that traps exits.
kill() ->
    Victim = spawn(link_demo, wait, []),
    Ref = monitor(process, Victim),
    true = exit(Victim, kill),
    receive
        {'DOWN', Ref, process, Victim, killed} -> ok
    after 2000 ->
        error(not_killed)
    end.

wait() ->
    process_flag(trap_exit, true),
    receive stop -> ok end.

wait_plain() ->
    receive stop -> ok end.

unlink_test() ->
    false = process_flag(trap_exit, true),
    Crasher = spawn(link_demo, crash_after_go, []),
    link(Crasher),
    true = unlink(Crasher),
    Crasher ! go,
    receive
        {'EXIT', Crasher, _} -> error(still_linked)
    after 1000 ->
        ok
    end.

demonitor_test() ->
    Victim = spawn(link_demo, wait_plain, []),
    Ref = monitor(process, Victim),
    true = demonitor(Ref),
    true = exit(Victim, die),
    receive
        {'DOWN', Ref, _, _, _} -> error(down_after_demonitor)
    after 1000 ->
        ok
    end.

%% Monitoring a process that already terminated reports noproc.
monitor_dead() ->
    Victim = spawn(link_demo, wait_plain, []),
    First = monitor(process, Victim),
    true = exit(Victim, die),
    receive
        {'DOWN', First, process, Victim, die} -> ok
    after 2000 ->
        error(no_down)
    end,
    Second = monitor(process, Victim),
    receive
        {'DOWN', Second, process, Victim, noproc} -> ok
    after 2000 ->
        error(no_noproc)
    end.

%% Linking to a process that is gone is a noproc exit signal.
link_dead() ->
    false = process_flag(trap_exit, true),
    Dead = spawn(link_demo, wait_plain, []),
    Ref = monitor(process, Dead),
    true = exit(Dead, die),
    receive
        {'DOWN', Ref, process, Dead, die} -> ok
    after 2000 ->
        error(no_down)
    end,
    link(Dead),
    receive
        {'EXIT', Dead, noproc} -> ok
    after 2000 ->
        error(no_noproc)
    end.

registry() ->
    undefined = whereis(link_demo_name),
    Pid = spawn(link_demo, wait_plain, []),
    true = register(link_demo_name, Pid),
    Pid = whereis(link_demo_name),
    true = is_process_alive(Pid),
    true = member(registered(), link_demo_name),
    true = unregister(link_demo_name),
    undefined = whereis(link_demo_name),
    true = exit(Pid, die),
    receive after 200 -> ok end,
    false = is_process_alive(Pid),
    ok.

member([Name | _], Name) -> true;
member([_ | Rest], Name) -> member(Rest, Name);
member([], _) -> false.
