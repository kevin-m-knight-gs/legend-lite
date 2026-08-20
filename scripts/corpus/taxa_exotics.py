"""Twelve more reference-data taxonomies: the exotic and structured end of the book.

The corpus's 33 existing taxonomies cover the flow business and the operational functions
around it. What they do not cover is the STRUCTURED end -- the payoffs a derivatives desk
prices with a model rather than a formula, the tranches a securitisation is cut into, the
capital instruments a treasury issues, and the ways a settlement goes wrong.

The type lists are the content and are written out one by one. Everything else -- the table,
the filters, the classes, the mapping, the services -- is refdata.py's job, and is the same
five edits every taxonomy needs.

Each taxonomy seeds two rows in three, so a third of the subtypes have no rows at all. That
is the normal state of reference data, and the sharpest test of a ~filter: an empty result is
only correct if the filter works, and the failure mode is returning the whole table.
"""
import refdata

# (package, TABLE, base class, tag, file index, doc, [(CODE, ClassName, one-line doc), ...])
TAXA = [
    ("eqexotics", "EQ_EXOTIC_PAYOFF", "EquityExoticPayoff", "Eqx", 38,
     "Exotic equity payoffs: what the note actually pays, and on what.",
     [("AUTOCALLABLE", "AutocallableNote", "Redeems early if the underlying is above a barrier on an observation date."),
      ("WORST_OF_BASKET", "WorstOfBasketOption", "Pays on the weakest name in the basket, which is why it is cheap."),
      ("BEST_OF_BASKET", "BestOfBasketOption", "Pays on the strongest name, and costs accordingly."),
      ("RAINBOW", "RainbowOption", "Ranked payoff across several underlyings at once."),
      ("CLIQUET", "CliquetOption", "A series of forward-starting options, each struck at the last reset."),
      ("HIMALAYA", "HimalayaOption", "Locks in the best performer at each observation and removes it from the basket."),
      ("LOOKBACK_FIXED", "FixedStrikeLookback", "Pays against the best price reached over the life."),
      ("LOOKBACK_FLOAT", "FloatingStrikeLookback", "Struck at the best price reached, so it always finishes in the money."),
      ("ASIAN_ARITHMETIC", "ArithmeticAsianOption", "Averages the underlying, which kills the gamma and the price with it."),
      ("BARRIER_UP_OUT", "UpAndOutBarrier", "Dies if the underlying trades above the barrier."),
      ("BARRIER_DOWN_IN", "DownAndInBarrier", "Does not exist until the underlying trades below the barrier."),
      ("DIGITAL_CASH", "CashOrNothingDigital", "Pays a fixed amount or nothing, with a discontinuity at the strike."),
      ("VARIANCE_SWAP", "VarianceSwap", "Pays realised variance against a strike, with no directional exposure at all."),
      ("CORRIDOR_VARIANCE", "CorridorVarianceSwap", "Variance, but only accrued while the underlying is inside a range."),
      ("DISPERSION", "DispersionTrade", "Index variance against the sum of its constituents' variance."),
      ("CPPI", "CppiStructure", "Rebalances between risky and riskless to protect a floor.")]),

    ("fxexotics", "FX_EXOTIC_STRUCTURE", "FxExoticStructure", "Fxx", 39,
     "FX exotics: barriers, baskets and the accumulators that keep ending up in court.",
     [("ONE_TOUCH", "OneTouchOption", "Pays if the barrier is touched at any point before expiry."),
      ("NO_TOUCH", "NoTouchOption", "Pays if the barrier is never touched, which is the same bet the other way."),
      ("DOUBLE_NO_TOUCH", "DoubleNoTouchOption", "Two barriers, neither touched. A pure range bet."),
      ("KNOCK_IN_REVERSE", "ReverseKnockIn", "Knocks in on a move that takes it further into the money."),
      ("WINDOW_BARRIER", "WindowBarrierOption", "The barrier is only live between two dates."),
      ("PARTIAL_BARRIER", "PartialBarrierOption", "Monitored on a subset of the life rather than continuously."),
      ("TARGET_REDEMPTION", "TargetRedemptionForward", "Redeems once accumulated profit reaches a target, and not before."),
      ("PIVOT_TARF", "PivotTargetRedemption", "A TARF with a second strike beyond a pivot level."),
      ("ACCUMULATOR", "AccumulatorForward", "Accumulates daily at a discount and doubles up against you below the barrier."),
      ("DECUMULATOR", "DecumulatorForward", "The seller's side of the same structure."),
      ("SEAGULL", "SeagullStructure", "A collar funded by selling a further out-of-the-money option."),
      ("PARTICIPATING_FWD", "ParticipatingForward", "A forward that keeps some upside, paid for with a worse rate."),
      ("QUANTO_OPTION", "QuantoOption", "Pays in a currency other than the underlying's, at a fixed rate."),
      ("COMPOUND_OPTION", "CompoundOption", "An option on an option, and two volatilities to get wrong."),
      ("BASKET_FX", "FxBasketOption", "One option over a weighted basket of pairs."),
      ("VOLATILITY_SWAP", "FxVolatilitySwap", "Realised volatility against a strike, linear where a variance swap is not.")]),

    ("ratesexotics", "RATES_EXOTIC", "RatesExoticTrade", "Rtx", 40,
     "Interest rate exotics: the callable, the capped and the convexity-adjusted.",
     [("BERMUDAN_SWAPTION", "BermudanSwaption", "Callable on a schedule of dates rather than one."),
      ("CANCELLABLE_SWAP", "CancellableSwap", "A swap with an embedded option to terminate."),
      ("CMS_SWAP", "ConstantMaturitySwap", "Pays a swap rate rather than a Libor-style fixing, and needs a convexity adjustment."),
      ("CMS_SPREAD", "CmsSpreadOption", "The slope of the curve as a payoff, and the correlation trade behind it."),
      ("RANGE_ACCRUAL", "RangeAccrualNote", "Accrues only on days the index is inside a corridor."),
      ("CALLABLE_RANGE", "CallableRangeAccrual", "A range accrual the issuer can call, which is most of them."),
      ("INVERSE_FLOATER", "InverseFloater", "Pays a fixed rate less the index, so it rallies when rates fall."),
      ("LEVERAGED_FLOATER", "LeveragedFloater", "The same, geared."),
      ("SNOWBALL", "SnowballNote", "Each coupon is the last one plus a spread less the index."),
      ("TARN_RATES", "TargetRedemptionNote", "Redeems once coupons paid reach a target."),
      ("STEEPENER", "SteepenerNote", "Pays on the long end less the short end, with a floor at zero."),
      ("FLATTENER", "FlattenerNote", "The opposite view, and rarely retail."),
      ("CAP_DIGITAL", "DigitalCap", "Pays a fixed amount for each fixing above the strike."),
      ("RATCHET_CAP", "RatchetCap", "Each strike is set from the previous fixing."),
      ("SPREAD_LOCK", "SpreadLockAgreement", "Locks a swap spread over a government benchmark."),
      ("ZERO_COUPON_SWAP", "ZeroCouponSwap", "One payment at maturity against a stream, so the whole exposure is at the end.")]),

    ("creditstructures", "CREDIT_STRUCTURE", "CreditStructure", "Crx", 41,
     "Credit derivatives beyond the single name: indices, tranches and the recovery trade.",
     [("CDS_INDEX", "CdsIndexTrade", "A standardised basket traded as one instrument."),
      ("INDEX_TRANCHE", "IndexTranche", "A slice of an index's loss distribution, priced off correlation."),
      ("BESPOKE_TRANCHE", "BespokeTranche", "The same idea over a portfolio the client chose, which is where 2007 started."),
      ("FIRST_TO_DEFAULT", "FirstToDefaultBasket", "Pays on whichever name defaults first, and then stops."),
      ("NTH_TO_DEFAULT", "NthToDefaultBasket", "The same, further up the queue."),
      ("RECOVERY_SWAP", "RecoverySwap", "Trades the recovery rate itself, with no default view."),
      ("RECOVERY_LOCK", "RecoveryLock", "Fixes recovery for a name, usually alongside a CDS."),
      ("CDS_OPTION", "CreditDefaultSwaption", "An option to enter a CDS, knocked out by default."),
      ("CONSTANT_MATURITY_CDS", "ConstantMaturityCds", "Resets to a rolling tenor rather than amortising down the curve."),
      ("CREDIT_LINKED_NOTE", "CreditLinkedNote", "A funded CDS in note form, so the buyer takes issuer risk too."),
      ("TOTAL_RETURN_SWAP", "CreditTotalReturnSwap", "The bond's whole return against a funding leg."),
      ("LOAN_CDS", "LoanCds", "Referencing secured loans, which changes the deliverable set entirely."),
      ("SOVEREIGN_CDS", "SovereignCds", "Where the credit event definitions do most of the work."),
      ("QUANTO_CDS", "QuantoCds", "Protection in a currency other than the reference obligation's."),
      ("SUCCESSION_EVENT", "SuccessionEventItem", "What happens to protection when the reference entity is reorganised."),
      ("AUCTION_SETTLEMENT", "AuctionSettlementItem", "The industry auction that sets the recovery everyone settles at.")]),

    ("securitisation", "SECURITISATION_TRANCHE", "SecuritisationTranche", "Sec", 42,
     "Securitisation: how a pool is cut up, and who takes the first loss.",
     [("SENIOR_AAA", "SeniorAaaTranche", "Last to take losses and first to be paid."),
      ("MEZZANINE_AA", "MezzanineAaTranche", "Above the junior mezzanine and below the senior."),
      ("MEZZANINE_BBB", "MezzanineBbbTranche", "The thin slice that repriced hardest in 2007."),
      ("JUNIOR_BB", "JuniorBbTranche", "Below investment grade and priced like it."),
      ("EQUITY_TRANCHE", "EquityTranche", "First loss, usually retained, and the reason the rest can be sold."),
      ("RETAINED_STRIP", "RetainedRiskStrip", "The 5% the originator must keep under the risk-retention rules."),
      ("IO_STRIP", "InterestOnlyStrip", "Interest with no principal, and negative duration when prepayments rise."),
      ("PO_STRIP", "PrincipalOnlyStrip", "Principal with no interest, and the mirror exposure."),
      ("SEQUENTIAL_PAY", "SequentialPayClass", "Paid down in strict order rather than pro rata."),
      ("PRO_RATA", "ProRataClass", "Paid down alongside the others until a trigger says otherwise."),
      ("PAC_CLASS", "PlannedAmortisationClass", "A schedule protected by companion classes absorbing prepayment risk."),
      ("SUPPORT_CLASS", "SupportClass", "The companion that absorbs it."),
      ("REVOLVING_PERIOD", "RevolvingPeriodItem", "While new receivables are still being bought into the pool."),
      ("AMORTISATION_TRIGGER", "AmortisationTriggerItem", "The test that ends the revolving period early."),
      ("LIQUIDITY_FACILITY", "LiquidityFacilityItem", "A bank line covering timing mismatches, not credit losses."),
      ("SERVICER_ADVANCE", "ServicerAdvanceItem", "The servicer fronting missed payments it expects to recover.")]),

    ("structurednotes", "STRUCTURED_NOTE_WRAPPER", "StructuredNoteWrapper", "Snw", 43,
     "The wrapper a structured payoff is sold in, which decides the tax and the recourse.",
     [("EMTN", "EuroMediumTermNote", "Off a programme, which is why it can be issued in a day."),
      ("US_MTN", "UsMediumTermNote", "The domestic equivalent, with its own disclosure regime."),
      ("CERTIFICATE", "StructuredCertificate", "Listed, retail, and usually small denomination."),
      ("WARRANT", "CoveredWarrant", "Exchange traded and issued by the bank rather than the company."),
      ("REPACK_SPV", "RepackagingVehicle", "An SPV holding collateral and a swap, so the buyer takes neither alone."),
      ("FUND_LINKED", "FundLinkedNote", "Referencing a fund's NAV, with all the valuation lag that implies."),
      ("DEPOSIT_LINKED", "StructuredDeposit", "Deposit-protected, which changes who the creditor is."),
      ("INSURANCE_WRAPPER", "InsuranceWrappedNote", "Held inside a policy for tax reasons."),
      ("PENSION_WRAPPER", "PensionWrappedNote", "The same idea in a retirement account."),
      ("ACTIVELY_MANAGED", "ActivelyManagedCertificate", "The underlying basket changes at a manager's discretion."),
      ("DELTA_ONE_NOTE", "DeltaOneNote", "Linear exposure in note form, for accounts that cannot trade swaps."),
      ("LEVERAGED_ETN", "LeveragedExchangeTradedNote", "Daily-reset leverage, and the decay that follows."),
      ("PRINCIPAL_PROTECTED", "PrincipalProtectedNote", "Protected by the issuer's promise, not by anything else."),
      ("PARTIAL_PROTECTION", "PartiallyProtectedNote", "Protected down to a level and not below it."),
      ("REVERSE_CONVERTIBLE", "ReverseConvertibleNote", "A high coupon funded by a short put the buyer may not notice."),
      ("BONUS_CERTIFICATE", "BonusCertificate", "Pays a bonus unless a barrier is breached, when it becomes the underlying.")]),

    ("repotypes", "REPO_ARRANGEMENT", "RepoArrangement", "Rep", 44,
     "Repo: the same economics under a dozen names, and the collateral rules that differ.",
     [("CLASSIC_REPO", "ClassicRepo", "Sale and repurchase, with coupons passed back as manufactured payments."),
      ("REVERSE_REPO", "ReverseRepo", "The same trade from the cash lender's side."),
      ("TRIPARTY_REPO", "TripartyRepo", "An agent holds and substitutes collateral against an eligibility schedule."),
      ("GC_REPO", "GeneralCollateralRepo", "Any collateral from a basket; the trade is about cash, not the bond."),
      ("SPECIAL_REPO", "SpecialsRepo", "A specific bond in short supply, so the rate goes negative."),
      ("OPEN_REPO", "OpenRepo", "No fixed maturity; either side can close it daily."),
      ("EVERGREEN_REPO", "EvergreenRepo", "Rolls automatically until notice, which is how it gets liquidity treatment."),
      ("EXTENDIBLE_REPO", "ExtendibleRepo", "One side can extend the term at a pre-agreed rate."),
      ("FLOATING_REPO", "FloatingRateRepo", "The repo rate resets against an index."),
      ("CROSS_CURRENCY_REPO", "CrossCurrencyRepo", "Cash in one currency against collateral in another."),
      ("SELL_BUY_BACK", "SellBuyBack", "Economically a repo, documented as two outright trades."),
      ("BUY_SELL_BACK", "BuySellBack", "The other side of it."),
      ("PLEDGE_REPO", "PledgeStructureRepo", "Collateral pledged rather than transferred, so title does not move."),
      ("CENTRAL_CLEARED_REPO", "ClearedRepo", "Novated to a CCP, which nets it and changes the capital."),
      ("REPO_TO_MATURITY", "RepoToMaturity", "Term matching the bond's maturity, which removes the roll risk and hides the rest."),
      ("FAIL_CHARGE", "RepoFailCharge", "What is owed when the bond does not arrive.")]),

    ("fundtypes", "FUND_VEHICLE", "FundVehicle", "Fnd", 45,
     "Fund vehicles: what the client actually holds, and under whose rules.",
     [("UCITS", "UcitsFund", "European retail, with hard limits on concentration and leverage."),
      ("AIF", "AlternativeInvestmentFund", "Everything that is not UCITS, reported under AIFMD."),
      ("US_40_ACT", "RegisteredInvestmentCompany", "The US retail equivalent, under the 1940 Act."),
      ("EXCHANGE_TRADED", "ExchangeTradedFund", "Creation and redemption in kind, which is what keeps the price on NAV."),
      ("MONEY_MARKET", "MoneyMarketFund", "Short, liquid, and subject to its own gating rules."),
      ("HEDGE_FUND", "HedgeFundVehicle", "Private, performance-fee bearing, and lightly constrained."),
      ("PRIVATE_EQUITY", "PrivateEquityFund", "Drawn down over years and valued by the manager."),
      ("REAL_ESTATE", "RealEstateFund", "Where the redemption terms and the asset liquidity rarely match."),
      ("INFRASTRUCTURE", "InfrastructureFund", "Longer still, and usually with an inflation link."),
      ("FUND_OF_FUNDS", "FundOfFunds", "Two layers of fees and two layers of lookthrough."),
      ("MASTER_FEEDER", "FeederFund", "Feeds into a master so several investor bases share one book."),
      ("SEGREGATED_MANDATE", "SegregatedMandate", "Not a fund at all: the client owns the assets directly."),
      ("SIDE_POCKET", "SidePocketItem", "Illiquid holdings ring-fenced from redeeming investors."),
      ("CLOSED_END", "ClosedEndFund", "Fixed shares, so it trades at a discount or a premium to NAV."),
      ("INTERVAL_FUND", "IntervalFund", "Redeems on a schedule rather than on demand."),
      ("COLLECTIVE_TRUST", "CollectiveInvestmentTrust", "Bank-sponsored, pension-only, and cheaper for it.")]),

    ("capitalinstruments", "CAPITAL_INSTRUMENT", "CapitalInstrument", "Cap", 46,
     "Regulatory capital: what counts, where it sits, and what happens when it does not.",
     [("CET1_ORDINARY", "OrdinaryShareCapital", "The purest loss-absorbing capital there is."),
      ("CET1_RESERVES", "RetainedEarningsItem", "Profit not paid out, which counts the same way."),
      ("AT1_PERPETUAL", "AdditionalTier1Perpetual", "Perpetual, discretionary coupon, and converts or writes down at a trigger."),
      ("AT1_TEMPORARY_WD", "TemporaryWriteDownAt1", "Written down at the trigger and written back up if things recover."),
      ("TIER2_DATED", "DatedTier2", "Subordinated with a maturity, amortising out of capital in its last five years."),
      ("TIER2_PERPETUAL", "PerpetualTier2", "Subordinated with no maturity, and no amortisation."),
      ("MREL_SENIOR_NP", "SeniorNonPreferred", "Ranking below ordinary senior specifically so it can be bailed in."),
      ("MREL_HOLDCO", "HoldcoSenior", "Structurally subordinated by being issued a level up."),
      ("TLAC_ELIGIBLE", "TlacEligibleInstrument", "Meeting the global standard for the largest banks."),
      ("CONTINGENT_CONVERTIBLE", "ContingentConvertible", "Converts to equity at a capital trigger."),
      ("GRANDFATHERED", "GrandfatheredInstrument", "No longer eligible on today's rules, phased out on the old ones."),
      ("MINORITY_INTEREST", "MinorityInterestItem", "Capital in a subsidiary, only partly recognised at group."),
      ("DEDUCTION_GOODWILL", "GoodwillDeduction", "Removed from capital entirely, having no loss-absorbing value."),
      ("DEDUCTION_DTA", "DeferredTaxDeduction", "Deferred tax assets that only pay off if the bank is profitable."),
      ("PRUDENTIAL_FILTER", "PrudentialFilterItem", "An accounting result the capital rules decline to recognise."),
      ("CAPITAL_BUFFER", "CapitalBufferRequirement", "Held above the minimum, and the thing that restricts distributions.")]),

    ("paymenttypes", "PAYMENT_INSTRUCTION", # "Pay" was taken by the back-office payment taxonomy, and a tag collision is a
     # whole-corpus compile failure: set ids and filter names are both global.
     "PaymentInstruction", "Pmt", 47,
     "Payments: the rails, and what each one guarantees.",
     [("RTGS_HIGH_VALUE", "RtgsPayment", "Settled one by one in central bank money, and final on settlement."),
      ("ACH_CREDIT", "AchCreditTransfer", "Batched, cheap, and revocable for longer than people expect."),
      ("ACH_DEBIT", "AchDirectDebit", "Pulled by the payee, which is why the mandate matters."),
      ("SEPA_CREDIT", "SepaCreditTransfer", "Euro area, IBAN-addressed, next day."),
      ("SEPA_INSTANT", "SepaInstantPayment", "Ten seconds, any hour, with a value cap."),
      ("FASTER_PAYMENT", "FasterPayment", "The UK equivalent, and the one most fraud travels on."),
      ("WIRE_CORRESPONDENT", "CorrespondentWire", "Through one or more intermediaries, each taking a fee and a day."),
      ("BOOK_TRANSFER", "BookTransfer", "Both accounts at the same bank, so nothing leaves."),
      ("CHECK_CLEARING", "CheckClearingItem", "Still material, still slow, still occasionally returned."),
      ("CARD_SETTLEMENT", "CardSettlementItem", "Net of the scheme's interchange and its chargebacks."),
      ("PVP_SETTLEMENT", "PaymentVersusPayment", "Both currency legs or neither, which is what CLS exists for."),
      ("DVP_SETTLEMENT", "DeliveryVersusPayment", "Cash against securities, simultaneously."),
      ("STANDING_ORDER", "StandingOrderItem", "The same amount on the same day until cancelled."),
      ("RECALL_REQUEST", "PaymentRecallRequest", "Asking for a payment back, which the beneficiary may refuse."),
      ("RETURN_PAYMENT", "ReturnedPayment", "Rejected downstream and sent back with a reason code."),
      ("INVESTIGATION", "PaymentInvestigation", "Where a payment goes when nobody can find it.")]),

    # SETTLEMENT_FAIL was taken -- the back-office domain declared one first.
    ("settlementfails", "SETL_FAIL_REASON", "SettlementFail", "Fai", 48,
     "Why a settlement failed, which decides who pays the penalty.",
     [("INSUFFICIENT_SECURITIES", "InsufficientSecurities", "The seller does not have the stock, which is most fails."),
      ("INSUFFICIENT_CASH", "InsufficientCash", "The buyer does not have the money."),
      ("SSI_MISMATCH", "SsiMismatchFail", "The two sides are instructing to different places."),
      ("LATE_MATCHING", "LateMatchingFail", "Matched after the cut-off, so it settles the next day."),
      ("UNMATCHED", "UnmatchedInstruction", "Never matched at all, which is a different problem."),
      ("SHAPING_REQUIRED", "ShapingRequiredFail", "Too large for the market's settlement limit and not split."),
      ("PARTIAL_SETTLEMENT", "PartialSettlementItem", "Some of it settled, which is better than none and worse than all."),
      ("BUY_IN_TRIGGERED", "BuyInTriggered", "The buyer went to the market and the seller pays the difference."),
      ("CSDR_PENALTY", "CsdrPenaltyItem", "The daily charge the settlement discipline regime imposes."),
      ("MARKET_CLAIM", "MarketClaimItem", "An entitlement that went to the wrong side because the trade was late."),
      ("TRANSFORMATION", "TransformationItem", "A pending instruction rewritten by a corporate action."),
      ("CANCELLED_BILATERAL", "BilateralCancellation", "Both sides agreed to withdraw it."),
      ("COUNTERPARTY_SUSPENDED", "CounterpartySuspended", "The other side cannot settle anything today."),
      ("SANCTIONS_HOLD", "SanctionsHold", "Stopped by screening, and not restartable by operations."),
      ("PLACE_OF_SETTLEMENT", "WrongPlaceOfSettlement", "Instructed at the wrong depository, which fails silently until it does not."),
      ("SYSTEM_OUTAGE", "SystemOutageFail", "Nobody's fault, and still a fail.")]),

    ("surveillance", "SURVEILLANCE_ALERT", "SurveillanceAlert", "Srv", 49,
     "Market-abuse surveillance: what the model flagged, and what it usually turns out to be.",
     [("SPOOFING", "SpoofingAlert", "Orders placed with no intention of executing, to move the book."),
      ("LAYERING", "LayeringAlert", "The same thing at several price levels."),
      ("WASH_TRADE", "WashTradeAlert", "Both sides the same beneficial owner, so no risk changed hands."),
      ("MARKING_THE_CLOSE", "MarkingTheCloseAlert", "Trading into the auction to set a favourable closing price."),
      ("MOMENTUM_IGNITION", "MomentumIgnitionAlert", "Provoking a move and then trading against the reaction."),
      ("QUOTE_STUFFING", "QuoteStuffingAlert", "Message volume as a weapon rather than as an intention."),
      ("FRONT_RUNNING", "FrontRunningAlert", "Dealing ahead of a client order."),
      ("INSIDER_DEALING", "InsiderDealingAlert", "Trading on information the market does not have."),
      ("UNUSUAL_PROFIT", "UnusualProfitAlert", "A return the model cannot explain, which is a lead rather than a finding."),
      ("PRICE_OUTLIER", "PriceOutlierAlert", "Executed far from the prevailing market."),
      ("CROSS_MARKET", "CrossMarketAlert", "The abuse is in one venue and the profit in another."),
      ("REFERENCE_RATE", "BenchmarkManipulationAlert", "Trading to influence a fixing rather than to take a position."),
      ("OFF_MARKET_TRANSFER", "OffMarketTransferAlert", "Value moved between accounts at a price nobody would agree to."),
      ("PERSONAL_ACCOUNT", "PersonalAccountDealingAlert", "An employee's own account, undisclosed."),
      ("RESTRICTED_LIST", "RestrictedListBreachAlert", "Dealing in a name the firm has restricted."),
      ("COMMUNICATION_FLAG", "CommunicationFlagAlert", "Language a model found interesting, which is almost always nothing.")]),
]

