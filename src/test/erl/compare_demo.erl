%% Comparison operators in every operand order and across term types.
%% Compile with: erlc -o src/test/resources src/test/erl/compare_demo.erl
-module(compare_demo).
-export([run/0, lt/2, le/2, gt/2, ge/2, eq/2, ne/2, eq_exact/2, ne_exact/2,
         guard_lt/2, guard_ge/2, guard_eq/2, guard_ne/2, guard_eq_exact/2,
         guard_ne_exact/2, small/1, at_most/1, big/1, at_least/1,
         y_lt/2, y_guard/2, mixed/0]).

lt(A, B) -> A < B.
le(A, B) -> A =< B.
gt(A, B) -> A > B.
ge(A, B) -> A >= B.
eq(A, B) -> A == B.
ne(A, B) -> A /= B.
eq_exact(A, B) -> A =:= B.
ne_exact(A, B) -> A =/= B.

guard_lt(A, B) when A < B -> yes;
guard_lt(_, _) -> no.

guard_ge(A, B) when A >= B -> yes;
guard_ge(_, _) -> no.

guard_eq(A, B) when A == B -> yes;
guard_eq(_, _) -> no.

guard_ne(A, B) when A /= B -> yes;
guard_ne(_, _) -> no.

guard_eq_exact(A, B) when A =:= B -> yes;
guard_eq_exact(_, _) -> no.

guard_ne_exact(A, B) when A =/= B -> yes;
guard_ne_exact(_, _) -> no.

%% Guards with a literal on either side of the operator.
small(X) when X < 12 -> yes;
small(_) -> no.

at_most(X) when X =< 12 -> yes;
at_most(_) -> no.

big(X) when 12 < X -> yes;
big(_) -> no.

at_least(X) when 12 =< X -> yes;
at_least(_) -> no.

id(X) -> X.

%% Keep A and B in Y registers across a call, so the comparison reads y.
y_lt(A, B) ->
    _ = id(0),
    A < B.

y_guard(A, B) ->
    _ = id(0),
    case A < B of
        true -> yes;
        false -> no
    end.

%% The Erlang term order: number < atom < reference < fun < port < pid <
%% tuple < map < nil < list < bitstring.
mixed() ->
    true = (1 < a),
    true = (a < {1}),
    true = ({1} < [1]),
    true = ([] < [1]),
    true = (1 < 1.5),
    true = (1 == 1.0),
    false = (1 =:= 1.0),
    true = (1 =/= 1.0),
    true = ([1, 2] < [1, 3]),
    true = (<<1>> < <<1, 0>>),
    true = ({a, 2} < {b, 1}),
    true = (#{} < #{a => 1}),
    ok.

run() ->
    true = lt(1, 2),
    false = lt(2, 1),
    true = le(2, 2),
    false = le(3, 2),
    true = gt(2, 1),
    false = gt(1, 2),
    true = ge(2, 2),
    false = ge(1, 2),

    true = eq(1, 1.0),
    false = eq(1, 2),
    true = ne(1, 2),
    false = ne(1, 1.0),
    true = eq_exact(1, 1),
    false = eq_exact(1, 1.0),
    true = ne_exact(1, 1.0),

    yes = guard_lt(1, 2),
    no = guard_lt(2, 1),
    yes = guard_ge(2, 2),
    no = guard_ge(1, 2),

    yes = guard_eq(1, 1.0),
    no = guard_eq(1, 2),
    yes = guard_ne(1, 2),
    no = guard_ne(1, 1.0),
    yes = guard_eq_exact(1, 1),
    no = guard_eq_exact(1, 1.0),
    yes = guard_ne_exact(1, 1.0),
    no = guard_ne_exact(1, 1),

    yes = small(5),
    no = small(20),
    yes = at_most(12),
    no = at_most(13),
    yes = big(20),
    no = big(5),
    yes = at_least(12),
    no = at_least(11),

    true = y_lt(1, 2),
    false = y_lt(2, 1),
    yes = y_guard(1, 2),
    no = y_guard(2, 1),

    ok = mixed(),
    ok.
