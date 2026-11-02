%% A process with state, to show what a test can assert about concurrency: the
%% counter lives in the loop, not in a Java field, so the same test proves that
%% spawn, send and receive work from a JUnit run.
-module(worker).

-export([start/0, incr/1, value/1, demo/0]).

start() ->
    spawn(fun() -> loop(0) end).

incr(Pid) ->
    Pid ! {incr, self()},
    receive
        {worker, Count} -> Count
    end.

value(Pid) ->
    Pid ! {value, self()},
    receive
        {worker, Count} -> Count
    end.

%% One call that starts a process, changes it twice and reads it back, so the
%% Java side needs no PID to hold on to.
demo() ->
    Pid = start(),
    incr(Pid),
    incr(Pid),
    value(Pid).

loop(Count) ->
    receive
        {incr, From} ->
            From ! {worker, Count + 1},
            loop(Count + 1);
        {value, From} ->
            From ! {worker, Count},
            loop(Count)
    end.