FIELDS = [("recordId", "RECORD_ID", "String", 1),
          ("recordType", "RECORD_TYPE", "String", 1),
          ("bookId", "BOOK_ID", "String", 1),
          ("notional", "NOTIONAL", "Float", 1),
          ("currency", "CURRENCY", "String", 1),
          ("tradeDate", "TRADE_DATE", "StrictDate", 1),
          ("maturityDate", "MATURITY_DATE", "StrictDate", 0),
          ("status", "STATUS", "String", 1),
          ("isActive", "IS_ACTIVE", "Boolean", 1),
          ("riskScore", "RISK_SCORE", "Float", 1)]

DERIVED = ("   // Notional per unit of risk score -- the crude size-versus-danger number a\n"
           "   // supervisor asks for first. Division, so the engine is in double from here.\n"
           "   notionalPerRiskPoint() { $this.notional / $this.riskScore } : Float[1];\n"
           "   // A qualified property: the notional converted at a rate the caller supplies.\n"
           "   notionalIn(fxRate: Float[1]) { $this.notional * $fxRate } : Float[1];")


def rows(items, prefix):
    """Two rows in three. The third of the subtypes with no rows is the point."""
    out = []
    for i, (code, _n, _d) in enumerate(items):
        if i % 3 == 2:
            continue
        out.append(
            f'    dict(RECORD_ID="{prefix}-{i + 1:05d}", RECORD_TYPE="{code}",\n'
            f'         BOOK_ID="BK-{["EQ", "RATES", "CREDIT"][i % 3]}", '
            f'NOTIONAL={(1 + i) * 1250000.00},\n'
            f'         CURRENCY="{["USD", "EUR", "GBP", "JPY"][i % 4]}", '
            f'TRADE_DATE=_iso(2024, 6, {3 + i % 18}),\n'
            f'         MATURITY_DATE='
            f'{f"_iso(202{5 + i % 4}, {1 + i % 12}, {1 + i % 27})" if i % 4 else None},\n'
            f'         STATUS="{["LIVE", "LIVE", "MATURED", "TERMINATED"][i % 4]}", '
            f'IS_ACTIVE={i % 4 < 2},\n'
            f'         RISK_SCORE={round(1.25 + i * 0.75, 2)}),')
    return "\n".join(out)


def apply() -> None:
    for pkg, table, base, tag, idx, doc, types in TAXA:
        refdata.emit(table=table, pkg=pkg, base=base, discriminator="recordType",
                     tag=tag, types=types, file_index=idx, doc=doc, fields=FIELDS,
                     derived=DERIVED, seed_rows=rows(types, tag.upper()))
        print(f"  {pkg:<20}{len(types)} types")


if __name__ == "__main__":
    apply()
