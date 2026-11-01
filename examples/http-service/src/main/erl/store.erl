%% The state of the service: a list of {Key, Value} pairs inside one registered
%% process.
%%
%% A map is the obvious choice and it does not work in this runtime: map lookups
%% compare binary keys by object identity, not by content. Storing a key that was
%% built from the request path and then looking it up with an equal literal
%% returns error; only the very same term instance is found. Each of these was
%% checked against 1.0.0:
%%
%%     maps:find(<<"a">>, maps:put(binary:copy(<<"a">>), 1, #{}))   -> error
%%     K = <<"name">>, maps:find(<<"name">>, maps:put(K, 1, #{}))  -> error
%%     K = <<"name">>, maps:find(K, maps:put(K, 1, #{}))           -> {ok,1}
%%
%% Term pattern matching and =:= do compare binaries by content, so the list is
%% searched with a match. That also means every lookup is a linear scan: fine for
%% an example, and the reason a real service would put the same state in ETS
%% (5x BEAM's cost, measured) or an OTP gen_server for supervision instead.
-module(store).

-export([start/0, get/1, put/2, delete/1, all/0]).

-define(SERVER, kv_store).

start() ->
    case whereis(?SERVER) of
        undefined ->
            Pid = spawn(fun() -> loop([]) end),
            register(?SERVER, Pid),
            ok;
        _Pid ->
            ok
    end.

get(Key) -> call({get, Key}).

put(Key, Value) -> call({put, Key, Value}).

delete(Key) -> call({delete, Key}).

all() -> call(all).

%% A synchronous call: send the request, wait for the reply. The receive parks the
%% process instead of blocking the thread, so a thousand of these cost a thousand
%% processes and no OS threads.
call(Request) ->
    ?SERVER ! {self(), Request},
    receive
        {store, Reply} -> Reply
    after 5000 ->
        exit(store_timeout)
    end.

loop(Entries) ->
    receive
        {From, {get, Key}} ->
            From ! {store, find(Key, Entries)},
            loop(Entries);
        {From, {put, Key, Value}} ->
            From ! {store, ok},
            loop(store(Key, Value, Entries));
        {From, {delete, Key}} ->
            case remove(Key, Entries) of
                {ok, Rest} ->
                    From ! {store, ok},
                    loop(Rest);
                not_found ->
                    From ! {store, not_found},
                    loop(Entries)
            end;
        {From, all} ->
            From ! {store, Entries},
            loop(Entries)
    end.

%% The head pattern is the comparison: Key is already bound, so the clause matches
%% only an entry whose key is equal in content to the one asked for.
find(Key, [{Key, Value} | _Rest]) -> {ok, Value};
find(Key, [_Other | Rest]) -> find(Key, Rest);
find(_Key, []) -> not_found.

store(Key, Value, [{Key, _Old} | Rest]) -> [{Key, Value} | Rest];
store(Key, Value, [Other | Rest]) -> [Other | store(Key, Value, Rest)];
store(Key, Value, []) -> [{Key, Value}].

remove(Key, [{Key, _Old} | Rest]) -> {ok, Rest};
remove(Key, [Other | Rest]) ->
    case remove(Key, Rest) of
        {ok, Rest2} -> {ok, [Other | Rest2]};
        not_found -> not_found
    end;
remove(_Key, []) -> not_found.
