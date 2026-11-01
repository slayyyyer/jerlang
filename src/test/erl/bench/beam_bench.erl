%% Benchmark workloads shared by BEAM and JErlang.
%%
%% BeamBenchTest compiles this file with erlc, runs main/1 on a real OTP node
%% and then calls the very same exported functions through the JErlang
%% interpreter. Both columns therefore measure the same Erlang source, which is
%% the only way a ratio between them means anything.
%%
%% Each workload is a nullary function so that neither runtime pays an argument
%% marshalling cost the other does not. The batch size lives next to the
%% workload because a cheap op needs more iterations than an expensive one to
%% be measurable.
-module(beam_bench).

-export([main/1]).
-export([lt/0, guard_lt/0, heads/0, bin/0, update/0, nums/0, bignum/0,
         closures/0, reverse100/0, msgpong/0, ets_round/0]).

-record(r, {a = 0, b = 2, c = 3}).

main(_Args) ->
    io:format("BENCHBEAM\t~s\t~s~n",
              [erlang:system_info(otp_release), erlang:system_info(version)]),
    lists:foreach(fun({Name, F, Ops}) -> report(Name, F, Ops) end, workloads()),
    halt(0).

%% The same list, in the same order, exists in BeamBenchTest. A workload that
%% is added on one side only fails the Java test instead of silently comparing
%% different things.
workloads() ->
    [{function_call, fun lt/0, 200000},
     {guard_dispatch, fun guard_lt/0, 200000},
     {list_head, fun heads/0, 200000},
     {binary_construct, fun bin/0, 200000},
     {record_update, fun update/0, 200000},
     {float_binary_segment, fun nums/0, 200000},
     {big_integer, fun bignum/0, 50000},
     {closures_and_higher_order, fun closures/0, 20000},
     {lists_reverse_100, fun reverse100/0, 20000},
     {message_round_trip, fun msgpong/0, 2000},
     {ets_insert_and_lookup, fun ets_round/0, 2000}].

report(Name, F, Ops) ->
    loop(F, 1000),
    Samples = [elapsed(F, Ops) || _ <- lists:seq(1, 5)],
    Median = lists:nth(3, lists:sort(Samples)),
    io:format("BENCH\t~s\t~.1f~n", [atom_to_list(Name), Median / Ops]).

%% timer:tc/1 returns microseconds; convert so the printed number is ns/op, the
%% unit JErlang's own harness reports.
elapsed(F, Ops) ->
    {Micros, _} = timer:tc(fun() -> loop(F, Ops) end),
    Micros * 1000.0.

loop(_F, 0) -> ok;
loop(F, N) -> F(), loop(F, N - 1).

lt() -> 1 < 2.

guard_lt() ->
    case 1 of
        X when X < 2 -> X;
        _ -> other
    end.

heads() ->
    [H | _] = [1, 2, 3],
    H.

bin() ->
    <<7, 255>>.

update() ->
    R = #r{},
    R#r{a = 1}.

nums() ->
    <<(-2)/signed, 1.5/float>>.

bignum() ->
    123456789012345678901234567890 * 987654321098765432109876543210.

closures() ->
    F = fun(X) -> X + 1 end,
    length(lists:reverse([F(X) || X <- [1, 2, 3, 4, 5]])).

reverse100() ->
    length(lists:reverse(lists:seq(1, 100))).

%% Spawn, two messages, exit: the same shape as the cross-boundary round trip
%% in BenchTest, minus the Java caller.
msgpong() ->
    Pid = spawn(fun() ->
        receive
            {ping, From} ->
                From ! pong,
                receive stop -> ok end
        end
    end),
    Pid ! {ping, self()},
    receive pong -> ok after 5000 -> timeout end,
    Pid ! stop,
    ok.

ets_round() ->
    T = ets:new(beam_bench, [set, private]),
    true = ets:insert(T, {k, 1}),
    [{k, 1}] = ets:lookup(T, k),
    true = ets:delete(T, k),
    true = ets:delete(T).
