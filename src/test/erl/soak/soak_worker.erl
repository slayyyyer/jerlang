%% Load for the soak test.
%%
%% A soak needs traffic that does not depend on the Java caller, otherwise the
%% measurement is of the harness. start/2 pairs drivers with echo workers: every
%% driver sends a message, waits for the reply and sends again, until the wall
%% clock passes the deadline it was given. Both sides then exit, so the test can
%% check that the registry drains back to where it started.
%%
%% Written with plain recursion and no ETS on purpose: this is a load generator
%% for the runtime, and it should not fail because a standard library function
%% it could have used is one of the things still missing. Deliveries are counted
%% by the runtime's own metrics counter, which is also the number the release
%% notes point at.
-module(soak_worker).

-export([start/2]).

start(Workers, Millis) when is_integer(Workers), Workers > 0, is_integer(Millis), Millis > 0 ->
    Deadline = erlang:monotonic_time(millisecond) + Millis,
    start_workers(1, Workers, Deadline),
    ok.

start_workers(Index, Workers, _Deadline) when Index > Workers ->
    ok;
start_workers(Index, Workers, Deadline) ->
    Echo = spawn(fun() -> worker() end),
    _Driver = spawn(fun() -> driver(Echo, Deadline) end),
    start_workers(Index + 1, Workers, Deadline).

driver(Echo, Deadline) ->
    Echo ! {ping, self()},
    receive
        pong ->
            case erlang:monotonic_time(millisecond) < Deadline of
                true ->
                    driver(Echo, Deadline);
                false ->
                    Echo ! stop,
                    ok
            end
    after 5000 ->
        %% A lost reply is a failure of the run, not of the driver: keep the
        %% sample series continuous and let the test decide on the numbers.
        Echo ! stop,
        ok
    end.

worker() ->
    receive
        {ping, From} ->
            From ! pong,
            worker();
        stop ->
            ok
    end.
