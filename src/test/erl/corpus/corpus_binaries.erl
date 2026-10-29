%% Conformance corpus: binary construction and matching.
%% Compile with: erlc -o src/test/resources/corpus src/test/erl/corpus/corpus_binaries.erl
-module(corpus_binaries).

-export([run/0]).

run() ->
    Built = <<1, 2:16, "hi">>,
    <<1, 0, 2, 104, 105>> = Built,
    5 = byte_size(Built),

    <<1, Rest/binary>> = <<1, 2, 3>>,
    <<2, 3>> = Rest,

    <<X:4, Y:4, _/bits>> = <<16#AB, 16#CD>>,
    16#A = X,
    16#B = Y,

    Bits = <<3:4, 5:4>>,
    <<3:4, 5:4>> = Bits,
    1 = byte_size(Bits),

    [1, 2, 3] = [B || <<B>> <= <<1, 2, 3>>],
    ok.
