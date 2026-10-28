# voucher-service

Internal campaign voucher redemption service.

Clients run promotional campaigns; each campaign has a fixed stock of vouchers.
End users redeem a voucher code via the storefront, which calls this service.

## Running

```
./gradlew bootRun
```

H2 in-memory DB, seeded from `src/main/resources/schema.sql` + `data.sql` on startup.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/vouchers/{code}/redeem?userId=...` | Redeem a voucher (subject to the per-user limit below) |
| POST | `/vouchers/{code}/void` | Mark a voucher void |
| GET | `/campaigns/{id}/stats` | Stock + redemption counts |
| GET | `/campaigns/by-client/{clientCode}/stats` | Same, per client |

## Per-user redemption limit

Each campaign has `max_redemptions_per_user` (default `2`). A user who already has
that many redemptions recorded in a campaign is rejected with:

```json
{ "result": "FAILED", "message": "User has reached the redemption limit for this campaign (2)" }
```

The count is taken from the `redemption` table, so a redemption still counts
toward the limit if its voucher is later voided. The limit applies per campaign;
a user at the limit on one campaign can still redeem on another.

## Sample data

Campaign 1 (`ACME`, "Raya Flash Deal") — vouchers `RAYA-0001` … `RAYA-0006`
Campaign 2 (`NOVA`, "Merdeka Giveaway") — vouchers `MRDK-0001`, `MRDK-0002`
Campaign 3 (`ACME`) — inactive, no stock

`user-99` already has redemption history on campaign 1 (two redemptions, so already at the default limit).

## Tests

```
./gradlew test
```
