/**
 * THE RUNTIME (step K): a plan and a session to typed rows. The plan runner sends a plan's statements on a session's
 * statements ({@link com.legend.exec.PlanRunner}, the one caller of {@link com.legend.exec.Statements}), the session
 * seam opens, checks and closes connections ({@link com.legend.exec.Sessions}, the one maker of sessions since D70,
 * 2026-10-30), and a row is decoded by the plan's column ({@link com.legend.exec.JdbcDecode}); every value leaves as
 * text at the result boundary ({@code com.legend.result}), never here. The judges (the host lattice, the grid compare,
 * the canonical instruments, the referee, the judges' collected side {@code com.legend.judge.TypedSide}) and the
 * schema probes lived here until D71 (2026-10-30, the seam-and-wall program's B1): they are planning-side work
 * ({@code com.legend.judge}, the root), and exec's remaining reaches into planning are a shrinking register
 * ({@code ArchitectureTest.EXEC_PLANNING_REFERENCES}), B2's to cut to the plan records alone.
 */
package com.legend.exec;
