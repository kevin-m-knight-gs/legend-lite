// DATACUBE'S PURE LIBRARY (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §5.6; the user, 2026-10-26: "try as pure
// function -- doesn't have to be built in -- can be datacube pure scripts"): gs-quant's timeseries functions written in
// Pure over ANY table, which a timeseries tile's queries call by name --
// `->datacube::ts::mean(over(~symbol, ~day->ascending(), rows(-21, 0)), ~close, 22, ~mean)` -- and which a person may
// call in a query of their own. legend-lite expands each call into its
// body over the call's own columns (docs/SEMANTICS_REGISTER.md S112: legend-engine refuses a user function with type
// parameters, so a planner of legend-engine's refuses these in its own words). The database computes every figure.
//
// The library is DataCube's, never the platform's: a model takes it only where a query calls it (withLibraryFor), so a
// query that calls none is planned over its model as it is, byte for byte.

import { functionsCalled, type ValueSpecification } from '../../pure-protocol/src/index.ts';

/** The package the library's functions are in. */
export const LIBRARY_PACKAGE = 'datacube::ts';

/**
 * The library, as Pure text: a `###Pure` section a model takes after its own. Each function takes the table, a window
 * of it (its LINE -- the column the table has a line per value of: a symbol --, its ORDER, its frame) or the line and
 * the dates themselves, the column it reads (by `read`: the caller's own), its parameters and the column it writes;
 * every other column carried as it is, its working columns its own (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §5.7).
 */
