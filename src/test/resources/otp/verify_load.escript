#!/usr/bin/env escript
%% Reads a list of "original<TAB>assembled" BEAM paths and asks the real OTP
%% loader to accept each assembled file.
%%
%% The original is the control. A file the writer produced is only a defect if
%% the loader takes the original and rejects the rewrite; a fixture that OTP 29
%% refuses to load at all is not something an assembler can fix, and refusing it
%% for the same reason as the original is the most that can be asked.
%%
%% Modules that ship with OTP are skipped rather than loaded and purged: the
%% corpus contains modules called lists and apply, and replacing those in a
%% running VM is not a test of the writer.

main([ListFile]) ->
    {ok, Binary} = file:read_file(ListFile),
    Lines = [Line || Line <- binary:split(Binary, <<"\n">>, [global]), Line =/= <<>>],
    Pairs = [pair(Line) || Line <- Lines],
    Results = [check(Orig, Asm) || {Orig, Asm} <- Pairs],
    {Loaded, Skipped, Rejected, Failures} = count(Results),
    io:format("files ~p~n", [length(Results)]),
    [io:format("skipped ~s (ships with OTP)~n", [Asm]) || {skipped, Asm} <- Results],
    [io:format("rejected ~s: ~p~n", [Asm, Reason]) || {rejected, Asm, Reason} <- Results],
    [io:format("FAILURE ~s: ~p~n", [Asm, Reason]) || {failure, Asm, Reason} <- Results],
    io:format("loaded ~p skipped ~p rejected ~p failures ~p~n",
              [Loaded, Skipped, Rejected, Failures]),
    halt(case Failures of 0 -> 0; _ -> 1 end).

pair(Line) ->
    [Orig, Asm] = binary:split(Line, <<"\t">>),
    {binary_to_list(Orig), binary_to_list(Asm)}.

check(Orig, Asm) ->
    try
        Info = info(Asm),
        Module = proplists:get_value(module, Info),
        _ = beam_lib:md5(Asm),
        {beam_file, Module, _, _, _, _} = beam_disasm:file(Asm),
        case shipped(Module) of
            true ->
                {skipped, Asm};
            false ->
                {ok, Binary} = file:read_file(Asm),
                case load(Module, Asm, Binary) of
                    ok -> ok;
                    {error, LoadReason} -> rejected(Orig, Module, LoadReason)
                end
        end
    catch
        Class:Error -> {failure, Asm, {Class, Error}}
    end.

%% beam_lib has returned both {ok, Info} and Info across releases; the check
%% cares about the contents, not the wrapper.
info(File) ->
    case beam_lib:info(File) of
        {ok, Info} when is_list(Info) -> Info;
        Info when is_list(Info) -> Info
    end.

rejected(Orig, Module, Reason) ->
    {ok, Binary} = file:read_file(Orig),
    case load(Module, Orig, Binary) of
        {error, Reason} -> {rejected, Orig, Reason};
        {error, Other} -> {failure, Orig, {different_reason, Reason, Other}};
        ok -> {failure, Orig, {original_loads, Reason}}
    end.

load(Module, File, Binary) ->
    unload(Module),
    case code:load_binary(Module, File, Binary) of
        {module, Module} -> unload(Module), ok;
        {error, Reason} -> {error, Reason}
    end.

unload(Module) ->
    code:purge(Module),
    code:delete(Module),
    code:purge(Module),
    ok.

shipped(Module) ->
    case code:which(Module) of
        preloaded -> true;
        non_existing -> false;
        Path when is_list(Path) ->
            lists:prefix(normalise(code:lib_dir()), normalise(Path))
    end.

normalise(Path) ->
    string:lowercase(filename:join(filename:split(Path))).

count(Results) ->
    {length([x || ok <- Results]),
     length([x || {skipped, _} <- Results]),
     length([x || {rejected, _, _} <- Results]),
     length([x || {failure, _, _} <- Results])}.
