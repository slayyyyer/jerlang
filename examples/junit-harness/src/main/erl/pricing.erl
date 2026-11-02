%% A pure module: no processes, no OTP, nothing to configure. It is the kind of
%% code that is easiest to move onto the JVM first, and the kind a test suite
%% should be able to call without an Erlang installation anywhere in sight.
%%
%% total/1 is written as a recursion rather than lists:sum/1 on purpose: this
%% module is compiled and called with no OTP installation on the code path, so it
%% may only use the modules the runtime implements itself.
-module(pricing).

-export([quote/2, with_tax/2, total/1, sample_total/0]).

quote(UnitPrice, Quantity) when Quantity >= 0 ->
    UnitPrice * Quantity.

%% Integer arithmetic, rounded down, as prices are.
with_tax(Amount, Rate) ->
    Amount * (100 + Rate) div 100.

total(Amounts) ->
    total(Amounts, 0).

total([], Accumulator) ->
    Accumulator;
total([Amount | Rest], Accumulator) ->
    total(Rest, Accumulator + Amount).

%% Sum of a list built here, so a test can check total/1 without having to
%% convert a Java collection into an Erlang list.
sample_total() ->
    total([100, 250, 400]).