export const DATACUBE_LIBRARY = `###Pure
// DataCube's timeseries functions (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §5.6, §5.7): gs-quant's, over any
// table. A function takes the table, a window -- its line, its order and its frame: over(~symbol, ~day->ascending(),
// rows(-19, 0)) -- or the line and the dates where it joins the line to itself, the column it reads (read: the caller's
// own, never renamed), its parameters and the column it writes; it declares what it answers, and its working columns
// are its own.

// The mean of the window's values, none until it holds ramp of them (gs-quant's mean, its ramp).
function datacube::ts::mean<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|$r->read($value)}:y|if($y->count() < $ramp, |[], |$y->average()))->rename(~m, $out)
}

// The sum of the window's values, none until it holds ramp of them.
function datacube::ts::sum<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|$r->read($value)}:y|if($y->count() < $ramp, |[], |$y->sum()))->rename(~m, $out)
}

// The least of the window's values, none until it holds ramp of them.
function datacube::ts::min<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|$r->read($value)}:y|if($y->count() < $ramp, |[], |$y->min()))->rename(~m, $out)
}

// The most of the window's values, none until it holds ramp of them.
function datacube::ts::max<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|$r->read($value)}:y|if($y->count() < $ramp, |[], |$y->max()))->rename(~m, $out)
}

// The most less the least of the window's values, none until it holds ramp of them.
function datacube::ts::range<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|$r->read($value)}:y|if($y->count() < $ramp, |[], |$y->max()->toOne() - $y->min()->toOne()))->rename(~m, $out)
}

// The sample standard deviation of the window's values, none until it holds ramp of them (at least two).
function datacube::ts::std<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|$r->read($value)}:y|if($y->count() < $ramp, |[], |$y->stdDevSample()))->rename(~m, $out)
}

// The sample variance of the window's values, none until it holds ramp of them (at least two).
function datacube::ts::var<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|$r->read($value)}:y|if($y->count() < $ramp, |[], |$y->varianceSample()))->rename(~m, $out)
}

// A statistic of the values a calendar period back from each date of the line, (date - n unit, date] (gs-quant's
// rolling_offset) -- 'mean', 'sum', 'min', 'max', 'range', 'std' or 'var', a word compared when the call is expanded:
// the line joined to itself on those dates, the statistic per date, joined back; none before the line's first value's
// date that period on (gs-quant's apply_ramp of a DateOffset).
function datacube::ts::overPeriod<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], n: Integer[1], unit: meta::pure::functions::date::DurationUnit[1], stat: String[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  let base = $t->extend(~[l:r|$r->read($line), d:r|$r->read($date), v:r|$r->read($value)]);
  let windows = $base->select(~[l, d])->extend(~lo:x|$x.d->toOne()->adjust(0 - $n, $unit))
    ->join($base->select(~[l, d, v])->rename(~l, ~wl)->rename(~d, ~wd)->rename(~v, ~wv), meta::pure::functions::relation::JoinKind.INNER, {a,b|($a.l == $b.wl) && ($b.wd > $a.lo) && ($b.wd <= $a.d)})
    ->groupBy(~[l, d], ~s:x|$x.wv:y|if($stat == 'mean', |$y->average(), |if($stat == 'sum', |$y->sum(), |if($stat == 'min', |$y->min(), |if($stat == 'max', |$y->max(), |if($stat == 'range', |$y->max()->toOne() - $y->min()->toOne(), |if($stat == 'std', |$y->stdDevSample(), |$y->varianceSample())))))))
    ->rename(~l, ~gl)->rename(~d, ~gd);
  $base->join($windows, meta::pure::functions::relation::JoinKind.LEFT, {a,b|($a.l == $b.gl) && ($a.d == $b.gd)})
    ->extend(over(~l, [~d->ascending()], rows(unbounded(), unbounded())), ~fd:{p,f,r|if($r.v->isNotEmpty(), |$r.d, |[])}:y|$y->min())
    ->extend(~o:x|if($x.d->toOne() < $x.fd->toOne()->adjust($n, $unit), |[], |$x.s))
    ->without(~[l, d, v, gl, gd, s, fd])
    ->rename(~o, $out);
}

// The value obs rows back (gs-quant's lag of a count): x[-obs], none before the line's obs-th row.
function datacube::ts::lag<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], obs: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|$p->lag($r, $obs)->read($value)})->rename(~m, $out)
}

// The value as of a calendar period back from each date of the line (gs-quant's lag of a period, as of): of its dates
// with a value, the latest at or before (date - n unit) -- an as-of join; none before the line's first that far back.
function datacube::ts::lagPeriod<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], n: Integer[1], unit: meta::pure::functions::date::DurationUnit[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  let base = $t->extend(~[l:r|$r->read($line), d:r|$r->read($date), v:r|$r->read($value)]);
  $base->extend(~at:x|$x.d->toOne()->adjust(0 - $n, $unit))
    ->asOfJoin($base->filter(x|$x.v->isNotEmpty())->select(~[l, d, v])->rename(~l, ~bl)->rename(~d, ~bd)->rename(~v, ~o), {a,b|$a.at >= $b.bd}, {a,b|$a.l == $b.bl})
    ->without(~[l, d, v, at, bl, bd])
    ->rename(~o, $out);
}

// The change over obs rows back (gs-quant's diff): x - x[-obs].
function datacube::ts::diff<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], obs: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|$r->read($value)->toOne() - $p->lag($r, $obs)->read($value)->toOne()})->rename(~m, $out)
}

// The change over a calendar period back (gs-quant's diff of a period): x less its value as of (date - n unit).
function datacube::ts::diffPeriod<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], n: Integer[1], unit: meta::pure::functions::date::DurationUnit[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->datacube::ts::lagPeriod($line, $date, $value, $n, $unit, $out)
    ->extend(~m:r|$r->read($value)->toOne() - $r->read($out)->toOne())
    ->without($out)->rename(~m, $out)
}

// The returns over obs rows back (gs-quant's returns), of a type -- 'simple', 'log' or 'absolute', a word compared when
// the call is expanded: x / x[-obs] - 1, none where x[-obs] is 0 or there is none; log(x) - log(x[-obs]), none where
// either is 0 or below; x - x[-obs].
function datacube::ts::returns<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], obs: Integer[1], type: String[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|if($type == 'log',
      |if(($r->read($value)->toOne() <= 0) || ($p->lag($r, $obs)->read($value)->toOne() <= 0), |[], |log($r->read($value)->toOne()) - log($p->lag($r, $obs)->read($value)->toOne())),
      |if($type == 'absolute',
        |$r->read($value)->toOne() - $p->lag($r, $obs)->read($value)->toOne(),
        |if($p->lag($r, $obs)->read($value)->toOne() == 0, |[], |$r->read($value)->toOne() / $p->lag($r, $obs)->read($value)->toOne() - 1)))})
    ->rename(~m, $out)
}

// The returns over a calendar period back (gs-quant's returns of a period), of a type as returns': each date against
// its value as of (date - n unit).
function datacube::ts::returnsPeriod<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], n: Integer[1], unit: meta::pure::functions::date::DurationUnit[1], type: String[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->datacube::ts::lagPeriod($line, $date, $value, $n, $unit, $out)
    ->extend(~m:r|if($type == 'log',
      |if(($r->read($value)->toOne() <= 0) || ($r->read($out)->toOne() <= 0), |[], |log($r->read($value)->toOne()) - log($r->read($out)->toOne())),
      |if($type == 'absolute',
        |$r->read($value)->toOne() - $r->read($out)->toOne(),
        |if($r->read($out)->toOne() == 0, |[], |$r->read($value)->toOne() / $r->read($out)->toOne() - 1))))
    ->without($out)->rename(~m, $out)
}

// The value annualized (gs-quant's annualize): times the square root of the periods a year (a column, the same down a
// line; none where it is none).
function datacube::ts::annualize<T,V,Y,O>(t: Relation<T>[1], value: ColSpec<V⊆T>[1], perYear: ColSpec<Y⊆T>[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend(~m:r|$r->read($value)->toOne() * sqrt($r->read($perYear)->toOne()))->rename(~m, $out)
}

// The line's first value beside each of its rows (gs-quant's first, on every date): of its dates with a value, the
// first's.
function datacube::ts::first<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend(~[l:r|$r->read($line), d:r|$r->read($date), v:r|$r->read($value)])
    ->extend(over(~l), ~at:{p,f,r|if($r.v->isNotEmpty(), |$r.d, |[])}:y|$y->min())
    ->extend(over(~l), ~o:{p,f,r|if($r.d == $r.at, |$r.v, |[])}:y|$y->max())
    ->without(~[l, d, v, at])->rename(~o, $out)
}

// The line's last value beside each of its rows (gs-quant's last, on every date): of its dates with a value, the
// last's.
function datacube::ts::last<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend(~[l:r|$r->read($line), d:r|$r->read($date), v:r|$r->read($value)])
    ->extend(over(~l), ~at:{p,f,r|if($r.v->isNotEmpty(), |$r.d, |[])}:y|$y->max())
    ->extend(over(~l), ~o:{p,f,r|if($r.d == $r.at, |$r.v, |[])}:y|$y->max())
    ->without(~[l, d, v, at])->rename(~o, $out)
}

// The line's value as of a date beside each of its rows: of its dates with a value, the latest at or before from -- or,
// none at or before it, the first (a base the first date a view shows sets).
function datacube::ts::valueAt<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], from: Date[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend(~[l:r|$r->read($line), d:r|$r->read($date), v:r|$r->read($value)])
    ->extend(over(~l), ~fst:{p,f,r|if($r.v->isNotEmpty(), |$r.d, |[])}:y|$y->min())
    ->extend(over(~l), ~bef:{p,f,r|if($r.v->isNotEmpty() && ($r.d->toOne() <= $from), |$r.d, |[])}:y|$y->max())
    ->extend(~at:x|if($x.bef->isEmpty(), |$x.fst, |$x.bef))
    ->extend(over(~l), ~o:{p,f,r|if($r.d == $r.at, |$r.v, |[])}:y|$y->max())
    ->without(~[l, d, v, fst, bef, at])->rename(~o, $out)
}

// The value as a multiple of the line's first, times initial (gs-quant's index): none where the first is 0.
function datacube::ts::index<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], initial: Number[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->datacube::ts::first($line, $date, $value, $out)
    ->extend(~m:r|if($r->read($out)->toOne() == 0, |[], |$r->read($value)->toOne() / $r->read($out)->toOne() * $initial))
    ->without($out)->rename(~m, $out)
}

// The value as a multiple of the line's as of a date (valueAt's), times initial: none where that is 0.
function datacube::ts::indexFrom<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], from: Date[1], initial: Number[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->datacube::ts::valueAt($line, $date, $value, $from, $out)
    ->extend(~m:r|if($r->read($out)->toOne() == 0, |[], |$r->read($value)->toOne() / $r->read($out)->toOne() * $initial))
    ->without($out)->rename(~m, $out)
}

// The value less the line's first (gs-quant's change).
function datacube::ts::change<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->datacube::ts::first($line, $date, $value, $out)
    ->extend(~m:r|$r->read($value)->toOne() - $r->read($out)->toOne())
    ->without($out)->rename(~m, $out)
}

// The value less the line's as of a date (valueAt's).
function datacube::ts::changeFrom<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], from: Date[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->datacube::ts::valueAt($line, $date, $value, $from, $out)
    ->extend(~m:r|$r->read($value)->toOne() - $r->read($out)->toOne())
    ->without($out)->rename(~m, $out)
}

// The value over the line's first, less 1: none where the first is 0.
function datacube::ts::percentChange<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->datacube::ts::first($line, $date, $value, $out)
    ->extend(~m:r|if($r->read($out)->toOne() == 0, |[], |$r->read($value)->toOne() / $r->read($out)->toOne() - 1))
    ->without($out)->rename(~m, $out)
}

// The value over the line's as of a date (valueAt's), less 1: none where that is 0.
function datacube::ts::percentChangeFrom<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], from: Date[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->datacube::ts::valueAt($line, $date, $value, $from, $out)
    ->extend(~m:r|if($r->read($out)->toOne() == 0, |[], |$r->read($value)->toOne() / $r->read($out)->toOne() - 1))
    ->without($out)->rename(~m, $out)
}

// The annualized volatility of a column of returns, in percent (gs-quant's volatility, its returns the caller's: one of
// the returns above): the sample standard deviation of the window's, times the square root of the periods a year (a
// column, the same down a line), times 100 -- none until the window holds ramp of them (at least two).
function datacube::ts::volatility<T,V,Y,O>(t: Relation<T>[1], win: _Window<T>[1], returns: ColSpec<V⊆T>[1], ramp: Integer[1], perYear: ColSpec<Y⊆T>[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~m:{p,f,r|$r->read($returns)->toOne() * sqrt($r->read($perYear)->toOne()) * 100}:y|if($y->count() < $ramp, |[], |$y->stdDevSample()))->rename(~m, $out)
}

// The z-score of each value against its window's mean and sample deviation, none until the window holds ramp of them
// or where the deviation is 0 (gs-quant's zscores).
function datacube::ts::zscores<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~[m:{p,f,r|$r->read($value)}:y|$y->average(), s:{p,f,r|$r->read($value)}:y|$y->stdDevSample(), n:{p,f,r|$r->read($value)}:y|$y->count()])
    ->extend(~z:x|if(($x.n->toOne() < $ramp) || ($x.s->toOne() == 0), |[], |($x->read($value)->toOne() - $x.m->toOne()) / $x.s->toOne()))
    ->without(~[m, s, n])->rename(~z, $out)
}

// The z-score against a calendar period back of each date (overPeriod's mean and deviation), none where the deviation
// is 0, or before the line's first value's date that period on.
function datacube::ts::zscoresPeriod<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], n: Integer[1], unit: meta::pure::functions::date::DurationUnit[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->datacube::ts::overPeriod($line, $date, $value, $n, $unit, 'mean', ~m)->datacube::ts::overPeriod($line, $date, $value, $n, $unit, 'std', ~s)
    ->extend(~z:x|if($x.s->toOne() == 0, |[], |($x->read($value)->toOne() - $x.m->toOne()) / $x.s->toOne()))
    ->without(~[m, s])->rename(~z, $out)
}

// Each value held within limit sample deviations of its window's mean (gs-quant's winsorize).
function datacube::ts::winsorize<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], limit: Number[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~[m:{p,f,r|$r->read($value)}:y|$y->average(), s:{p,f,r|$r->read($value)}:y|$y->stdDevSample()])
    ->extend(~[hi:x|$x.m->toOne() + $limit * $x.s->toOne(), lo:x|$x.m->toOne() - $limit * $x.s->toOne()])
    ->extend(~o:x|if($x->read($value)->toOne() > $x.hi->toOne(), |$x.hi, |if($x->read($value)->toOne() < $x.lo->toOne(), |$x.lo, |$x->read($value))))
    ->without(~[m, s, hi, lo])->rename(~o, $out)
}

// The fall from the window's peak, x / peak - 1, none until the window holds ramp values or where the peak is 0
// (gs-quant's drawdown).
function datacube::ts::drawdown<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~[pk:{p,f,r|$r->read($value)}:y|$y->max(), n:{p,f,r|$r->read($value)}:y|$y->count()])
    ->extend(~o:x|if(($x.n->toOne() < $ramp) || ($x.pk->toOne() == 0), |[], |$x->read($value)->toOne() / $x.pk->toOne() - 1))
    ->without(~[pk, n])->rename(~o, $out)
}

// The fall from the peak of a calendar period back of each date (overPeriod's max), none where the peak is 0, or before
// the line's first value's date that period on.
function datacube::ts::drawdownPeriod<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], n: Integer[1], unit: meta::pure::functions::date::DurationUnit[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->datacube::ts::overPeriod($line, $date, $value, $n, $unit, 'max', ~pk)
    ->extend(~o:x|if($x.pk->toOne() == 0, |[], |$x->read($value)->toOne() / $x.pk->toOne() - 1))
    ->without(~pk)->rename(~o, $out)
}

// The deepest drawdown within the window, each against the window's peak: none until the window holds ramp values
// (gs-quant's max_drawdown).
function datacube::ts::maxDrawdown<T,V,O>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~pk:{p,f,r|$r->read($value)}:y|$y->max())
    ->extend(~dd:x|if($x.pk->toOne() == 0, |[], |$x->read($value)->toOne() / $x.pk->toOne() - 1))
    ->extend($win, ~[md:{p,f,r|$r.dd}:y|$y->min(), n:{p,f,r|$r->read($value)}:y|$y->count()])
    ->extend(~o:x|if($x.n->toOne() < $ramp, |[], |$x.md))
    ->without(~[pk, dd, md, n])->rename(~o, $out)
}

// The annualized Sharpe ratio of a column of returns over the window: their mean over their sample deviation, times the
// square root of the periods a year (a column), none until the window holds ramp of them or where the deviation is 0.
function datacube::ts::sharpeRatio<T,V,Y,O>(t: Relation<T>[1], win: _Window<T>[1], returns: ColSpec<V⊆T>[1], ramp: Integer[1], perYear: ColSpec<Y⊆T>[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $t->extend($win, ~[m:{p,f,r|$r->read($returns)}:y|$y->average(), s:{p,f,r|$r->read($returns)}:y|$y->stdDevSample(), n:{p,f,r|$r->read($returns)}:y|$y->count()])
    ->extend(~o:x|if(($x.n->toOne() < $ramp) || ($x.s->toOne() == 0), |[], |$x.m->toOne() / $x.s->toOne() * sqrt($x->read($perYear)->toOne())))
    ->without(~[m, s, n])->rename(~o, $out)
}

// Bands k sample deviations either side of the window's mean, and their width (gs-quant's bollinger_bands): none until
// the window holds ramp values, and two.
function datacube::ts::bollingerBands<T,V,O1,O2,O3>(t: Relation<T>[1], win: _Window<T>[1], value: ColSpec<V⊆T>[1], ramp: Integer[1], k: Number[1], lower: ColSpec<O1>[1], upper: ColSpec<O2>[1], width: ColSpec<O3>[1]): Relation<T+O1+O2+O3>[1]
{
  $t->extend($win, ~[m:{p,f,r|$r->read($value)}:y|$y->average(), s:{p,f,r|$r->read($value)}:y|$y->stdDevSample(), n:{p,f,r|$r->read($value)}:y|$y->count()])
    ->extend(~[lo:x|if(($x.n->toOne() < $ramp) || ($x.n->toOne() < 2), |[], |$x.m->toOne() - $k * $x.s->toOne()), hi:x|if(($x.n->toOne() < $ramp) || ($x.n->toOne() < 2), |[], |$x.m->toOne() + $k * $x.s->toOne())])
    ->extend(~wd:x|$x.hi->toOne() - $x.lo->toOne())
    ->without(~[m, s, n])->rename(~lo, $lower)->rename(~hi, $upper)->rename(~wd, $width)
}

// Bollinger bands of a calendar period back of each date (overPeriod's mean and deviation): none before the line's first
// value's date that period on.
function datacube::ts::bollingerBandsPeriod<T,L,D,V,O1,O2,O3>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], n: Integer[1], unit: meta::pure::functions::date::DurationUnit[1], k: Number[1], lower: ColSpec<O1>[1], upper: ColSpec<O2>[1], width: ColSpec<O3>[1]): Relation<T+O1+O2+O3>[1]
{
  $t->datacube::ts::overPeriod($line, $date, $value, $n, $unit, 'mean', ~m)->datacube::ts::overPeriod($line, $date, $value, $n, $unit, 'std', ~s)
    ->extend(~[lo:x|$x.m->toOne() - $k * $x.s->toOne(), hi:x|$x.m->toOne() + $k * $x.s->toOne()])
    ->extend(~wd:x|$x.hi->toOne() - $x.lo->toOne())
    ->without(~[m, s])->rename(~lo, $lower)->rename(~hi, $upper)->rename(~wd, $width)
}

// The percentile rank of each value within its window (gs-quant's percentiles: scipy's percentileofscore, kind mean):
// the share of the window's values below it, those equal to it counted half, in percent. The window is the w rows to it
// (0: every row from the line's first), the line joined to itself on them; none until it holds w values.
function datacube::ts::percentiles<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], w: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  let base = $t->extend(~[l:r|$r->read($line), d:r|$r->read($date), v:r|$r->read($value)])
    ->extend(over(~l, [~d->ascending()]), ~rn:{p,f,r|$p->rowNumber($r)});
  let pairs = $base->select(~[l, rn, v])
    ->join($base->select(~[l, rn, v])->rename(~l, ~ol)->rename(~rn, ~orn)->rename(~v, ~ov), meta::pure::functions::relation::JoinKind.INNER,
      {a,b|($a.l == $b.ol) && ($b.orn <= $a.rn) && (($w == 0) || ($b.orn > ($a.rn - $w)))})
    ->groupBy(~[l, rn], ~[lt:x|if($x.ov->toOne() < $x.v->toOne(), |1, |[]):y|$y->count(), le:x|if($x.ov->toOne() <= $x.v->toOne(), |1, |[]):y|$y->count(), c:x|$x.ov:y|$y->count()])
    ->rename(~l, ~gl)->rename(~rn, ~grn);
  $base->join($pairs, meta::pure::functions::relation::JoinKind.LEFT, {a,b|($a.l == $b.gl) && ($a.rn == $b.grn)})
    ->extend(~o:x|if($x.v->isEmpty() || ($x.c->toOne() == 0) || (($w > 0) && ($x.c->toOne() < $w)), |[], |($x.lt->toOne() + $x.le->toOne()) / (2 * $x.c->toOne()) * 100))
    ->without(~[l, d, v, rn, gl, grn, lt, le, c])->rename(~o, $out);
}

// The percentile rank of each value among those a calendar period back of its date, (date - n unit, date]: none before
// the line's first value's date that period on.
function datacube::ts::percentilesPeriod<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], n: Integer[1], unit: meta::pure::functions::date::DurationUnit[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  let base = $t->extend(~[l:r|$r->read($line), d:r|$r->read($date), v:r|$r->read($value)]);
  let pairs = $base->select(~[l, d, v])->extend(~lo:x|$x.d->toOne()->adjust(0 - $n, $unit))
    ->join($base->select(~[l, d, v])->rename(~l, ~ol)->rename(~d, ~od)->rename(~v, ~ov), meta::pure::functions::relation::JoinKind.INNER,
      {a,b|($a.l == $b.ol) && ($b.od > $a.lo) && ($b.od <= $a.d)})
    ->groupBy(~[l, d], ~[lt:x|if($x.ov->toOne() < $x.v->toOne(), |1, |[]):y|$y->count(), le:x|if($x.ov->toOne() <= $x.v->toOne(), |1, |[]):y|$y->count(), c:x|$x.ov:y|$y->count()])
    ->rename(~l, ~gl)->rename(~d, ~gd);
  $base->join($pairs, meta::pure::functions::relation::JoinKind.LEFT, {a,b|($a.l == $b.gl) && ($a.d == $b.gd)})
    ->extend(over(~l), ~fd:{p,f,r|if($r.v->isNotEmpty(), |$r.d, |[])}:y|$y->min())
    ->extend(~o:x|if($x.v->isEmpty() || ($x.c->toOne() == 0) || ($x.d->toOne() < $x.fd->toOne()->adjust($n, $unit)), |[], |($x.lt->toOne() + $x.le->toOne()) / (2 * $x.c->toOne()) * 100))
    ->without(~[l, d, v, gl, gd, lt, le, c, fd])->rename(~o, $out);
}

// The q-th quantile of the window's values, continuous (pandas' quantile: between the two nearest; a median the 0.5th).
// The window is the w rows to it (0: every row from the line's first), the line joined to itself on them; none until it
// holds w values.
function datacube::ts::quantile<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], w: Integer[1], q: Float[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  let base = $t->extend(~[l:r|$r->read($line), d:r|$r->read($date), v:r|$r->read($value)])
    ->extend(over(~l, [~d->ascending()]), ~rn:{p,f,r|$p->rowNumber($r)});
  let pairs = $base->select(~[l, rn])
    ->join($base->select(~[l, rn, v])->rename(~l, ~ol)->rename(~rn, ~orn)->rename(~v, ~ov), meta::pure::functions::relation::JoinKind.INNER,
      {a,b|($a.l == $b.ol) && ($b.orn <= $a.rn) && (($w == 0) || ($b.orn > ($a.rn - $w)))})
    ->groupBy(~[l, rn], ~[qv:x|$x.ov:y|$y->percentile($q, true, true), c:x|$x.ov:y|$y->count()])
    ->rename(~l, ~gl)->rename(~rn, ~grn);
  $base->join($pairs, meta::pure::functions::relation::JoinKind.LEFT, {a,b|($a.l == $b.gl) && ($a.rn == $b.grn)})
    ->extend(~o:x|if(($w > 0) && ($x.c->toOne() < $w), |[], |$x.qv))
    ->without(~[l, d, v, rn, gl, grn, qv, c])->rename(~o, $out);
}

// The q-th quantile of the values a calendar period back of each date, (date - n unit, date]: none before the line's
// first value's date that period on.
function datacube::ts::quantilePeriod<T,L,D,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], date: ColSpec<D⊆T>[1], value: ColSpec<V⊆T>[1], n: Integer[1], unit: meta::pure::functions::date::DurationUnit[1], q: Float[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  let base = $t->extend(~[l:r|$r->read($line), d:r|$r->read($date), v:r|$r->read($value)]);
  let pairs = $base->select(~[l, d])->extend(~lo:x|$x.d->toOne()->adjust(0 - $n, $unit))
    ->join($base->select(~[l, d, v])->rename(~l, ~ol)->rename(~d, ~od)->rename(~v, ~ov), meta::pure::functions::relation::JoinKind.INNER,
      {a,b|($a.l == $b.ol) && ($b.od > $a.lo) && ($b.od <= $a.d)})
    ->groupBy(~[l, d], ~qv:x|$x.ov:y|$y->percentile($q, true, true))
    ->rename(~l, ~gl)->rename(~d, ~gd);
  $base->join($pairs, meta::pure::functions::relation::JoinKind.LEFT, {a,b|($a.l == $b.gl) && ($a.d == $b.gd)})
    ->extend(over(~l), ~fd:{p,f,r|if($r.v->isNotEmpty(), |$r.d, |[])}:y|$y->min())
    ->extend(~o:x|if($x.d->toOne() < $x.fd->toOne()->adjust($n, $unit), |[], |$x.qv))
    ->without(~[l, d, v, gl, gd, qv, fd])->rename(~o, $out);
}

// The q-th quantile of the line's every value, beside each of its rows: one aggregate a line, grouped by it and joined
// back.
function datacube::ts::quantileOfLine<T,L,V,O>(t: Relation<T>[1], line: ColSpec<L⊆T>[1], value: ColSpec<V⊆T>[1], q: Float[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  let base = $t->extend(~[l:r|$r->read($line), v:r|$r->read($value)]);
  $base->join($base->groupBy(~[l], ~o:x|$x.v:y|$y->percentile($q, true, true))->rename(~l, ~gl), meta::pure::functions::relation::JoinKind.LEFT, {a,b|$a.l == $b.gl})
    ->without(~[l, v, gl])->rename(~o, $out);
}
`;

/** Whether a query calls a function of the library. */
export function callsLibrary(query: ValueSpecification): boolean {
  for (const f of functionsCalled(query)) if (f.startsWith(`${LIBRARY_PACKAGE}::`)) return true;
  return false;
}

/** A model with the library after it: what a query that calls the library is planned over. */
export function withLibrary(model: string): string {
  return `${model}\n${DATACUBE_LIBRARY}`;
}
