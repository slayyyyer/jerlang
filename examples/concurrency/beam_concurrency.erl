%% Run the shared module on BEAM. Kept separate from concurrency_demo.erl so the
%% benchmark workload contains no timing or printing on either runtime.
-module(beam_concurrency).

-export([main/0]).

main() ->
    {SpawnMicros, 1000000} = timer:tc(fun concurrency_demo:spawn_million/0),
    io:format("BEAM spawn 1000000 ms ~.1f~n", [SpawnMicros / 1000]),
    {RingMicros, 10000} = timer:tc(fun() -> concurrency_demo:ring(1000, 10000) end),
    io:format("BEAM ring 1000 10000 ms ~.1f~n", [RingMicros / 1000]),
    halt(0).
