% Used by unit tests.
% Minimal example to produce the BEAM opcodes:
% bs_skip_utf8, bs_skip_utf16, bs_skip_utf32

-module(bs_skip_utf).
-export([test8/1,test16/1,test32/1,
         test8_unicode/1,test16_unicode/1,test32_unicode/1,
         test8_bad/1,test16_bad/1,test32_bad/1]).

test8(BS)  -> <<_/utf8,R/binary>>  = BS, R.
test16(BS) -> <<_/utf16,R/binary>> = BS, R.
test32(BS) -> <<_/utf32,R/binary>> = BS, R.

test8_unicode(BS)  -> <<_/utf8,R/binary>>  = BS, R.
test16_unicode(BS) -> <<_/utf16,R/binary>> = BS, R.
test32_unicode(BS) -> <<_/utf32,R/binary>> = BS, R.

test8_bad(BS) ->
    case BS of
        <<_/utf8,R/binary>> -> R;
        _ -> no_match
    end.
test16_bad(BS) ->
    case BS of
        <<_/utf16,R/binary>> -> R;
        _ -> no_match
    end.
test32_bad(BS) ->
    case BS of
        <<_/utf32,R/binary>> -> R;
        _ -> no_match
    end.

