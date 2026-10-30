%% A supervisor with no children, used by the included application in the
%% milestone 6 exit test.
-module(ms6_empty_sup).

-behaviour(supervisor).

-export([start_link/0, init/1]).

start_link() ->
    supervisor:start_link(?MODULE, []).

init([]) ->
    {ok, {{one_for_one, 1, 5}, []}}.
