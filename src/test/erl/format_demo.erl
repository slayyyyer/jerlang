%% Exercises io_lib:format/2, io:format/1,2, unicode and
%% code:ensure_loaded/1 from Erlang code compiled by OTP 29.
-module(format_demo).

-export([terms/0, strings/0, widths/0, numbers/0, misc/0,
         unicode_binary/0, unicode_list/0, unicode_errors/0,
         ensure_loaded/0, io_format/0, printable/0, probe/0, probe2/0]).

%% Functions whose whole body is a tail call, to check that the callee's
%% result reaches the caller.
probe() ->
    unicode:characters_to_binary([104, 105]).

probe2() ->
    lists:reverse([1, 2, 3]).


%% The expected text is compared as a binary, so a failing conversion shows
%% up as a badmatch on the exact string.
text(Format, Args) ->
    unicode:characters_to_binary(io_lib:format(Format, Args)).

terms() ->
    <<"{a,\"b\",[1,2],<<1,2>>,3.0}">> =
        text("~p", [{a, "b", [1, 2], <<1, 2>>, 3.0}]),
    <<"[97,98,99]">> = text("~w", ["abc"]),
    <<"\"abc\"">> = text("~p", ["abc"]),
    <<"[1,2]">> = text("~p", [[1, 2]]),
    <<"{}">> = text("~w", [{}]),
    <<"#{a => 1}">> = text("~p", [#{a => 1}]),
    <<"#{}">> = text("~p", [#{}]),
    %% The printable head of a binary is written as a string, the rest as
    %% integers.
    <<"<<\"ab\",0,255>>">> = text("~p", [<<97, 98, 0, 255>>]),
    <<"<<>>">> = text("~p", [<<>>]),
    <<"[]">> = text("~p", [[]]),
    ok.

strings() ->
    <<"abc">> = text("~s", ["abc"]),
    <<"abc">> = text("~s", [<<"abc">>]),
    <<"A">> = text("~c", [65]),
    <<195, 169>> = text("~tc", [233]),
    ok.

widths() ->
    <<"       abc|">> = text("~10s|", ["abc"]),
    <<"abc       |">> = text("~-10s|", ["abc"]),
    <<"     abc|">> = text("~*s|", [8, "abc"]),
    <<"abc     |">> = text("~*s|", [-8, "abc"]),
    <<"ab">> = text("~.2s", ["abcdef"]),
    <<"0000003.14">> = text("~10.2.0f", [3.14159]),
    ok.

numbers() ->
    <<"3.141593">> = text("~f", [3.14159265]),
    <<"2.000">> = text("~.3f", [2.0]),
    <<"1.23457e+3">> = text("~e", [1234.5678]),
    <<"1.23450e-5">> = text("~g", [0.000012345]),
    <<"255">> = text("~b", [255]),
    <<"ff">> = text("~.16b", [255]),
    <<"zz">> = text("~.36b", [1295]),
    <<"101">> = text("~.2b", [5]),
    <<"        12">> = text("~10.3b", [5]),
    <<"0000000012">> = text("~10.3.0b", [5]),
    ok.

misc() ->
    <<"a\nb~">> = text("~s~nb~~", ["a"]),
    <<"ab">> = text("a~ib", [ignored]),
    badarg = try io_lib:format("~s", []) of
                 _ -> missing_badarg
             catch
                 error:badarg -> badarg
             end,
    badarg = try io_lib:format("~q", [1]) of
                 _ -> missing_badarg
             catch
                 error:badarg -> badarg
             end,
    ok.

unicode_binary() ->
    <<"hi">> = unicode:characters_to_binary([104, 105]),
    <<104, 195, 169, 108, 108, 111>> =
        unicode:characters_to_binary([104, 233, 108, 108, 111]),
    <<"hij">> = unicode:characters_to_binary([104, <<"ij">>]),
    %% A latin1 binary is decoded before it is written as UTF-8.
    <<104, 195, 169>> = unicode:characters_to_binary(<<104, 233>>, latin1),
    %% The codepoint 233 fits latin1, so it is written as one byte.
    <<104, 233>> = unicode:characters_to_binary([104, 233], utf8, latin1),
    ok.

unicode_list() ->
    [104, 233, 108, 108, 111] =
        unicode:characters_to_list(<<104, 195, 169, 108, 108, 111>>),
    [104, 105] = unicode:characters_to_list(<<"hi">>),
    [104, 105] = unicode:characters_to_list(<<104, 105>>, latin1),
    ok.

unicode_errors() ->
    %% 300 is a codepoint but not a latin1 character: what was converted is
    %% a binary and the unconverted tail keeps the invalid character.
    {error, <<104>>, [[300]]} =
        unicode:characters_to_binary([104, 300], unicode, latin1),
    %% 255 is not valid UTF-8 and is reported as the rest of the input.
    {error, [], <<255>>} = unicode:characters_to_list(<<255>>),
    %% A sequence cut short by the end of the binary is incomplete.
    {incomplete, [104], <<195>>} = unicode:characters_to_list(<<104, 195>>),
    ok.

ensure_loaded() ->
    {module, lists} = code:ensure_loaded(lists),
    {error, nofile} = code:ensure_loaded(no_such_module_xyz),
    ok.

io_format() ->
    ok = io:format("hello ~s~n", ["world"]),
    ok = io:format("plain~n"),
    ok = io:format("~p~n", [42]),
    ok.

%% The io_lib predicates and maps:to_list/1 are what the standard library's
%% formatters use to choose between text and a term.
printable() ->
    true = io_lib:printable_unicode_list("abc"),
    true = io_lib:printable_unicode_list([20013]),
    false = io_lib:printable_unicode_list([0]),
    false = io_lib:printable_unicode_list([127]),
    false = io_lib:printable_unicode_list([[97]]),
    true = io_lib:printable_list("abc"),
    true = io_lib:printable_latin1_list("abc"),
    false = io_lib:printable_latin1_list([20013]),
    [{a, 1}] = maps:to_list(#{a => 1}),
    [{a, 1}, {b, 2}] = maps:to_list(#{b => 2, a => 1}),
    ok.
