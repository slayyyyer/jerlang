%% The tuple is built into a Y register: a call clobbers the argument
%% registers, so every value that is still live afterwards has to live in
%% the frame. A put_tuple2 that ignores a Y destination leaves the variable
%% holding nil instead of the tuple.
-module(put_tuple2_y).

-export([run/1]).

run(N) ->
    A = N + 1,
    B = N + 2,
    C = N + 3,
    D = N + 4,
    E = N + 5,
    F = N + 6,
    T = {tuple, F},
    S = bump(N),
    {T, S, A, B, C, D, E, F}.

bump(X) ->
    X * 10.
