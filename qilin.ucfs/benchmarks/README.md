# Used application benchmark inputs

This workflow registers **JOlden `treeadd`** and the **18 DaCapo cases** used by the integration experiments (9 DaCapo 2006 and 9 Bach). It supplies input setup and validated argument vectors for the module CLI. SPECjvm98 and Java Grande are not added: neither is used in this experiment series. No application JAR, JRE, or third-party source is committed here.

The planner does not run an analysis. A registered case is an input definition, not a claim that UCFS completes on it. The module CLI first runs Qilin CI/Spark, exports its graph, then invokes the selected UCFS solver. This workflow does not select the separate Qilin SelectX2c (`s-2c`) comparative analysis or the newer Qilin-driver typed INT endpoint.

## Build the module

Use JDK 21 and the UCFS build described in the parent module README, then build `:qilin.ucfs:installDist`. Run builds/analyses under the machine's shared resource policy; this directory does not start Gradle daemons.

## JOlden preparation

Use the pinned source revision below. `treeadd` is the JOlden program currently analysed; other JOlden programs are not registered yet. Set `bench_lock` to the shared Heavy lock used by the machine before executing the preparation commands. Both compiler/tool heaps are small and fixed. These are setup commands, not analysis measurements.

```sh
bench_inputs="$PWD/qilin.ucfs/benchmarks/local"
bench_lock=/home/usatovpavel/edu/.heavy-run.lock
mkdir -p "$bench_inputs"
git clone https://github.com/reprogrammer/jolden "$bench_inputs/jolden"
git -C "$bench_inputs/jolden" checkout --detach b0ea8356a88702f5d3549b871aae8fdbcba6db7c
mkdir -p "$bench_inputs/treeadd/classes"
flock "$bench_lock" javac -J-Xmx256m -J-XX:+UseG1GC --release 8 -d "$bench_inputs/treeadd/classes" "$bench_inputs"/jolden/treeadd/src/*.java
flock "$bench_lock" jar -J-Xmx128m -J-XX:+UseG1GC cf "$bench_inputs/treeadd/treeadd.jar" -C "$bench_inputs/treeadd/classes" .
python3 qilin.ucfs/benchmarks/benchmarks.py --case jolden:treeadd --jolden-jar "$bench_inputs/treeadd/treeadd.jar" --out "$bench_inputs/results/treeadd-full" > "$bench_inputs/treeadd-full.json"
python3 qilin.ucfs/benchmarks/benchmarks.py --case jolden:treeadd --jolden-jar "$bench_inputs/treeadd/treeadd.jar" --reach-only --out "$bench_inputs/results/treeadd-reach" > "$bench_inputs/treeadd-reach.json"
```

The model defaults to `artifact/benchmarks/JREs/jre1.6.0_45`; provide `--jre /path/to/model` to override explicitly. The runtime JDK is not silently used as the analysed JRE. The JAR's actual SHA-256 is recorded; the source pin in the case definition states the expected preparation revision, not a certification of an arbitrary `--jolden-jar` supplied by the caller.

## Existing DaCapo inputs

Reuse Qilin's existing `artifact/benchmarks` submodule; do not copy its binaries into this module. If absent, obtain it using the repository's submodule setup:

```sh
git submodule update --init artifact/benchmarks
python3 qilin.ucfs/benchmarks/benchmarks.py --list
python3 qilin.ucfs/benchmarks/benchmarks.py --case dacapo2006:luindex --out /absolute/results/legacy-luindex > /tmp/legacy-luindex-plan.json
python3 qilin.ucfs/benchmarks/benchmarks.py --case dacapo-bach:luindex --out /absolute/results/bach-luindex > /tmp/bach-luindex-plan.json
```

`--benchmark-root` accepts another existing checkout of those inputs. Legacy app/dependency/reflection paths, mains, and JRE versions come from `artifact/util/benchmark.py`. Bach case names come from `artifact/util/dacapobach.py`, retaining the `Harness` main, per-case dependency JAR and default/tradebeans reflection-log distinction. The Bach model used by this series is `jre1.8.0_121_debug`; an unavailable model must be supplied explicitly via `--jre`, not replaced silently. Legacy and Bach cases have separate IDs even when their application names coincide.

The planner fails on any missing app, dependencies, reflection log, or model `rt.jar`. It fingerprints all model JARs and required case files. A release patch is not inferred from a filename.

## Execute and record

Create the listed `required_output_directories` before launching: the pair writer requires an existing parent directory. Plans contain a **JSON argument vector**, the `JAVA_OPTS` environment, input hashes, selected model/solver, and the budget contract: **48 GiB heap, 52 GiB whole process, 7200 seconds FULL per case/configuration**. Treat the vector as arguments, never as a shell string. Apply the shared Heavy lock, whole-process cap (for example a dedicated cgroup with `MemoryMax=52G`, `MemorySwapMax=0`), and a 7200-second service lifetime **before executing it**. The planner itself enforces no runtime cap and launches no process.

Keep the full command/environment, input/model hashes, module/UCFS commits, GC (fixed G1 here), FULL wall time including preanalysis/export/solve/output, peak RSS and cgroup memory, physical I/O and any OOM/timeout cause. Use an external wall/RSS observer such as `/usr/bin/time -v`; the module's solve timer is not FULL. Preserve `stats.json`, the requested `points-to.tsv` (vertex-ID pairs), and result/diff files. Vertex IDs belong to one exported graph; comparing fresh JVMs requires stable node mapping, not raw integer equality. `--solver none` prepares/export-measures only; it is not a completed points-to analysis.

For a full-vs-reach comparison use identical inputs/flags, distinct output directories, and change only `--reach-only`. This selects the existing GLL recognition storage mode; source batching and newer INT modes are separate experiments. No timing/precision ranking is claimed by these setup scripts.

## Source-only checks

```sh
python3 -m unittest discover -s qilin.ucfs/benchmarks -p 'test_*.py'
```

These tests validate case registration, model/dependency paths, missing-input rejection, argument boundaries (including spaces), and full/reach plan equivalence without launching Java. Real JVM setup/build/runtime validation must be recorded separately under the shared locks.
