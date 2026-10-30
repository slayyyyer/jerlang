%% Exercises the native file module.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/file_demo.erl
%%
%% Every function is given a directory the caller owns, so the demo never
%% depends on where the node happens to run.
-module(file_demo).

-export([write_read_binary/1, open_write_read/1, read_lines/1, append_mode/1,
         update_mode/1, list_dir_names/1, consult_terms/1, consult_values/1,
         missing_file/1, position_and_eof/1, bad_consult/1]).

%% write_file/2 and read_file/1 pass every byte through unchanged.
write_read_binary(Dir) ->
    Path = Dir ++ "/file_demo.bin",
    Data = <<0, 1, 2, 253, 254, 255>>,
    ok = file:write_file(Path, Data),
    {ok, Data} = file:read_file(Path),
    ok = file:delete(Path),
    ok.

%% An open io device writes iodata and reads binaries, and read/2 reports
%% eof only when nothing is left.
open_write_read(Dir) ->
    Path = Dir ++ "/file_demo_open.txt",
    {ok, Out} = file:open(Path, [write, binary]),
    ok = file:write(Out, [<<"hello">>, <<", ">>, "world"]),
    ok = file:close(Out),
    {ok, In} = file:open(Path, [read, binary]),
    {ok, <<"hello, world">>} = file:read(In, 12),
    eof = file:read(In, 1),
    ok = file:close(In),
    {ok, <<"hello, world">>} = file:read_file(Path),
    ok = file:delete(Path),
    ok.

%% The default mode is list, and read_line/1 keeps the line feed.
read_lines(Dir) ->
    Path = Dir ++ "/file_demo_lines.txt",
    ok = file:write_file(Path, "first line\nsecond line\n"),
    {ok, In} = file:open(Path, [read]),
    {ok, "first line\n"} = file:read_line(In),
    {ok, "second line\n"} = file:read_line(In),
    eof = file:read_line(In),
    ok = file:close(In),
    ok = file:delete(Path),
    ok.

%% write_file/3 appends, and exclusive refuses to replace a file.
append_mode(Dir) ->
    Path = Dir ++ "/file_demo_append.txt",
    ok = file:write_file(Path, "one"),
    ok = file:write_file(Path, "two", [append]),
    {ok, <<"onetwo">>} = file:read_file(Path),
    {error, eexist} = file:write_file(Path, "three", [exclusive]),
    {ok, <<"onetwo">>} = file:read_file(Path),
    ok = file:delete(Path),
    ok.

%% Opening for read and write updates the file in place instead of
%% replacing it, so a write lands where the last read stopped.
update_mode(Dir) ->
    Path = Dir ++ "/file_demo_update.txt",
    ok = file:write_file(Path, "abcdef"),
    {ok, Io} = file:open(Path, [read, write, binary]),
    {ok, <<"abc">>} = file:read(Io, 3),
    ok = file:write(Io, <<"XYZ">>),
    {ok, 0} = file:position(Io, bof),
    {ok, <<"abcXYZ">>} = file:read(Io, 6),
    eof = file:read(Io, 1),
    ok = file:close(Io),
    ok = file:delete(Path),
    ok.

%% list_dir/1 returns plain names, not paths.
list_dir_names(Dir) ->
    A = Dir ++ "/file_demo_a.txt",
    B = Dir ++ "/file_demo_b.txt",
    ok = file:write_file(A, "a"),
    ok = file:write_file(B, "b"),
    {ok, Names} = file:list_dir(Dir),
    true = lists:member("file_demo_a.txt", Names),
    true = lists:member("file_demo_b.txt", Names),
    {error, enoent} = file:list_dir(Dir ++ "/file_demo_none"),
    ok = file:delete(A),
    ok = file:delete(B),
    ok.

%% consult/1 reads the terms of the configuration files applications use.
consult_terms(Dir) ->
    Path = Dir ++ "/file_demo.config",
    Text = "% an application description\n"
           "{application, file_demo}.\n"
           "{modules, [file_demo, file_demo_server]}.\n"
           "{enabled, true}.\n"
           "{nested, {ok, \"text\"}}.\n"
           "{data, <<1, 2, 3>>}.\n",
    ok = file:write_file(Path, Text),
    {ok, Terms} = file:consult(Path),
    [{application, file_demo},
     {modules, [file_demo, file_demo_server]},
     {enabled, true},
     {nested, {ok, "text"}},
     {data, <<1, 2, 3>>}] = Terms,
    ok = file:delete(Path),
    ok.

%% The literal syntax consult accepts: numbers, quoted atoms, characters
%% and lists written with a tail.
consult_values(Dir) ->
    Path = Dir ++ "/file_demo_values.txt",
    Text = "42.\n-7.\n1.5.\n'quoted atom'.\n$x.\n16#FF.\n[1, 2 | [3]].\n",
    ok = file:write_file(Path, Text),
    {ok, Terms} = file:consult(Path),
    [A, B, C, D, E, F, G] = Terms,
    true = A =:= 42,
    true = B =:= -7,
    true = C =:= 1.5,
    true = D =:= 'quoted atom',
    true = E =:= $x,
    true = F =:= 16#FF,
    true = G =:= [1, 2, 3],
    ok = file:delete(Path),
    ok.

%% A missing file is enoent for every reader.
missing_file(Dir) ->
    Path = Dir ++ "/file_demo_missing.txt",
    {error, enoent} = file:read_file(Path),
    {error, enoent} = file:open(Path, [read]),
    {error, enoent} = file:consult(Path),
    ok.

%% The position and eof functions follow the bytes that were read.
position_and_eof(Dir) ->
    Path = Dir ++ "/file_demo_position.txt",
    ok = file:write_file(Path, <<"abcdef">>),
    {ok, In} = file:open(Path, [read, binary]),
    {ok, 0} = file:position(In),
    {ok, <<"abc">>} = file:read(In, 3),
    {ok, 3} = file:position(In),
    false = file:eof(In),
    {ok, 0} = file:position(In, bof),
    {ok, <<"abcdef">>} = file:read(In, 6),
    true = file:eof(In),
    {ok, 4} = file:position(In, {bof, 4}),
    {ok, <<"ef">>} = file:read(In, 2),
    ok = file:close(In),
    ok = file:delete(Path),
    ok.

%% A syntax error names the line the parser stopped on.
bad_consult(Dir) ->
    Path = Dir ++ "/file_demo_bad.config",
    ok = file:write_file(Path, "{ok}.\n{broken\n"),
    {error, {Line, erl_parse, _}} = file:consult(Path),
    true = Line >= 2,
    ok = file:delete(Path),
    ok.
