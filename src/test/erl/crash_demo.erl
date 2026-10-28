%% Exercises the error report for processes that die uncaught.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/crash_demo.erl
-module(crash_demo).

-export([crash/0, nested_crash/0, throw_crash/0, exit_crash/0, caught/0,
         crash_now/1, outer/1, inner/1, throw_now/0, exit_now/0]).

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
