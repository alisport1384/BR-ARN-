Pristine upstream copies of the files this app patches, at core 2.0.0.

Do not edit. scripts/sync-core.sh uses them as the merge base so the app's
engine patches can be rebased onto a new core instead of overwriting it.

1.2.9: every patched file got a baseline here (1.2.8 cached only prober.rs
and wg_prober.rs, so the other eight had none and CI would have had to
reconstruct them from the AETHER-APP-PATCH markers - which is lossy wherever a
patch REPLACES an upstream line rather than adding to it).

1.3.0: refreshed to 2.0.0, the tag the app's patches are now rebased onto.
build.rs has no baseline on purpose: it does not exist upstream at all, it is
an app file in full, so there is nothing to merge it against.

Two files are worth knowing about before the next upgrade:

  netstack.rs  Upstream 2.0.0 moved this file to smoltcp 0.14 and its feature
               list again has no congestion-control algorithm, which is the
               1.2.8-r4 root cause. The app keeps smoltcp 0.12 and its own
               netstack; upstream's BEHAVIOURAL changes in 2.0.0 were ported
               into it by hand instead and are marked AETHER-CORE-PORT 2.0.0.
               Expect this file to conflict on every upgrade. That is correct:
               it must be read by a human, not merged by a machine.

  Cargo.toml   The smoltcp pin and the socket-tcp-cubic feature are app
               patches. A merge that silently takes the upstream line reverts
               the fix five rounds of field tests paid for.
