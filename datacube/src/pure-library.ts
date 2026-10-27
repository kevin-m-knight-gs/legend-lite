// DATACUBE'S PURE LIBRARY (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §5.6; the user, 2026-10-26: "try as pure
// function -- doesn't have to be built in -- can be datacube pure scripts"): gs-quant's timeseries functions written in
// Pure over ANY table, which a timeseries tile's queries call by name -- `->datacube::ts::mean(~symbol, [~day->ascending()],
// ~close, 22, ~mean)` -- and which a person may call in a query of their own. legend-lite expands each call into its
// body over the call's own columns (docs/SEMANTICS_REGISTER.md S104: legend-engine refuses a user function with type
// parameters, so a planner of legend-engine's refuses these in its own words). The database computes every figure.
//
// The library is DataCube's, never the platform's: a model takes it only where a query calls it (withLibraryFor), so a
// query that calls none is planned over its model as it is, byte for byte.

import { functionsCalled, type ValueSpecification } from '../../pure-protocol/src/index.ts';

/** The package the library's functions are in. */
export const LIBRARY_PACKAGE = 'datacube::ts';

/**
 * The library, as Pure text: a `###Pure` section a model takes after its own. Each function takes the table, its LINE
 * (the column the table has a line per value of: a symbol), its ORDER (its dates, ascending), the column it reads and
 * the column it writes, every other column carried as it is. A function reads its column under a name of its own
 * (`ts_value`) and writes its figure under another (`ts_out`), each renamed back to the call's: so the column it reads
 * is neither the line nor the order, and the table has neither name.
 */
export const DATACUBE_LIBRARY = `###Pure
// DataCube's timeseries functions (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §5.6): gs-quant's, over any table.

// The mean of the last w values (w at least 1), none until there are w (gs-quant's mean, its ramp).
function datacube::ts::mean<T,L,D,V,O>(table: Relation<T>[1], line: ColSpec<L⊆T>[1], order: SortInfo<D⊆T>[*], value: ColSpec<V⊆T>[1], w: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $table->rename($value, ~ts_value)
    ->extend(over($line, $order, rows(1 - $w, 0)), ~ts_out:{p,f,r|$r.ts_value}:y|if($y->count() < $w, |[], |$y->average()))
    ->rename(~ts_value, $value)
    ->rename(~ts_out, $out)
}

// The simple returns over obs values back: x / x[-obs] - 1, none where x[-obs] is 0 or there is none.
function datacube::ts::simpleReturns<T,L,D,V,O>(table: Relation<T>[1], line: ColSpec<L⊆T>[1], order: SortInfo<D⊆T>[*], value: ColSpec<V⊆T>[1], obs: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $table->rename($value, ~ts_value)
    ->extend(over($line, $order), ~ts_out:{p,f,r|if($p->lag($r, $obs).ts_value->toOne() == 0, |[], |$r.ts_value->toOne() / $p->lag($r, $obs).ts_value->toOne() - 1)})
    ->rename(~ts_value, $value)
    ->rename(~ts_out, $out)
}

// The log returns over obs values back: log(x) - log(x[-obs]), none where either is 0 or below.
function datacube::ts::logReturns<T,L,D,V,O>(table: Relation<T>[1], line: ColSpec<L⊆T>[1], order: SortInfo<D⊆T>[*], value: ColSpec<V⊆T>[1], obs: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $table->rename($value, ~ts_value)
    ->extend(over($line, $order), ~ts_out:{p,f,r|if(($r.ts_value->toOne() <= 0) || ($p->lag($r, $obs).ts_value->toOne() <= 0), |[], |log($r.ts_value->toOne()) - log($p->lag($r, $obs).ts_value->toOne()))})
    ->rename(~ts_value, $value)
    ->rename(~ts_out, $out)
}

// The absolute returns over obs values back: x - x[-obs].
function datacube::ts::absoluteReturns<T,L,D,V,O>(table: Relation<T>[1], line: ColSpec<L⊆T>[1], order: SortInfo<D⊆T>[*], value: ColSpec<V⊆T>[1], obs: Integer[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $table->rename($value, ~ts_value)
    ->extend(over($line, $order), ~ts_out:{p,f,r|$r.ts_value->toOne() - $p->lag($r, $obs).ts_value->toOne()})
    ->rename(~ts_value, $value)
    ->rename(~ts_out, $out)
}

// The annualized volatility of a column of returns, in percent (gs-quant's volatility, its returns the caller's: one of
// the returns above): the sample standard deviation of the last w, times the square root of the periods a year (a column,
// the same down a line), times 100 -- none until there are w and at least two.
function datacube::ts::volatility<T,L,D,V,Y,O>(table: Relation<T>[1], line: ColSpec<L⊆T>[1], order: SortInfo<D⊆T>[*], returns: ColSpec<V⊆T>[1], w: Integer[1], perYear: ColSpec<Y⊆T>[1], out: ColSpec<O>[1]): Relation<T+O>[1]
{
  $table->rename($returns, ~ts_value)
    ->rename($perYear, ~ts_per_year)
    ->extend(over($line, $order, rows(1 - $w, 0)), ~ts_out:{p,f,r|$r.ts_value->toOne() * sqrt($r.ts_per_year->toOne()) * 100}:y|if(($y->count() < $w) || ($y->count() < 2), |[], |$y->stdDevSample()))
    ->rename(~ts_per_year, $perYear)
    ->rename(~ts_value, $returns)
    ->rename(~ts_out, $out)
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
