%% Compiled by OTP 29. The other half of the alternating tail loop, plus the
%% callees that return a value or wait for a message.
-module(scheduler_tail_b).

-export([burn/1, twice/1, receive_one/0]).

burn(Deadline) ->
    case erlang:monotonic_time(millisecond) >= Deadline of
        true ->
            ok;
        false ->
            scheduler_tail_a:burn(Deadline)
    end.

twice(N) ->
    N * 2.

receive_one() ->
    receive
        Message ->
            Message
    end.
