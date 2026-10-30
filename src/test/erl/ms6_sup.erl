%% The supervisor of the milestone 6 tree: one permanent gen_server child,
%% restarted one_for_one. The intensity is deliberately small so a test can
%% exhaust it as well as observe a single restart.
-module(ms6_sup).

-behaviour(supervisor).

-export([start_link/0, init/1]).

start_link() ->
    supervisor:start_link({local, ?MODULE}, ?MODULE, []).

init([]) ->
    Child = #{id => ms6_worker,
              start => {ms6_worker, start_link, []},
              restart => permanent,
              shutdown => 5000,
              type => worker,
              modules => [ms6_worker]},
    {ok, {{one_for_one, 3, 10}, [Child]}}.
