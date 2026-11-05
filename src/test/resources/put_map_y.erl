% Used by unit test.
% A map that survives a later call is stored in a Y register, which is the
% destination shape the handler used to reject.

-module(put_map_y).
-export([test/1]).

test(Map) ->
    Updated = Map#{a => 1, b => 2},
    id(Updated),
    Updated.

id(Value) -> Value.
