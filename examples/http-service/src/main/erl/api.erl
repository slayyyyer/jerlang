%% The service. Every route, status code and byte of every body is decided here;
%% the Java side only moves bytes.
%%
%% handle/3 receives the method, the path and the request body as binaries and
%% returns {Status, ContentType, Body}. That tuple is the entire contract with
%% Server.java, which is why adding a route never means touching Java.
%%
%% Three things had to be hand-rolled, because this runtime does not have them
%% yet:
%%
%%   * no binary:split/2 or binary:part/3, and no string module, so the path is
%%     matched as a binary;
%%   * no iolist_to_binary/1, and list_to_binary/1 only works where erlc folds
%%     it at compile time, so JSON is assembled with binary expressions;
%%   * building a binary by appending to an accumulator inside a loop compiles
%%     fine but fails at run time ("Unsupported bs_create_bin segment type:
%%     private_append"), so escape/1 recurses and appends the result of the
%%     recursive call instead of threading an accumulator.
%%
%% Check docs/beam-compatibility.md before reaching for a stdlib function that
%% may not be there; all three of these were found by trying them.
-module(api).

-export([init/0, handle/3]).

init() ->
    store:start(),
    ok.

handle(<<"GET">>, <<"/">>, _Body) ->
    text(200, <<"JErlang key-value service\n"
                "  GET    /kv          list every entry as JSON\n"
                "  GET    /kv/<key>    one entry\n"
                "  PUT    /kv/<key>    store the request body as the value\n"
                "  DELETE /kv/<key>    remove an entry\n"
                "  GET    /health      liveness, for a load balancer\n"
                "  GET    /metrics     runtime counters, served by the JVM side\n">>);

handle(<<"GET">>, <<"/health">>, _Body) ->
    text(200, <<"ok\n">>);

handle(<<"GET">>, <<"/kv">>, _Body) ->
    json(200, kv_json(store:all()));

handle(<<"GET">>, Path, _Body) ->
    case key_of(Path) of
        {ok, Key} -> lookup(Key);
        error -> json(404, error_json(<<"no_such_route">>, Path))
    end;

handle(<<"PUT">>, Path, Body) ->
    case key_of(Path) of
        {ok, Key} ->
            store:put(Key, Body),
            no_content();
        error ->
            json(404, error_json(<<"no_such_route">>, Path))
    end;

handle(<<"DELETE">>, Path, _Body) ->
    case key_of(Path) of
        {ok, Key} ->
            case store:delete(Key) of
                ok -> no_content();
                not_found -> json(404, error_json(<<"not_found">>, Key))
            end;
        error ->
            json(404, error_json(<<"no_such_route">>, Path))
    end;

handle(Method, _Path, _Body) ->
    json(405, error_json(<<"method_not_allowed">>, Method)).

%% Routing. A key is everything after /kv/, so a key cannot contain a slash and
%% nothing is URL-decoded: this shows the embedding shape, it is not a finished
%% HTTP framework.
key_of(<<"/kv/", Key/binary>>) when Key =/= <<>> -> {ok, Key};
key_of(_) -> error.

lookup(Key) ->
    case store:get(Key) of
        {ok, Value} -> json(200, object(Key, Value));
        not_found -> json(404, error_json(<<"not_found">>, Key))
    end.

%% Responses.
text(Status, Body) ->
    {Status, <<"text/plain; charset=utf-8">>, Body}.

json(Status, Document) ->
    {Status, <<"application/json">>, <<Document/binary, 10>>}.

no_content() ->
    {204, <<"text/plain; charset=utf-8">>, <<>>}.

%% JSON, by hand. Every value this service produces is a string, so the encoder
%% is an escaping function and nothing else; numbers or nesting would mean a real
%% encoder here, or a JSON library on the Erlang side.
kv_json([]) ->
    <<"[]">>;
kv_json(Pairs) ->
    <<"[", (join_objects(Pairs))/binary, "]">>.

join_objects([{Key, Value}]) ->
    object(Key, Value);
join_objects([{Key, Value} | Rest]) ->
    <<(object(Key, Value))/binary, ",", (join_objects(Rest))/binary>>.

object(Key, Value) ->
    <<"{\"key\":\"", (escape(Key))/binary,
      "\",\"value\":\"", (escape(Value))/binary, "\"}">>.

error_json(Kind, Detail) ->
    <<"{\"error\":\"", (escape(Kind))/binary,
      "\",\"detail\":\"", (escape(Detail))/binary, "\"}">>.

%% Byte values are written out rather than as $ escapes so the JSON that comes
%% out is obvious: 34 is the double quote, 92 a backslash, 10/13/9 newline,
%% carriage return and tab.
escape(<<>>) ->
    <<>>;
escape(<<34, Rest/binary>>) ->
    <<"\\\"", (escape(Rest))/binary>>;
escape(<<92, Rest/binary>>) ->
    <<"\\\\", (escape(Rest))/binary>>;
escape(<<10, Rest/binary>>) ->
    <<"\\n", (escape(Rest))/binary>>;
escape(<<13, Rest/binary>>) ->
    <<"\\r", (escape(Rest))/binary>>;
escape(<<9, Rest/binary>>) ->
    <<"\\t", (escape(Rest))/binary>>;
escape(<<Control, Rest/binary>>) when Control < 32 ->
    %% Valid JSON needs an escape here. Dropping the byte keeps this short; a
    %% real encoder would emit \u00XX.
    escape(Rest);
escape(<<Byte, Rest/binary>>) ->
    <<Byte, (escape(Rest))/binary>>.
