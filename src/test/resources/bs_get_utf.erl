% Used by unit test.
% Minimal example to generate BEAM opcodes:
% bs_get_utf8, bs_get_utf16, bs_get_utf32

-module(bs_get_utf).
-export([test8/1,test16/1,test32/1,
         test8_unicode/1,test16_unicode/1,test32_unicode/1,
         test8_bad/1,test16_bad/1,test32_bad/1,
         test16_little/1,test32_little/1]).

test8(BS)  -> <<CodePoint/utf8,_/binary>>  = BS, CodePoint.
test16(BS) -> <<CodePoint/utf16,_/binary>> = BS, CodePoint.
test32(BS) -> <<CodePoint/utf32,_/binary>> = BS, CodePoint.

test8_unicode(BS)  -> <<CodePoint/utf8,_/binary>>  = BS, CodePoint.
test16_unicode(BS) -> <<CodePoint/utf16,_/binary>> = BS, CodePoint.
test32_unicode(BS) -> <<CodePoint/utf32,_/binary>> = BS, CodePoint.

test8_bad(BS) ->
    case BS of
        <<_/utf8,_/binary>> -> matched;
        _ -> no_match
    end.
test16_bad(BS) ->
    case BS of
        <<_/utf16,_/binary>> -> matched;
        _ -> no_match
    end.
test32_bad(BS) ->
    case BS of
        <<_/utf32,_/binary>> -> matched;
        _ -> no_match
    end.

test16_little(BS) -> <<CodePoint/utf16-little,_/binary>> = BS, CodePoint.
test32_little(BS) -> <<CodePoint/utf32-little,_/binary>> = BS, CodePoint.

