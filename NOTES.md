# NOTES

## What I changed and why

The feature is a per-user cap on redemptions within a campaign. I kept it as small
as I could and tried to make each commit one step.

**The limit lives on the campaign.** I added a `max_redemptions_per_user` column to
the `campaign` table with a database default of `2`, and the matching field on the
`Campaign` entity. The campaign row already holds the other redemption rules (active
flag, stock), so it is the natural home. Because the default is on the column, the
three seeded campaigns pick up `2` without me touching `data.sql`.

**The count comes from the `redemption` table.** Every redemption already writes a
row with the campaign and the user, so a single derived query,
`countByCampaignIdAndUserId`, gives the number I need. No new table, no counter on
the user to keep in sync, one extra query per redeem.

**The check is in `VoucherService.redeem`, last.** It runs after the existing checks
(voucher exists, not already used, campaign active, stock left) and before any
write. Putting it last means the more specific errors still win: if a voucher is
already redeemed you hear that, not "limit reached". When the user is at or over
the limit, the response is the existing `FAILED` shape with the message
`User has reached the redemption limit for this campaign (2)`, where the number is
whatever that campaign's limit is.

**`redeem` is now `@Transactional`.** The method already did three separate writes
with no transaction around them. Now that it reads from the redemption table and
then inserts into it, I wanted those in one unit of work. It does not solve the
concurrency problem (see below), but a failure halfway no longer leaves a voucher
marked `REDEEMED` with no redemption row behind it.

**Voided vouchers still count.** `user-99` in the seed has one `REDEEMED` and one
`VOID` voucher, each with a redemption row, so they are already at the limit of 2.
I counted redemption rows rather than `REDEEMED` vouchers because the user did
redeem, a void is an operator action after the fact, and the stats endpoint already
treats a redemption row as a redemption. If the business wants a void to hand the
allowance back, that belongs in `voidVoucher`, not in this check.

**Tests** are in `RedemptionLimitTest`: existing campaigns default to 2, `user-99` is
rejected on campaign 1, a new user can redeem twice and is rejected on the third,
`user-99` can still redeem on campaign 2, and a rejected attempt leaves stock and
the voucher untouched. The class is `@Transactional` so every test rolls back. The
existing tests run against the shared seed with no cleanup, and I did not want my
tests to change what they see or the other way round, so mine also stay away from
`RAYA-0001`, which the existing suite consumes.

Smaller things: renamed `gradlew.bat.txt` back to `gradlew.bat` so the wrapper runs
on Windows, and added a README section describing the limit and the rejection.

## What I noticed but deliberately did not change

**Race conditions.** Two requests for the same user at the same moment can both
read a count of 1 and both get through. The stock check has the same problem
already, so this is not new, but my change does not fix it. The proper fix is a
lock on the campaign row (`SELECT ... FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)`)
or a unique constraint that makes the database the final arbiter. That changes how
the existing stock decrement behaves and needs a proper concurrency test, so I left
it as its own piece of work.

**`voidVoucher` does not restore stock or remove the redemption row.** It may be
intentional, it may be a bug; the code does not say. Changing it would change the
numbers the stats endpoint reports, which clients may already rely on.

**`AuditClient`.** The URL and API key are hard-coded in source, the HTTP call is
made synchronously inside the redeem request, and failures are swallowed with a
one-line log. All three should change (config, async or queued, secret out of the
repo) but none of them are part of this feature. It is also why a successful redeem
in the test suite takes a few seconds: the audit host does not resolve and the
call has to time out.

**`CampaignStatsService`** runs one query per voucher to work out the redeemed
count. Fine with nine vouchers, not with a hundred thousand. A single count query
would replace the loop.

**General tidy-ups.** Mixed field and constructor injection, voucher status as a raw
string, `findById(...).get()` that throws on a missing id, every outcome returned
as HTTP 200. All real, all would touch most of the files and bury the actual change
in the diff.

**The existing tests share state.** They run against the live seed without rolling
back, so they depend on order. I did not retrofit them; the new test class shows
how I would.

## What I would do next if I had another day

1. Lock the campaign row during redeem and add a concurrency test that fires a
   burst of parallel redemptions for the same user and checks that exactly
   `limit` of them succeed.
2. Expose `maxRedemptionsPerUser` in the stats response and add a way to set it per
   campaign through the API. Right now it can only be changed in the database.
3. Return real HTTP status codes (409 or 422) instead of 200 with `result: FAILED`,
   with a small error code enum the storefront can branch on instead of matching on
   message text.
4. Take the audit call off the request path and move the API key out of source.
5. Replace the `DROP TABLE` bootstrap in `schema.sql` with Flyway so adding a column
   is a migration rather than a reseed.

## Reflections

### What did you get wrong first, and how did you notice?

I started by setting up the repository wrong. My first pass put the project in a
`voucher-service/` subfolder under the brief, which meant `./gradlew test` did not
work from the repo root and anyone cloning it had to know to `cd` first. I noticed
when I thought about how a reviewer would actually run it, threw that layout away
and started a fresh branch with the project at the root.

The smaller one was assuming `./gradlew` would just work. The very first command
failed with "Unable to locate a Java Runtime" even though a Homebrew JDK 17 was
installed: it was keg-only and not on the path. Pointing `JAVA_HOME` at the
Homebrew JDK fixed it, but it ate the first chunk of the time budget.

### Which AI suggestion did you reject, and why?

While designing the check, the AI raised adding a pessimistic lock on the campaign
row at the same time so the count-then-insert would be safe under concurrent
requests, and separately floated exposing the limit in the stats response and an
endpoint to set it. I said no to both. The lock is the right eventual fix, but it
changes how the existing stock decrement behaves, I could not load test it in the
time, and I did not want the feature commit to carry a behaviour change nobody
asked for. The API additions were useful but the brief said "that's the whole
feature" and I took that at face value. Both are written up above so they are
conscious gaps, not missed ones.

### What took you longest?

The environment, not the code. Getting Java visible to Gradle and sitting through
the first full build took longer than the schema change, the service check and the
tests put together. The one real design question was whether a voided voucher
still counts toward the limit; once I had decided that, the feature itself was
quick. Working out what to say in these notes took a surprising share of what was
left.
