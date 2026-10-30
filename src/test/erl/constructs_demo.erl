%% Constructs that exercise the last OTP 29 opcodes implemented for 0.5:
%% binary construction (bs_create_bin), record updates (update_record and
%% badrecord) and head/tail extraction (get_hd/get_tl).
-module(constructs_demo).

-export([bin/2, nums/2, utf/1, build/2, rec/2, update/2, field/1,
         heads/1, tails/1, heads_improper/1, tails_improper/1,
         bif_head/1, bif_tail/1, bad/1, catch_update/2, catch_field/1,
         catch_heads/1, catch_tails/1, run/0]).

-record(r, {a, b = 2, c}).

bin(N, B) ->
    <<1, N:16, B/binary>>.

nums(X, F) ->
    <<X:32/little-signed, F:64/float, "hi">>.

utf(C) ->
    <<C/utf8, C/utf16, C/utf32>>.

build(A, B) ->
    <<A/binary, B/bitstring>>.

rec(A, C) ->
    #r{a = A, c = C}.

update(R, V) ->
    R#r{a = V}.

field(R) ->
    R#r.b.

heads([H|_]) ->
    H.

tails([_|T]) ->
    T.

%% The same patterns, exercised with a tail that is not a list. gen_server
%% keeps its time-out state as [TRef | TimeoutMsg] and matches it this way.
heads_improper([H|_]) ->
    H.

tails_improper([_|T]) ->
    T.

%% The hd/1 and tl/1 BIFs, called as expressions rather than through a
%% pattern. gen_server's decode_msg guard calls hd/1 on the same improper
%% timer list, so this is the path that used to raise badarg.
bif_head(L) ->
    erlang:hd(L).

bif_tail(L) ->
    erlang:tl(L).

bad(X) ->
    X#r.a.

%% Catch the errors inside Erlang, so the test checks the exception reason
%% without leaving the virtual machine with an uncaught process failure.
catch_update(R, V) ->
    try
        update(R, V)
    catch
        error:Reason -> {error, Reason}
    end.

catch_field(R) ->
    try
        field(R)
    catch
        error:Reason -> {error, Reason}
    end.

catch_heads(X) ->
    try
        heads(X)
    catch
        error:Reason -> {error, Reason}
    end.

catch_tails(X) ->
    try
        tails(X)
    catch
        error:Reason -> {error, Reason}
    end.

run() ->
    <<1, 0, 7, 255>> = bin(7, <<255>>),
    <<254, 255, 255, 255, 63, 248, 0, 0, 0, 0, 0, 0, 104, 105>> = nums(-2, 1.5),
    <<65, 0, 65, 0, 0, 0, 65>> = utf(65),
    #r{a = 1, b = 2, c = 3} = update(rec(0, 3), 1),
    1 = heads([1, 2]),
    [2] = tails([1, 2]),
    ok.
