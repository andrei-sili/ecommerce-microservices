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
import landing, in which a relay could publish an event the spy has no queue for yet. Today that
window is empty: the queues are durable, so it only exists on a first boot with a brand-new
volume, and a brand-new volume means brand-new service databases with no outbox rows to publish.

That is a property of the current seeding, **not** of this design. Anything that puts outbox rows
in a service database at startup — a seed migration, a fixture loader, a restored dump — reopens
the window silently: the events are simply not copied, and no probe fails. If that ever lands,
gate the publishing services on `rabbitmq-spy-topology` with
`condition: service_completed_successfully` and accept the coupling.

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

Raising either is safe. Lowering one below those figures silently weakens every probe that reads
these queues.

## Checking it landed

```
docker compose ps rabbitmq-spy-topology          # should show Exited (0)
docker compose logs rabbitmq-spy-topology
docker exec ecommerce-rabbitmq rabbitmqctl -q list_bindings \
  source_name destination_name routing_key | grep spy
```
