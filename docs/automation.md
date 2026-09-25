# Analyzing with AI agents and SQL

The correlator's outputs are built to be read by programs as well as people:
a bounded digest that states its own coverage and the commands behind each
table, ranked tables as JSON, and one exported row per stack for SQL. This
page shows how to hand a capture to an AI agent and how to query it with
[DuckDB](https://duckdb.org/).

- [Analyzing with AI agents](#analyzing-with-ai-agents)
- [Analyzing with SQL](#analyzing-with-sql)

## Analyzing with AI agents

Give an agent the digest first: `jonoffcpu-summary.md` in the analysis
directory (or `summarize --profile … --app …`, which takes the capture section
from the report the profile carries), written with `--app` so that its tables
name your code. It is bounded in size, says when and on what the capture was
recorded, states its coverage and losses, defines its terms, and holds the
ranked tables with the exact command that reproduces each one, so the agent
can drill down with `top --format json` or `stacks` instead of reading raw
stacks. For custom questions, `export --format jsonl` gives one row per profile
entry, frames as arrays (see [Analyzing with SQL](#analyzing-with-sql)). Do not
hand an agent the capture stream or the JFR: they are large, binary, and the
correlator has already extracted what they contain. When comparing runs, give
it `top --baseline` output, which normalizes per unit of work and warns when
the runs are not comparable.

## Analyzing with SQL

`export --format jsonl` writes one row per profile entry that DuckDB reads
directly: the stacks as arrays (`javaFrames`, `kernelFrames`, `userFrames`),
the stack without generated-class addresses (`canonicalJavaStack`, which joins
across runs), the counters, the thread's pool (`threadPool`), a `run` column
(`--run-label`), and on every row whether its estimated columns are valid
(`estimateAvailable`). The 64-bit counters are decimal strings, as the proto3
JSON mapping writes them, so cast them to `UBIGINT` (or declare the column
types in `read_json`). The columns are listed in OFFLINE.md's
[Stack profile](../jonoffcpu-correlator/OFFLINE.md#stack-profile). Rank the
blocked waits by application boundary:

```sh
java -jar jonoffcpu-correlator.jar export \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --format jsonl --output broker-offcpu.jsonl
```

```sql
CREATE TEMP TABLE entries AS
SELECT javaFrames AS frames, observedNanos::UBIGINT AS nanos, intervals::UBIGINT AS intervals
FROM read_json('broker-offcpu.jsonl', format = 'newline_delimited');

SELECT coalesce(list_filter(frames, lambda f: regexp_matches(f, '^org\.apache\.'))[-1],
                '[no application frame]') AS boundary,
       round(sum(nanos) / 1e9, 3) AS seconds, sum(intervals) AS intervals
FROM entries
WHERE NOT list_bool_or(list_transform(frames, lambda f: regexp_matches(f,
      '^(io\.netty\.channel\.epoll\.Native\.epollWait0?|java\.util\.concurrent\.ThreadPoolExecutor\.getTask|sun\.nio\.ch\.SelectorImpl\.select|java\.util\.concurrent\.ForkJoinPool\.awaitWork)$|BlockingQueue\.take(All)?$|^java\.lang\.ref\.|^libasyncProfiler\.so\.')))
GROUP BY 1 ORDER BY 2 DESC LIMIT 20;
```

On a Pulsar broker this returns `internalConsumerFlow` with 11.982 s in 4,245
intervals and `GrowableBatchedArrayBlockingQueue.offer` with 4.946 s in 1,565
as the top application rows, as `top` does. To compare runs, export each with
its own `--run-label` and load them into one table; `--run-metadata FILE`
writes each profile's provenance and totals as one JSON object (a
`RunMetadata` message) to join on `run`. Seconds per million measured messages
and share of blocked application time, for two runs of 5 million messages
each:

```sql
CREATE TEMP TABLE e AS
SELECT 5.0 AS mmsgs, * FROM read_json(['alpine.jsonl', 'wolfi.jsonl'], format = 'newline_delimited');

WITH b AS (
  SELECT run, mmsgs, observedNanos::UBIGINT AS nanos,
         list_filter(javaFrames, lambda f: regexp_matches(f, '^org\.apache\.'))[-1] AS boundary
  FROM e
  WHERE NOT list_bool_or(list_transform(javaFrames, lambda f: regexp_matches(f, '<waiting patterns>'))))
SELECT boundary,
       round(sum(nanos) FILTER (WHERE run = 'alpine') / 1e9 / any_value(mmsgs), 3) AS alpine_s_per_m,
       round(sum(nanos) FILTER (WHERE run = 'wolfi') / 1e9 / any_value(mmsgs), 3) AS wolfi_s_per_m
FROM b WHERE boundary IS NOT NULL
GROUP BY 1 ORDER BY greatest(coalesce(alpine_s_per_m, 0), coalesce(wolfi_s_per_m, 0)) DESC LIMIT 20;
```

Replace `<waiting patterns>` with the lines of `preset:jvm-waiting`
(`stacks --list-presets` prints them) joined with `|`. Use `estimatedNanos`
instead of `observedNanos` only when `estimateAvailable` is true for both runs:
under proportional admission observed time under-weights short waits (see
[Correlate](analysis.md#correlate) for the size of the difference).
