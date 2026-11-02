%% OTP's supervisor, unmodified, from the Erlang installation. The child spec is
%% a map, so this is also a check that map literals and the supervisor's own
%% parsing of them work.
-module(demo_sup).
-behaviour(supervisor).

-export([start_link/0, init/1]).

start_link() ->
    supervisor:start_link({local, ?MODULE}, ?MODULE, []).

init([]) ->
    Worker = #{id => demo_worker,
               start => {demo_worker, start_link, []},
               restart => permanent,
               shutdown => 5000,
               type => worker,
               modules => [demo_worker]},
    {ok, {#{strategy => one_for_one, intensity => 3, period => 10}, [Worker]}}.
