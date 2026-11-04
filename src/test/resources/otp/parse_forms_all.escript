#!/usr/bin/env escript
main([ListFile, OutputDir]) ->
    {ok, Bin} = file:read_file(ListFile),
    Paths = [binary_to_list(Path)
             || Path <- binary:split(Bin, <<"\n">>, [global]),
                Path =/= <<>>],
    ok = filelib:ensure_dir(filename:join(OutputDir, "x")),
    lists:foldl(
        fun(Path, Number) ->
            Dir = filename:dirname(Path),
            App = filename:dirname(Dir),
            Inc = filename:join(App, "include"),
            Result = try
                         case epp:scan_file(Path, [{includes, [Dir, Inc]}]) of
                             {ok, TokenForms, _Extra} -> {ok, parse(TokenForms)};
                             {ok, TokenForms} -> {ok, parse(TokenForms)};
                             {error, Reason} -> {error, Reason}
                         end
                     catch
                         Class:CatchReason -> {error, {Class, CatchReason}}
                     end,
            ok = file:write_file(
                filename:join(OutputDir, integer_to_list(Number) ++ ".etf"),
                term_to_binary(Result)),
            Number + 1
        end, 1, Paths),
    io:format("files ~p~n", [length(Paths)]),
    halt(0).

parse([]) ->
    [];
parse([{eof, _} = Eof | Rest]) ->
    [Eof | parse(Rest)];
parse([Tokens | Rest]) ->
    {ok, Form} = erl_parse:parse_form(Tokens),
    [Form | parse(Rest)].
