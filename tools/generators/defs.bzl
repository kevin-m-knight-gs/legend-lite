"""What every generator build action shares: the pinned upstream trees as inputs,
their roots as arguments, and the JVM flags that point the corpus machinery at the
action's own inputs.

A generator is a PROGRAM run as a sandboxed build action (java_run, which runs its
target-configuration jars — //tools/java_run) over declared inputs, writing one file; each package's write_generated_files (update_generated) writes the
outputs into the checkout and diff-tests the committed copies.
"""

load("@bazel_lib//lib:write_source_files.bzl", "write_source_files")
load("//tools/java_run:defs.bzl", "java_run")

# The trees as declared inputs, plus a file each release has at its root.
UPSTREAM_TREES = [
    "@legend_engine_src//:pom.xml",
    "@legend_engine_src//:tree",
    "@legend_pure_src//:pom.xml",
    "@legend_pure_src//:tree",
]

# java_run's roots: each release's root, named by the file at it, as a token the
# generator's arguments and flags use
UPSTREAM_ROOTS = {
    "@legend_engine_src//:pom.xml": "{ENGINE_ROOT}",
    "@legend_pure_src//:pom.xml": "{PURE_ROOT}",
}

def program_jvm_flags(module, engine = True, pure = True):
    """JVM flags for a generator (java_run) that reads through Repo/Upstream.

    Repo resolves against the action's working directory (the execroot, where
    declared inputs sit at their repository paths), as `module`.
    """
    flags = [
        "-Dlegend.repo.root=.",
        "-Dlegend.repo.module=" + module,
        "-Duser.timezone=GMT",
    ]
    if engine:
        flags.append("-Dlegend.engine.root={ENGINE_ROOT}")
    if pure:
        flags.append("-Dlegend.pure.root={PURE_ROOT}")
    return flags

def write_generated_files(name, files, **kwargs):
    """write_source_files over generator outputs, in this checkout's line endings.

    A generator writes '\\n' on every machine. A checkout's line endings are git's
    choice: LF on CI, CRLF in a Windows clone with core.autocrlf=true. Each output
    first passes through CheckoutLineEndings (//tools/generators), which gives it
    its committed copy's line endings by git's own rule. Only then is it written
    into the checkout or diff-tested against the committed copy.

    Args:
      name: the update target. Its diff tests are write_source_files' own,
        {name}_<n>_test.
      files: committed path in this package -> the generator target that writes it.
      **kwargs: passed to write_source_files; testonly also marks the conversions.
    """
    checked_out = {}
    for i, (committed, generated) in enumerate(files.items()):
        conversion = "%s_checkout_%d" % (name, i)
        java_run(
            name = conversion,
            testonly = kwargs.get("testonly", False),
            srcs = [":" + committed, generated],
            outs = ["%s_checkout/%s" % (name, committed)],
            arguments = [
                "$(execpath :%s)" % committed,
                "$(execpath %s)" % generated,
                "{OUT}",
            ],
            main_class = "com.legend.tools.generators.CheckoutLineEndings",
            mnemonic = "LineEndings",
            deps = ["//tools/generators:checkout_line_endings"],
        )
        checked_out[committed] = ":" + conversion
    write_source_files(name = name, files = checked_out, **kwargs)
