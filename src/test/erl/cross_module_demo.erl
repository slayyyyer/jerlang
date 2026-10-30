%% Compiled by OTP 29. Exercises calls that cross a module boundary:
%% a receive that parks below the caller, a fun built here but applied from
%% another module, and a terminating tail call whose frame has Y registers.

-module(cross_module_demo).

-export([run/0, wait_run/0, fun_run/0, tail_run/1, double/1, combine/2]).

run() ->
    {wait_run(), fun_run(), tail_run(tailed)}.

%% The receive lives in cross_module_worker, one call below this module.
%% When the process parks there it loses the frames of both activations, so
%% the worker's wait and this call both have to be resumed.
wait_run() ->
    Parent = self(),
    spawn(fun() -> receive after 20 -> Parent ! {value, 7} end end),
    Result = cross_module_worker:wait(Parent),
    {waited, Result}.

%% A fun compiled here and applied in the other module: its label belongs to
%% this module's code, so the other module can not jump to it.
fun_run() ->
    F = fun(X) -> X * 3 end,
    cross_module_worker:apply_fun(F, 5).

%% The worker ends in a cross-module tail call, with the first argument held
%% in a Y register over another call. This function keeps a Y register of its
%% own live across that call, so a tail call that left its stack words behind
%% would make the value below it read from the wrong slot.
tail_run(Prefix) ->
    Result = cross_module_worker:tail(4),
    {Prefix, Result}.

double(N) ->
    N * 2.

combine(A, B) ->
    A * 1000 + B.
