# Repro: unimported elements bind silently

Full writeup: `docs/NAME_RESOLUTION_BUG.md`. Audit: `docs/SIMPLE_NAME_AUDIT.md`.

A bare reference that fails import qualification is resolved by scanning the whole model for any
FQN ending in `::<name>`. A unique hit binds with no error — **including when the referring file
never imported that package.** Real Pure rejects the model; legend-lite emits SQL against the
wrong store.

## Run

```bash
export JAVA_HOME=~/jdk/jdk-21.0.11+10/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -o -q install -pl core -DskipTests          # from the repo root, if core/target is stale
CORE=core/target/classes
javac -cp "$CORE" -d /tmp experiments/name-resolution-repro/FqnBug.java
java -cp "/tmp:$CORE" FqnBug
```

## Expected today (the bug)

```
findDatabase("myDB")          -> pkg::B::myDB      <-- bound, never imported
findDatabase("pkg::A::myDB")  -> <unresolved>
```

`f2.pure` imports `pkg::A::*`, which defines nothing. `pkg::B` is never imported. Correct Pure
behaviour is a compile-time failure.

## Expected after the fix

`findDatabase("myDB")` must NOT bind `pkg::B::myDB`. This file should become a
failing-before / passing-after regression test (`docs/NAME_RESOLUTION_BUG.md` §7 step 4) so the
bug cannot be reintroduced.
