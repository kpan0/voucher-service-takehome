# Demo walkthrough: per-user redemption limit

A step-by-step script for showing the feature end to end against the running
service. Every response below was captured from a real run on a fresh boot.

## Before you start

The database is in-memory and reseeded on every boot, so the numbers below only
line up if you start from a fresh `bootRun`. If you have already redeemed
anything, stop the app and start it again.

Gradle needs Java 17. If `./gradlew` says "Unable to locate a Java Runtime", point
it at the Homebrew JDK first:

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

Start the service in one terminal and leave it running:

```sh
./gradlew bootRun
```

It is ready when the log shows `Started CampaignApplication`. Run the curl
commands below in a second terminal.

## What the seed gives you

- Campaign 1 (`ACME`, "Raya Flash Deal"): stock 100, vouchers `RAYA-0001` to `RAYA-0006`.
  `RAYA-0004` is already redeemed and `RAYA-0005` is void, both by `user-99`, and
  both have a redemption row. So `user-99` already has 2 redemptions on campaign 1.
- Campaign 2 (`NOVA`, "Merdeka Giveaway"): stock 50, vouchers `MRDK-0001`, `MRDK-0002`.
- Every campaign has `max_redemptions_per_user = 2`, from the column default.

## The scenario

### 1. Seeded user already at the limit

`user-99` has two redemption rows on campaign 1, which is the limit. Expect
`FAILED` with the limit message. The voucher stays `ACTIVE` and stock is untouched.

```sh
curl -X POST "localhost:8080/vouchers/RAYA-0001/redeem?userId=user-99"
```

```json
{"result":"FAILED","voucherCode":null,"remainingStock":null,"message":"User has reached the redemption limit for this campaign (2)"}
```

### 2. A fresh user, first redeem

`user-7` has no history. Expect `OK`, remaining stock 99. Note this reuses
`RAYA-0001`, which proves step 1 did not consume it.

```sh
curl -X POST "localhost:8080/vouchers/RAYA-0001/redeem?userId=user-7"
```

```json
{"result":"OK","voucherCode":"RAYA-0001","remainingStock":99,"message":null}
```

### 3. Same user, second redeem

Still under the limit. Expect `OK`, remaining stock 98.

```sh
curl -X POST "localhost:8080/vouchers/RAYA-0002/redeem?userId=user-7"
```

```json
{"result":"OK","voucherCode":"RAYA-0002","remainingStock":98,"message":null}
```

### 4. Same user, third redeem

`user-7` now has two redemption rows on campaign 1. Expect `FAILED` with the limit
message. `RAYA-0003` is a valid, active voucher, so the only reason for the
rejection is the limit.

```sh
curl -X POST "localhost:8080/vouchers/RAYA-0003/redeem?userId=user-7"
```

```json
{"result":"FAILED","voucherCode":null,"remainingStock":null,"message":"User has reached the redemption limit for this campaign (2)"}
```

### 5. The capped user on a different campaign

`user-99` is at the limit on campaign 1 but has nothing on campaign 2. Expect
`OK`, remaining stock 49. This shows the limit is counted per campaign, not per
user globally.

```sh
curl -X POST "localhost:8080/vouchers/MRDK-0001/redeem?userId=user-99"
```

```json
{"result":"OK","voucherCode":"MRDK-0001","remainingStock":49,"message":null}
```

### 6. Stats for campaign 1

Expect remaining stock 98. Steps 2 and 3 consumed one each; the rejections in
steps 1 and 4 did not touch stock. `redeemedCount` is 4 (the two seeded
redemption rows plus the two from `user-7`) and `activeVoucherCount` is 2
(`RAYA-0003` and `RAYA-0006` are the only vouchers still `ACTIVE`).

```sh
curl localhost:8080/campaigns/1/stats
```

```json
{"campaignId":1,"name":"Raya Flash Deal","totalStock":100,"remainingStock":98,"redeemedCount":4,"activeVoucherCount":2}
```

## Where the behaviour comes from

- `src/main/resources/schema.sql`: the `max_redemptions_per_user` column on
  `campaign`, `NOT NULL DEFAULT 2`.
- `src/main/java/com/prizm/campaign/model/Campaign.java`: the matching field.
- `src/main/java/com/prizm/campaign/repository/RedemptionRepository.java`:
  `countByCampaignIdAndUserId`, the one query the check needs.
- `src/main/java/com/prizm/campaign/service/VoucherService.java`: the check in
  `redeem`, placed after the existing checks and before any write, inside a
  transaction.
- `src/test/java/com/prizm/campaign/RedemptionLimitTest.java`: the same
  scenario as above, as tests.

## Running the tests

```sh
./gradlew test
```

Gradle only prints a summary. To see one line per test, read the XML reports it
writes:

```sh
for f in build/test-results/test/*.xml; do
  grep -o 'testcase name="[^"]*"' "$f" | sed 's/testcase name=/PASS /'
done
grep -l '<failure\|<error' build/test-results/test/*.xml && echo "failures present" || echo "all passed"
```

Expected: 8 tests, 5 in `RedemptionLimitTest` and 3 in `VoucherServiceTest`,
all passing. A successful redeem in the tests takes a few seconds because the
audit call to `internal-audit.prizm.local` has to fail before the method returns.
