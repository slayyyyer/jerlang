%% Exercises the binary matching instructions of OTP 29: bs_start_match3,
%% bs_match with its command list, bs_get_position, bs_set_position and
%% bs_get_tail.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/binary_match.erl
-module(binary_match).

-export([one/1, rest/1, ints/1, lit/1, tail/1, bits/1, nested/1, short/1,
         guarded/1]).

%% One byte, then everything else as a binary.
one(<<A, B/binary>>) -> {A, B}.

%% Skip two bytes.
rest(<<_Head:16, Rest/binary>>) -> Rest.

%% Big endian unsigned, little endian signed, and a plain byte.
ints(<<A:16/big-unsigned, B:32/little-signed, C:8>>) -> {A, B, C}.

%% A literal prefix. A non-binary argument and a wrong prefix both fall
%% through to the second clause.
lit(<<1, 2, Rest/binary>>) -> Rest;
lit(_) -> no.

%% Capture a fixed prefix, the rest, and the original binary.
tail(<<A:4/binary, B/binary>> = All) -> {A, B, All}.

%% Bit level matching: three bits, five bits, then the remaining bits.
bits(<<A:3, B:5, Rest/bitstring>>) -> {A, B, Rest};
bits(_) -> no.

%% Capture the whole binary after two bytes, which uses a saved position and
%% bs_get_tail.
nested(<<A:8, B:8, _/binary>> = Bin) -> {A, B, Bin}.

%% The is_binary guard proves the source is matchable, so the compiler emits
%% bs_start_match4 with the no_fail mode instead of bs_start_match3.
guarded(Bin) when is_binary(Bin) ->
    case Bin of
        <<A:8>> -> A;
        <<B:16>> -> B;
        _ -> none
    end.

%% A case whose pattern fails on a short binary, plus a non-binary argument.
short(B) ->
    case B of
        <<_:16, R/binary>> -> {ok, R};
        _ -> short
    end.
