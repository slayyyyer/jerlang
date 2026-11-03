#!/usr/bin/env escript
%% Reads a list of source paths and writes erl_scan:string/3's answer for each
%% as an external term, one file per source, numbered from one.
%%
%% The output is {ok, Tokens} or {error, Reason} so a source OTP itself cannot
%% scan is reported as such rather than mistaken for a Java scanner defect.
%% Encoding is UTF-8 because the corpus contains quoted atoms and strings that
%% are not ASCII, and erl_scan works on characters, not bytes.

main([ListFile, OutDir]) ->
    {ok, Binary} = file:read_file(ListFile),
    Lines = [Line || Line <- binary:split(Binary, <<"\n">>, [global]), Line =/= <<>>],
    Results = scan(Lines, OutDir, 1, []),
    Failures = [Result || Result = {_, {error, _}} <- Results],
    io:format("files ~p~n", [length(Results)]),
    [io:format("FAILURE ~ts: ~p~n", [Path, Reason])
     || {Path, {error, Reason}} <- Results],
    io:format("ok ~p failed ~p~n",
              [length(Results) - length(Failures), length(Failures)]),
    halt(case Failures of [] -> 0; _ -> 1 end).

scan([], _OutDir, _Number, Results) ->
    lists:reverse(Results);
scan([Line | Lines], OutDir, Number, Results) ->
    Path = unicode:characters_to_list(Line),
    Result = scan_file(Path),
    ok = file:write_file(filename:join(OutDir, integer_to_list(Number) ++ ".etf"),
                         term_to_binary(Result)),
    scan(Lines, OutDir, Number + 1, [{Path, Result} | Results]).

scan_file(Path) ->
    case file:read_file(Path) of
        {ok, Binary} ->
            case unicode:characters_to_list(Binary, utf8) of
                Source when is_list(Source) ->
                    case erl_scan:string(Source, 1, []) of
                        {ok, Tokens, _End} -> {ok, Tokens};
                        {error, Reason, _End} -> {error, Reason}
                    end;
                Error ->
                    {error, {encoding, Error}}
            end;
        {error, Reason} ->
            {error, {read, Reason}}
    end.
