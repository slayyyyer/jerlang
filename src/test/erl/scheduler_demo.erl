%% Compiled by OTP 29. The scheduler fairness fixture: more CPU-bound
%% processes than schedulers run while a small receive loop measures how long
%% it waits between timer wakeups.
%%
%% Compile with: erlc -o src/test/resources src/test/erl/scheduler_demo.erl
-module(scheduler_demo).

-export([fairness/1]).

fairness(Schedulers) ->
    Parent = self(),
    Deadline = erlang:monotonic_time(millisecond) + 300,
    Count = Schedulers * 2,
    %% Every burner parks before it starts, so preemption has to work for a
    %% process that has already been resumed once. Without frame accounting
    %% that survives a resume, these all run to the deadline on one scheduler.
    Pids = [spawn(fun() ->
        receive go -> ok end,
        burn(Deadline),
        Parent ! {done, self()}
    end) || _ <- lists:seq(1, Count)],
    [Pid ! go || Pid <- Pids],
    Samples = ping(Deadline, erlang:monotonic_time(millisecond), []),
    case wait_done(Count) of
        ok ->
            {ok, p99(Samples), length(Samples)};
        Error ->
            Error
    end.

%% A deadline-bounded, purely CPU-bound tail loop. There is no receive and no
%% deliberate yield, so without preemption the first burner holds its
%% scheduler until the deadline.
burn(Deadline) ->
    case erlang:monotonic_time(millisecond) >= Deadline of
        true ->
            ok;
        false ->
            burn(Deadline)
    end.

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

wait_done(0) ->
    ok;
wait_done(Count) ->
    receive
        {done, _Pid} ->
            wait_done(Count - 1)
    after 2000 ->
        {error, burner_timeout}
    end.

p99(Samples) ->
    nth((length(Samples) * 99 + 99) div 100, sort(Samples)).

%% lists:sort/1 is not part of the runtime's lists module yet. The measured
%% list is small, so a plain quicksort keeps the fixture self-contained.
sort([]) ->
    [];
sort([Pivot | Rest]) ->
    sort([X || X <- Rest, X =< Pivot]) ++ [Pivot] ++ sort([X || X <- Rest, X > Pivot]).

nth(1, [Head | _]) ->
    Head;
nth(N, [_ | Tail]) ->
    nth(N - 1, Tail).
