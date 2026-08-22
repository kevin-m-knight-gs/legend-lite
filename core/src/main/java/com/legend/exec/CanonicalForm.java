// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * R0's SCALAR-channel canonical render over wire values
 * (docs/CANONICAL_FORM_SPEC.md §2) — the HOST reference implementation.
 * Charter (R1): this is the referee's half of the byte channel — the
 * divergence instrument computes {@code render(e) == render(a)} NEXT TO
 * {@code PureAsserts.equal(e, a)} across the full corpus; R2's cutovers
 * move the render of record into SQL (Render), per family, against this
 * spec. It is NOT a verdict path: production verdicts never call it.
 *
 * <p>Every rule here cites an H-table row of
 * docs/CANONICAL_RENDER_HOMEWORK.md; a value outside the claimed domain
 * (§4: non-finite floats, -0.0, unmodeled kinds) returns
 * {@link Result.Residue}, never a guessed spelling.
 */
public final class CanonicalForm {

    private CanonicalForm() {
    }

    /** A render outcome: canonical text, or a named residue (out of the
     * byte channel's claimed domain). */
    public sealed interface Result {
        record Text(String value) implements Result {
        }

        record Residue(String reason) implements Result {
        }
    }

    /** Canonical text of one scalar wire value (spec §2). */
    public static Result render(@com.legend.Nullable Object v) {
        return switch (v) {
            case null -> new Result.Residue("null-scalar");
            // Integer: bare decimal, no decoration (H1)
            case Byte b -> new Result.Text(String.valueOf(b));
            case Short s -> new Result.Text(String.valueOf(s));
            case Integer i -> new Result.Text(String.valueOf(i));
            case Long l -> new Result.Text(String.valueOf(l));
            case BigInteger bi -> new Result.Text(bi.toString());
            case Boolean b -> new Result.Text(b.toString());
            // String: verbatim bytes (H1; toRepresentation quoting is a
            // DIFFERENT channel). The temporal string-carrier bridge is
            // the PROBE's pairwise concern, not this per-value rule.
            case String s -> new Result.Text(s);
            case Float f -> renderFloat(f.doubleValue());
            case Double d -> renderFloat(d);
            // Decimal: SCALE-NORMALIZED; integral renders BARE (no .0),
            // matching Integer — forced by pure's numeric equality
            // (spec §2/§3, assertEq(8D, toDecimal(8)) pin)
            case BigDecimal bd ->
                    new Result.Text(bd.stripTrailingZeros().toPlainString());
            // TEMPORAL CARRIERS: java.time ONLY — java.sql kinds are a
            // FETCH-SEAM leak (user directive 2026-08-21: sql types
            // never escape the fetch); if one ever reaches an assert it
            // reports as unmodeled-kind residue = an egress bug, never
            // an arm here. Measured: zero in 1,555 probes.
            case LocalDate ld -> new Result.Text(ld.toString());
            case LocalDateTime ldt -> new Result.Text(renderDateTime(ldt));
            case OffsetDateTime odt -> new Result.Text(renderDateTime(
                    odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()));
            default -> new Result.Residue(
                    "unmodeled-kind:" + v.getClass().getSimpleName());
        };
    }

    /** A collection side: one element renders as the scalar; otherwise
     * pure's list form {@code [a, b, c]} (H1 testListToString). Empty
     * renders {@code []}. Any element residue poisons the side. */
    public static Result renderSide(List<Object> side) {
        if (side.size() == 1) {
            return render(side.get(0));
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < side.size(); i++) {
            switch (render(side.get(i))) {
                case Result.Text t -> sb.append(i > 0 ? ", " : "")
                        .append(t.value());
                case Result.Residue r -> {
                    return r;
                }
            }
        }
        return new Result.Text(sb.append(']').toString());
    }

    /**
     * Float: fixed-point ALWAYS (pure never prints exponent notation —
     * H1 testFloatToString×4; DuckDB's bare CAST diverges on small
     * magnitudes, the R1 finding). {@code BigDecimal.valueOf} rides
     * {@code Double.toString}'s shortest-representation, which already
     * collapses trailing zeros and keeps {@code .0} on integral floats;
     * {@code toPlainString} removes the exponent. Non-finite and -0.0
     * are OUT of the claimed domain (spec §4 — ZERO witnesses, H6).
     */
    private static Result renderFloat(double d) {
        if (!Double.isFinite(d)) {
            return new Result.Residue("non-finite-float");
        }
        if (d == 0.0 && Double.doubleToRawLongBits(d) != 0L) {
            return new Result.Residue("negative-zero");
        }
        String plain = BigDecimal.valueOf(d).toPlainString();
        // integral doubles keep .0 (Double.toString gives "17.0");
        // BigDecimal.valueOf(17.0) has scale 1 so toPlainString keeps it
        return new Result.Text(plain.contains(".") ? plain : plain + ".0");
    }

    /**
     * DateTime scalar form: {@code yyyy-MM-ddTHH:mm:ss[.f+]+0000},
     * UTC-normalized (H1). WIRE LIMIT, measured not hidden: pure
     * preserves WRITTEN subsecond precision ({@code .000} ≠ {@code .0}),
     * but the wire's LocalDateTime carrier cannot carry written
     * precision — this render emits minimal precision (subseconds only
     * when nonzero, trailing zeros stripped). Where that loses a
     * distinction the divergence census shows it as a row; the census
     * decides whether a precision-carrying wire type is needed.
     */
    private static String renderDateTime(LocalDateTime ldt) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                ldt.getYear(), ldt.getMonthValue(), ldt.getDayOfMonth(),
                ldt.getHour(), ldt.getMinute(), ldt.getSecond()));
        int nanos = ldt.getNano();
        if (nanos != 0) {
            String frac = String.format("%09d", nanos)
                    .replaceFirst("0+$", "");
            sb.append('.').append(frac);
        }
        return sb.append("+0000").toString();
    }
}
