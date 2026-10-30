% Exercises the code server from compiled Erlang: which/1, get_path/0,
% add_patha/1, load_file/1, is_loaded/1, lib_dir/1, priv_dir/1 and
% root_dir/0. The module is loaded from a directory on the code path, not
% from the working directory.
-module(code_demo).

-export([which/0, which_in/1, first_path/0, add_and_load/1, loaded/1,
         lib_dir/1, priv_dir/1, root_dir/0]).

%% What the code server answers for this module.
which() ->
    code:which(?MODULE).

%% True when the code server finds this module at Path.
which_in(Path) ->
    list_to_atom(Path) =:= code:which(?MODULE).

%% The first entry of the code path.
first_path() ->
    hd(code:get_path()).

%% Put Dir in front of the code path and load code_extra from it.
add_and_load(Dir) ->
    true = code:add_patha(Dir),
    {module, code_extra} = code:load_file(code_extra),
    code_extra:answer().

loaded(Module) ->
    case code:is_loaded(Module) of
        false -> not_loaded;
        {file, _} -> loaded
    end.

lib_dir(App) ->
    code:lib_dir(App).

priv_dir(App) ->
    code:priv_dir(App).

root_dir() ->
    code:root_dir().
