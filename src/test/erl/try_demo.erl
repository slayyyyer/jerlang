%% Exercises try/catch and raise from real Erlang source.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/try_demo.erl
-module(try_demo).

-export([run/0, caught/1, caught_by_class/1, reraise/1, reraise_caught/1,
         rethrow/1, rethrow_caught/1]).

%% Bind the exception class, the reason and the stacktrace.
%% The stacktrace variable makes the compiler emit build_stacktrace.
caught(Reason) ->
    try
        erlang:error(Reason)
    catch
        Class:Caught:Stack ->
            {Class, Caught, Stack}
    end.

%% Dispatch on the exception class and on the reason.
caught_by_class(Reason) ->
    try
        erlang:error(Reason)
    catch
        error:badarg -> badarg_clause;
        error:Other -> {error, Other}
    end.

%% Raise the caught exception again with its original stacktrace.
%% OTP 24 and later compile erlang:raise/3 into raw_raise.
reraise(Reason) ->
    try
        erlang:error(Reason)
    catch
        Class:Caught:Stack ->
            erlang:raise(Class, Caught, Stack)
    end.

%% Catch what reraise/1 raised, so the re-raised exception stays inside
%% the virtual machine.
reraise_caught(Reason) ->
    try
        reraise(Reason)
    catch
        Class:Caught:Stack ->
            {reraised, Class, Caught, Stack}
    end.

%% The catch clause only matches throws, so the error must be re-raised
%% unchanged. The compiler emits the internal `raise` bif for this.
rethrow(Reason) ->
    try
        erlang:error(Reason)
    catch
        throw:_ -> swallowed
    end.

rethrow_caught(Reason) ->
    try
        rethrow(Reason)
    catch
        Class:Caught:_ ->
            {reraised_by_bif, Class, Caught}
    end.

run() ->
    {caught(boom),
     caught_by_class(badarith),
     reraise_caught(again),
     rethrow_caught(unmatched)}.
