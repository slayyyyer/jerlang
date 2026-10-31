%% Compiled by OTP 29. Drives the cross-module tail-call fixture: more
%% CPU-bound processes than schedulers, with a small receive loop measuring
%% how long it waits between timer wakeups.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/scheduler_tail_a.erl \
%%       src/test/erl/scheduler_tail_b.erl src/test/erl/scheduler_tail_demo.erl
-module(scheduler_tail_demo).

-export([fairness/1, result/0, delayed/0]).

fairness(Schedulers) ->
    Deadline = erlang:monotonic_time(millisecond) + 300,
    Count = Schedulers * 2,
    %% The burn call is the fun's last expression, so it is a tail call: the
    %% loop runs in the process entry activation instead of behind a nested
    %% Java call. Each burner parks once first, so the test also covers a
    %% chain that has been resumed from a wait.
    Pids = [spawn(fun() ->
        receive go -> ok end,
        scheduler_tail_a:burn(Deadline)
    end) || _ <- lists:seq(1, Count)],
    _Refs = [erlang:monitor(process, Pid) || Pid <- Pids],
    [Pid ! go || Pid <- Pids],
    Samples = ping(Deadline, erlang:monotonic_time(millisecond), []),
    case wait_down(Count) of
        ok ->
            {ok, p99(Samples), length(Samples)};
        Error ->
            Error
    end.

%% The value comes back through a local continuation after a cross-module
%% tail call, so the explicit continuation stack is exercised.
result() ->
    scheduler_tail_a:result().

%% The tail-called function waits; the continuation in its caller has to be
%% restored when the process wakes up.
delayed() ->
    scheduler_tail_a:delayed().

ping(Deadline, Last, Samples) ->
    case erlang:monotonic_time(millisecond) >= Deadline of
        true ->
            Samples;
        false ->
            erlang:send_after(1, self(), tick),
            receive
                tick ->
                    Now = erlang:monotonic_time(millisecond),
                    ping(Deadline, Now, [Now - Last | Samples])
            end
    end.

wait_down(0) ->
    ok;
wait_down(Count) ->
    receive
        {'DOWN', _Ref, process, _Pid, normal} ->
            wait_down(Count - 1);
        {'DOWN', _Ref, process, _Pid, Reason} ->
            {error, {burner_exited, Reason}}
    after 2000 ->
        {error, burner_timeout}
    end.

p99(Samples) ->
    nth((length(Samples) * 99 + 99) div 100, sort(Samples)).

sort([]) ->
    [];
sort([Pivot | Rest]) ->
    sort([X || X <- Rest, X =< Pivot]) ++ [Pivot] ++ sort([X || X <- Rest, X > Pivot]).

nth(1, [Head | _]) ->
    Head;
nth(N, [_ | Tail]) ->
    nth(N - 1, Tail).
