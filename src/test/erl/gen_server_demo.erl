%% Compiled by OTP 29. A gen_server callback module that is exercised against
%% the gen_server from an OTP installation, so both sides are unmodified OTP
%% code building on proc_lib.

-module(gen_server_demo).

-behaviour(gen_server).

-export([ping/0, start/1, start_hibernating/1, add/2, get/1, bump/1, stop/1]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

ping() ->
    pong.

%% Starts a server registered under this module's name. handle_continue/2 is
%% deliberately not implemented: gen_server has to start anyway, because it
%% builds that callback as an external fun.
start(Initial) ->
    {ok, _Pid} = gen_server:start_link({local, ?MODULE}, ?MODULE, Initial, []),
    ok.

%% Start with hibernate_after so an idle server reaches erlang:hibernate/0.
%% The next call wakes it and must be handled normally.
start_hibernating(Initial) ->
    {ok, _Pid} = gen_server:start_link({local, ?MODULE}, ?MODULE, Initial,
                                       [{hibernate_after, 10}]),
    ok.

add(Server, N) ->
    gen_server:call(Server, {add, N}).

get(Server) ->
    gen_server:call(Server, get).

bump(Server) ->
    gen_server:cast(Server, bump).

stop(Server) ->
    gen_server:stop(Server).

init(Initial) ->
    {ok, Initial}.

handle_call({add, N}, _From, State) ->
    Next = State + N,
    {reply, Next, Next};
handle_call(get, _From, State) ->
    {reply, State, State}.

handle_cast(bump, State) ->
    {noreply, State + 1}.

handle_info(_Info, State) ->
    {noreply, State}.

terminate(_Reason, _State) ->
    ok.
