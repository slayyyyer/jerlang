%% Conformance corpus: try/catch with classes, reasons and a stacktrace.
%% Compile with: erlc -o src/test/resources/corpus src/test/erl/corpus/corpus_try.erl
-module(corpus_try).

-export([run/0]).

run() ->
    {error, boom} = try
        erlang:error(boom)
    catch
        error:Reason1 -> {error, Reason1}
    end,

    {thrown, x} = try
        throw(x)
    catch
        throw:Value -> {thrown, Value}
    end,

    {caught, error, boom, [_ | _]} = try
        erlang:error(boom)
    catch
        error:Reason2:Stack -> {caught, error, Reason2, Stack}
    end,

    ok = try
        ok
    after
        put(after_ran, true)
    end,
    true = get(after_ran),
    ok.
