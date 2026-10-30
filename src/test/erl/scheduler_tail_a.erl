%% Compiled by OTP 29. One half of a CPU-bound loop that alternates
%% modules through a tail call. Every iteration must continue in this
%% interpreter loop instead of nesting another Java call.
-module(scheduler_tail_a).

-export([burn/1, result/0, delayed/0, seed/1, echo/0]).

%% The call to scheduler_tail_b:burn/1 is in tail position.
burn(Deadline) ->
    case erlang:monotonic_time(millisecond) >= Deadline of
        true ->
            ok;
        false ->
            scheduler_tail_b:burn(Deadline)
    end.

%% seed/1 is a local, non-tail call whose callee tail-calls another module.
%% The value has to come back to this continuation.
result() ->
    Base = seed(5),
    Base + 1.

seed(N) ->
    scheduler_tail_b:twice(N).

%% echo/0 is called non-tail and its callee waits for a message. The
%% continuation here must survive the park in the other module.
delayed() ->
    Value = echo(),
    {Value, done}.

echo() ->
    self() ! hello,
    scheduler_tail_b:receive_one().
