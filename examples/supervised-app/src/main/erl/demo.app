{application, demo,
 [{description, "a supervised worker started from Java"},
  {vsn, "1"},
  {modules, [demo_app, demo_sup, demo_worker]},
  {registered, [demo_sup, demo_worker]},
  {applications, [kernel, stdlib]},
  {mod, {demo_app, []}},
  {env, []}]}.
