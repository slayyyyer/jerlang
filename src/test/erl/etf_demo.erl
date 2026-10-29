%% Exercises term_to_binary, binary_to_term and node/1 from real Erlang source.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/etf_demo.erl
-module(etf_demo).

-export([roundtrip/1, encoded_size/1, node_name/0, pid_node/0, self_binary/0,
         binary_node/1]).

%% Encode and decode a term in one go.
roundtrip(Term) ->
    binary_to_term(term_to_binary(Term)).

encoded_size(Term) ->
    byte_size(term_to_binary(Term)).

node_name() ->
    node().

%% The node of the process that runs this function.
pid_node() ->
    node(self()).

%% The external encoding of this process' own PID.
self_binary() ->
    term_to_binary(self()).

%% The node recorded in an encoded PID.
binary_node(Binary) ->
    node(binary_to_term(Binary)).
