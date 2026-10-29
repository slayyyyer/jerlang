%% Exercises the error report for processes that die uncaught.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/crash_demo.erl
-module(crash_demo).

-export([crash/0, nested_crash/0, throw_crash/0, exit_crash/0, caught/0,
         crash_now/1, outer/1, inner/1, throw_now/0, exit_now/0,
         down_reason/1, linked_reason/0, wait_then_crash/1]).

%% An uncaught badmatch in a detached process. The value is an argument so
%% that the compiler can not fold the match away.
crash() ->
    Pid = spawn(crash_demo, crash_now, [2]),
    Ref = monitor(process, Pid),
    receive {'DOWN', Ref, process, Pid, _} -> ok after 2000 -> timeout end.

crash_now(Value) ->
    1 = Value.

%% A stack with two frames: inner/0 raises, outer/0 is the caller.
nested_crash() ->
    Pid = spawn(crash_demo, outer, [2]),
    Ref = monitor(process, Pid),
    receive {'DOWN', Ref, process, Pid, _} -> ok after 2000 -> timeout end.

outer(Value) ->
    Result = inner(Value),
    Result + 1.

inner(1) ->
    ok;
inner(_) ->
    erlang:error(inner_boom).

%% An uncaught throw is reported as {nocatch, Value}.
throw_crash() ->
    Pid = spawn(crash_demo, throw_now, []),
    Ref = monitor(process, Pid),
    receive {'DOWN', Ref, process, Pid, _} -> ok after 2000 -> timeout end.

throw_now() ->
    throw(oops).

%% exit/1 is a normal way to stop a process; it must print no report.
exit_crash() ->
    Pid = spawn(crash_demo, exit_now, []),
    Ref = monitor(process, Pid),
    receive {'DOWN', Ref, process, Pid, bye} -> ok after 2000 -> timeout end.

exit_now() ->
    exit(bye).

%% A caught error never reaches the scheduler.
caught() ->
    try
        erlang:error(boom)
    catch
        error:boom -> ok
    end.

%% The reason a monitor receives from a process that dies uncaught.
%% The child is held in a receive until the monitor is installed.
down_reason(error) ->
    down_reason_of(spawn(crash_demo, wait_then_crash, [error]));
down_reason(throw) ->
    down_reason_of(spawn(crash_demo, wait_then_crash, [throw]));
down_reason(exit) ->
    down_reason_of(spawn(crash_demo, wait_then_crash, [exit])).

down_reason_of(Pid) ->
    Ref = monitor(process, Pid),
    Pid ! go,
    receive
        {'DOWN', Ref, process, Pid, Reason} -> Reason
    after 2000 ->
        timeout
    end.

%% A link carries the same reason a monitor would receive.
linked_reason() ->
    process_flag(trap_exit, true),
    Pid = spawn(crash_demo, wait_then_crash, [error]),
    true = link(Pid),
    Pid ! go,
    receive
        {'EXIT', Pid, Reason} -> Reason
    after 2000 ->
        timeout
    end.

wait_then_crash(error) ->
    receive go -> crash_now(2) end;
wait_then_crash(throw) ->
    receive go -> throw_now() end;
wait_then_crash(exit) ->
    receive go -> exit_now() end.
