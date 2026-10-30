%% Compiled by OTP 29. Called from cross_module_demo, so the receive, the fun
%% application and the tail call all run in an activation below the caller.

-module(cross_module_worker).

-export([wait/1, apply_fun/2, tail/1]).

%% Parks in a receive that belongs to this module, not to the caller's.
wait(Parent) ->
    Parent ! waiting,
    receive
        {value, V} -> {got, V}
    after 5000 ->
        timeout
    end.

%% Applies a fun that was built in another module.
apply_fun(Fun, Arg) ->
    Fun(Arg).

%% Ends in a cross-module tail call. A has to stay live across the call to
%% count/1, so it lives in a Y register and the tail call deallocates it.
tail(N) ->
    A = count(N),
    B = count(A),
    cross_module_demo:combine(A, B).

count(N) ->
    N + 1.
