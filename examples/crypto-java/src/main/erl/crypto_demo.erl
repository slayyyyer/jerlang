%% Crypto-style operations without a NIF.
%%
%% The hashing and MAC primitives come from the JDK's java.security and
%% javax.crypto packages. The java module turns the byte arrays they return into
%% Erlang binaries, so the boundary is explicit: this is application code, not a
%% replacement for OTP's crypto module.
-module(crypto_demo).

-export([sha256_hex/1, hmac_sha256_hex/2, random_hex/1]).

sha256_hex(Data) ->
    Digest = java:call_static("java.security.MessageDigest", "getInstance",
        ["SHA-256"]),
    try
        hex(java:call(Digest, "digest", [Data]))
    after
        java:release(Digest)
    end.

hmac_sha256_hex(Key, Data) ->
    KeySpec = java:new("javax.crypto.spec.SecretKeySpec",
        [Key, "HmacSHA256"]),
    Mac = java:call_static("javax.crypto.Mac", "getInstance",
        ["HmacSHA256"]),
    try
        java:call(Mac, "init", [KeySpec]),
        hex(java:call(Mac, "doFinal", [Data]))
    after
        java:release(Mac),
        java:release(KeySpec)
    end.

random_hex(Bytes) when is_integer(Bytes), Bytes > 0 ->
    Random = java:new("java.security.SecureRandom", []),
    try
        hex(java:call(Random, "generateSeed", [Bytes]))
    after
        java:release(Random)
    end.

hex(Binary) ->
    unicode:characters_to_binary(hex(Binary, [])).

hex(<<>>, Acc) ->
    lists:reverse(Acc);
hex(<<Byte, Rest/binary>>, Acc) ->
    High = nibble(Byte div 16),
    Low = nibble(Byte rem 16),
    hex(Rest, [Low, High | Acc]).

nibble(N) when N < 10 ->
    $0 + N;
nibble(N) ->
    $a + N - 10.
