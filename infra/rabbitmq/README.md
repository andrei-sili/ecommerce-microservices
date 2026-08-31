# Spy topology

`definitions.json` declares two read-only tap queues on the `ecommerce.events` topic exchange:

| queue                | routing key    |
| -------------------- | -------------- |
| `spy.order-placed`   | `order.placed` |
| `spy.payment-events` | `payment.*`    |

They exist so the outbox-relay probes (contract rows C9(b) and C4) can read a delivered message
without hand-declaring a queue and purging it on every run. A one-shot probe is not a re-runnable
gate.

A topic exchange delivers an independent copy to every queue whose binding matches, so a tap on
the same routing key **copies**; it does not divert. The live `notification.*` and
`order.payment-events` bindings are untouched, and import declares but never deletes.

## Why the file is applied after boot, not by `load_definitions`

RabbitMQ can import a definitions file during boot. It must not be used here: boot-time import
makes the node skip seeding the default user, and it says so in its own log —
`Will not seed default virtual host and user: have definitions to load`. The result is a broker
with **zero** users, every service getting 401, and no service topology declared at all.

The only way to keep boot-time import is to put a user into `definitions.json`, and a RabbitMQ
user entry carries a `password_hash`. This repository is public, so that is not available. The
same versioned file is therefore applied by the one-shot `rabbitmq-spy-topology` service in
`docker-compose.yml` once the broker is healthy.

No application service depends on that one-shot service. The spy is a test affordance and must
never gate the stack coming up. That leaves a window between the broker going healthy and the
import landing, in which a relay can publish an event the spy has no queue for yet.

**The window reopens whenever the broker's state is reset independently of the service databases,
and that is a normal operation, not an exotic one.** `rabbitmq-data` is one of seven independent
named volumes in `docker-compose.yml`, a sibling of the six service DB volumes. So
`docker volume rm ecommerce_rabbitmq-data` — the ordinary reflex for a corrupt schema or a broker
in a bad state — destroys the spy queues and leaves all six service databases intact, outbox rows
included. No seed migration, fixture loader or restored dump is needed to get there.

What makes that quiet rather than obvious is an asymmetry, measured on this stack rather than
assumed: the service topology **self-heals** and the spy does **not**. With both
`notification.order-events` and `spy.order-placed` deleted and only the broker container
restarted, notification's queue came back on its own — its client re-declares on reconnect, and
the container was never restarted — while `spy.order-placed` stayed gone, because the importer
had already exited and nothing re-runs a one-shot. The result is a probe that is silently empty:
green because it measured nothing.

Recovery is one command, and it does not disturb anything else:

```
docker compose up -d rabbitmq-spy-topology
```

If the coupling is ever worth paying for, gate the publishing services on that service with
`condition: service_completed_successfully`.

`definitions.json` pins vhost `/`. If `RABBITMQ_VHOST` is ever changed, change it here too; the
file cannot read the environment. It deliberately does **not** declare the vhost, so a mismatch
fails the import loudly instead of quietly creating a second, unused vhost.

## Queue bounds

The taps are always on and nothing consumes them, so both are bounded. JSON takes no comments,
which is why the sizing argument lives here.

`x-overflow` is `drop-head`, and dropping is **silent** — a bound that is actually reached does
not fail a probe, it deletes the evidence and lets the probe pass green. So the bounds have to
stay clear of the largest single demand made on them:

- `x-max-length` is 1000. The biggest drain in the acceptance criteria is AC-5.9, which drains 10
  rows in one pass and asserts 10 distinct messages plus a null 11th; payment ships
  `batch-size: 50`.
- `x-message-ttl` is 1h. C9(b) waits at least 6s, inside runs that take minutes end to end.

Lowering one below those figures silently weakens every probe that reads these queues.

Editing them here does **not** change a queue that already exists. RabbitMQ will not redefine a
live queue's arguments, and the re-import does not complain: measured in both directions on an
existing `spy.order-placed`, 1000 → 5000 and 1000 → 500 each returned exit 0 while the queue
stayed at 1000. The same file applied to a queue name that did not exist yet got 5000, which is
what proves the silence belongs to the mechanism and not to the measurement.

Combined with `drop-head`, that is a complete trap: someone sees a truncated probe, raises the
bound, gets a clean exit 0, and keeps losing messages with no signal anywhere. **To actually
change a bound on an existing volume, delete the queues and let the importer recreate them:**

```
docker exec ecommerce-rabbitmq rabbitmqctl delete_queue spy.order-placed
docker exec ecommerce-rabbitmq rabbitmqctl delete_queue spy.payment-events
docker compose up -d rabbitmq-spy-topology
```

## Checking it landed

```
docker compose ps rabbitmq-spy-topology          # should show Exited (0)
docker compose logs rabbitmq-spy-topology
docker exec ecommerce-rabbitmq rabbitmqctl -q list_bindings \
  source_name destination_name routing_key | grep spy
```
