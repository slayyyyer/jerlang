#!/usr/bin/env escript
main([ListFile, OutputDir]) ->
    {ok, Bin} = file:read_file(ListFile),
    Paths = [binary_to_list(Path)
             || Path <- binary:split(Bin, <<"\n">>, [global]),
                Path =/= <<>>],
    ok = filelib:ensure_dir(filename:join(OutputDir, "x")),
    {Ok, Failed} = lists:foldl(
        fun(Path, {OkCount, FailedCount}) ->
            Number = OkCount + FailedCount + 1,
            Result = try epp:scan_file(Path, []) of
                         {ok, Forms, _Extra} -> {ok, Forms};
                         {ok, Forms} -> {ok, Forms};
                         {error, Reason} -> {error, Reason}
                     catch
                         Class:Reason -> {error, {Class, Reason}}
                     end,
            ok = file:write_file(
                filename:join(OutputDir, integer_to_list(Number) ++ ".etf"),
                term_to_binary(Result)),
            case Result of
                {ok, _} -> {OkCount + 1, FailedCount};
                {error, _} -> {OkCount, FailedCount + 1}
            end
        end, {0, 0}, Paths),
    io:format("files ~p~n", [length(Paths)]),
    io:format("ok ~p failed ~p~n", [Ok, Failed]),
    case Failed of
        0 -> halt(0);
        _ -> halt(1)
    end.
