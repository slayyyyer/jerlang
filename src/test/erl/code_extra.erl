% Loaded through code:load_file/1 from a directory that the demo adds to
% the code path at run time.
-module(code_extra).

-export([answer/0]).

answer() ->
    42.
