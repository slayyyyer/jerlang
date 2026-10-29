%% A stand-in for OTP's lists.beam, used by ListsModuleTest. It implements only
%% two functions, so the test can tell a module loaded from disk apart from the
%% built-in implementation. Everything else falls back to the runtime.
%% Compile with: erlc -o src/test/resources/override src/test/erl/override/lists.erl
-module(lists).
-export([sort/1, usort/1]).

sort(_List) -> from_beam.
usort(_List) -> from_beam.
