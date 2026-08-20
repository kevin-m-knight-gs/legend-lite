"""
One service per SUBTYPE of a discriminated taxonomy.

A taxonomy modelled as one table plus a discriminator has exactly one thing that can go
wrong, and it is invisible: if a subtype's `~filter` is wrong, the set returns the WHOLE
table rather than its own rows. Nothing errors, the columns are right, the types are right,
and a report that asked for swaptions gets every trade in the book.

The only way to notice is to ask each subtype for its own rows and check the count. So this
emits one service per subtype -- seventy-odd of them across the derivatives, risk,
middle-office and back-office taxonomies -- each projecting the discriminator column itself,
which makes a wrong filter obvious in the expectation rather than merely wrong.

Empty subtypes are emitted too, and deliberately. A firm's product taxonomy covers what it
CAN book, not what it happens to hold this morning, so half of these return no rows -- and an
empty result is the strongest possible evidence that a filter is doing its job, because the
failure mode is returning everything.
"""
from __future__ import annotations

import model
import query
from query import Proj, Spec

# (base class, the property carrying the discriminator, a short tag for the service name)
TAXONOMIES = [
    ("derivatives::OtcTrade", "productType", "TX"),
    ("risk::RiskMeasure", "measureType", "TXR"),
    ("risk::Sensitivity", "measureType", "TXS"),
    ("middleoffice::LifecycleEvent", "eventType", "TXE"),
    ("middleoffice::TradeBreak", "breakType", "TXB"),
    ("backoffice::Payment", "paymentType", "TXP"),
    ("backoffice::ReconciliationItem", "itemType", "TXI"),
    ("corpactions::CorporateActionEvent", "actionType", "TXC"),
    ("securities::MasterSecurity", "securityType", "TXM"),
    ("orders::OrderTicket", "orderType", "TXO"),
    ("greenfinance::SustainableFinanceItem", "recordType", "GRNX"),
    ("appetitemetrics::RiskAppetiteMetric", "recordType", "RAPX"),
    ("benchmarkreform::BenchmarkTransitionItem", "recordType", "BTRX"),
    ("shortselling::ShortPositionItem", "recordType", "SHOX"),
    ("issuanceprocess::IssuanceStage", "recordType", "ISSX"),
    ("clientreporting::ClientReport", "recordType", "CRPX"),
    ("outsourcing::OutsourcingArrangement", "recordType", "OUTX"),
    ("cashmanagement::CashManagementItem", "recordType", "CSHX"),
    ("prospectus::ProspectusItem", "recordType", "PROX"),
    ("cbrelationships::CorrespondentRelationship", "recordType", "CBRX"),
    ("frtbdesks::FrtbDeskItem", "recordType", "FRTX"),
    ("legalagreements::LegalAgreement", "recordType", "LGLX"),
    ("recontypes::ReconciliationType", "recordType", "RCNX"),
    ("swiftmessages::SwiftMessage", "recordType", "SWFX"),
    ("lendingcovenants::LoanCovenant", "recordType", "COVX"),
    ("bankingproducts::BankingProduct", "recordType", "BNKX"),
    ("marketmaking::MarketMakingObligation", "recordType", "MMKX"),
    ("stresstests::StressTestItem", "recordType", "STTX"),
    ("valuationadj::ValuationAdjustment", "recordType", "XVAX"),
    ("clearingmembers::ClearingArrangement", "recordType", "CLRX"),
    ("taxlots::TaxLotMethod", "recordType", "TLMX"),
    ("proxyvoting::ProxyVoteItem", "recordType", "PRXX"),
    ("corpactionsx::CorporateEventType", "recordType", "CEVX"),
    ("identifiers::InstrumentIdentifier", "recordType", "IDNX"),
    ("realassets::RealAssetHolding", "recordType", "REAX"),
    ("pensionsitems::PensionSchemeItem", "recordType", "PENX"),
    ("wealthproducts::WealthProduct", "recordType", "WLTX"),
    ("tradefinance::TradeFinanceItem", "recordType", "TFIX"),
    ("islamicfinance::ShariahStructure", "recordType", "ISLX"),
    ("digitalassets::DigitalAssetPosition", "recordType", "DIGX"),
    ("insurancelinked::InsuranceLinkedItem", "recordType", "INSX"),
    ("commodities::CommodityContract", "recordType", "CMDX"),
    ("ratingscales::RatingAssignment", "recordType", "RATX"),
    ("indexrules::IndexMethodology", "recordType", "IDXX"),
    ("feetypes::FeeChargeType", "recordType", "CHGX"),
    ("ordertypes::OrderHandlingType", "recordType", "OHTX"),
    ("hedgeaccounting::HedgeRelationship", "recordType", "HDGX"),
    ("incidents::OperationalIncident", "recordType", "INCX"),
    ("clientaccess::ClientAccessArrangement", "recordType", "ACCX"),
    ("employeeroles::EmployeeRole", "recordType", "EMPX"),
    ("bookinglocations::BookingLocation", "recordType", "BKLX"),
    ("regimes::RegulatoryRegime", "recordType", "RGMX"),
    ("dataquality::DataQualityRule", "recordType", "DQRX"),
    ("auditfindings::AuditFinding", "recordType", "AUDX"),
    ("modelrisk::ModelInventoryItem", "recordType", "MDLX"),
    ("impairment::ImpairmentStage", "recordType", "IMPX"),
    ("balancesheet::BalanceSheetItem", "recordType", "BSIX"),
    ("fundingsources::FundingSource", "recordType", "FUNX"),
    ("surveillance::SurveillanceAlert", "recordType", "SRVX"),
    ("settlementfails::SettlementFail", "recordType", "FAIX"),
    ("paymenttypes::PaymentInstruction", "recordType", "PMTX"),
    ("capitalinstruments::CapitalInstrument", "recordType", "CAPX"),
    ("fundtypes::FundVehicle", "recordType", "FNDX"),
    ("repotypes::RepoArrangement", "recordType", "REPX"),
    ("structurednotes::StructuredNoteWrapper", "recordType", "SNWX"),
    ("securitisation::SecuritisationTranche", "recordType", "SECX"),
    ("creditstructures::CreditStructure", "recordType", "CRXX"),
    ("ratesexotics::RatesExoticTrade", "recordType", "RTXX"),
    ("fxexotics::FxExoticStructure", "recordType", "FXXX"),
    ("eqexotics::EquityExoticPayoff", "recordType", "EQXX"),
    ("margincalls::MarginCallRecord", "callType", "MGNX"),
    ("pricesources::PriceSourceRecord", "sourceType", "SRCX"),
    ("messaging::InboundMessage", "messageType", "MSGX"),
    ("esg::EsgMetric", "metricType", "ESGX"),
    ("custody::CustodyInstruction", "instructionType", "CUSTX"),
    ("portfolios::PortfolioRecord", "portfolioType", "PFX"),
    ("taxregimes::TaxRegimeApplication", "regimeType", "TAXX"),
    ("benchmarks::BenchmarkIndex", "indexType", "BMKX"),
    ("liquidity::LiquidityMetric", "metricType", "LIQX"),
    ("venues::TradingVenue", "venueType", "VENX"),
    ("clients::ClientEntity", "clientType", "CLIX"),
    ("documents::LegalDocument", "documentType", "DOCX"),
    ("exceptions::ProcessingException", "exceptionType", "EXCX"),
    ("valmodels::ValuationModelRun", "modelType", "VALX"),
    ("ledger::AccountingEntry", "entryType", "LEDX"),
    ("kyc::ComplianceCheck", "checkType", "KYCX"),
    ("seclending::SecuritiesLoan", "loanType", "SLX"),
    ("marks::MarketDataPoint", "dataType", "MKTX"),
    ("fees::FeeCharge", "feeType", "FEEX"),
    ("accounts::AccountRecord", "accountType", "ACCTX"),
    ("limits::RiskLimitDefinition", "limitType", "LIMX"),
    ("regreporting::RegulatorySubmission", "reportType", "REGX"),
    ("collateraltypes::CollateralHolding", "collateralType", "COLX"),
]

# What each subtype service STACKS on top of the discriminator.
#
# The first version projected the identifier and the discriminator and nothing else, which is
# the right test for a ~filter and contributes nothing to the stacking scoreboard: a hundred
# and ten services, two constructs each, no combinations. These are the same services asking
# the same question with the reach the model already offers -- a derived property, a
# qualified one, a navigation, an emptiness -- so a wrong filter is still obvious AND the
# service is a stack rather than a probe.
#
# (alias, path, aggregate, args)
EXTRAS = {
    "derivatives::OtcTrade": [
        ("tenorYears", ["tenorYears"], None, []),
        ("notionalGbp", ["notionalIn"], None, [0.79]),
        ("counterpartyId", ["counterpartyId"], None, []),
        ("noLegs", ["legs"], "isEmpty", []),
        ("noOptionTerms", ["optionTerms"], "isEmpty", []),
    ],
    "risk::RiskMeasure": [
        ("valueEur", ["valueIn"], None, [0.92]),
        ("cobDate", ["measureRun", "cobDate"], None, []),
        ("runStatus", ["measureRun", "status"], None, []),
    ],
    "risk::Sensitivity": [
        ("scaled", ["scaledTo"], None, [2.0]),
        ("curveName", ["factor", "curveName"], None, []),
        ("assetClass", ["factor", "assetClass"], None, []),
        ("productType", ["sensitivityTrade", "productType"], None, []),
    ],
    "middleoffice::LifecycleEvent": [
        ("settlementLagDays", ["settlementLagDays"], None, []),
        ("tradeProduct", ["eventTrade", "productType"], None, []),
        ("tradeCurrency", ["eventTrade", "currency"], None, []),
    ],
    "middleoffice::TradeBreak": [
        ("ageDays", ["ageDays"], None, []),
        ("tradeProduct", ["brokenTrade", "productType"], None, []),
        ("counterpartyId", ["brokenTrade", "counterpartyId"], None, []),
    ],
    "backoffice::Payment": [
        ("signedAmount", ["signedAmount"], None, []),
        ("correspondentBic", ["instruction", "correspondentBic"], None, []),
        ("placeOfSettlement", ["instruction", "placeOfSettlement"], None, []),
        ("tradeProduct", ["paidTrade", "productType"], None, []),
    ],
    "backoffice::ReconciliationItem": [
        ("ageDays", ["ageDays"], None, []),
        ("accountName", ["reconNostro", "accountName"], None, []),
        ("correspondentBic", ["reconNostro", "correspondentBic"], None, []),
    ],
    "corpactions::CorporateActionEvent": [
        ("noticeDays", ["noticeDays"], None, []),
        ("isMandatory", ["isMandatory"], None, []),
        ("cashRate", ["cashRate"], None, []),
        ("payDate", ["payDate"], None, []),
    ],
    "securities::MasterSecurity": [
        ("daysToMaturity", ["daysToMaturity"], None, []),
        ("issuerName", ["issuerName"], None, []),
        ("faceValue", ["faceValue"], None, []),
        ("isListed", ["isListed"], None, []),
    ],
    "margincalls::MarginCallRecord": [
        ("disputedAmount", ["disputedAmount"], None, []),
        ("agreedAmount", ["agreedAmount"], None, []),
        ("isDisputed", ["isDisputed"], None, []),
        ("settledOn", ["settledOn"], None, []),
    ],
    "pricesources::PriceSourceRecord": [
        ("confidencePct", ["confidencePct"], None, []),
        ("isPrimary", ["isPrimary"], None, []),
        ("contributorCount", ["contributorCount"], None, []),
        ("challengedBy", ["challengedBy"], None, []),
    ],
    "custody::CustodyInstruction": [
        ("daysLate", ["daysLate"], None, []),
        ("custodian", ["custodian"], None, []),
        ("isUrgent", ["isUrgent"], None, []),
        ("settledOn", ["settledOn"], None, []),
    ],
    "esg::EsgMetric": [
        ("versusPeers", ["versusPeers"], None, []),
        ("unit", ["unit"], None, []),
        ("isEstimated", ["isEstimated"], None, []),
        ("assuranceLevel", ["assuranceLevel"], None, []),
    ],
    "messaging::InboundMessage": [
        ("sizeKb", ["sizeKb"], None, []),
        ("protocol", ["protocol"], None, []),
        ("isDuplicate", ["isDuplicate"], None, []),
        ("errorDetail", ["errorDetail"], None, []),
    ],
    "clients::ClientEntity": [
        ("tenureDays", ["tenureDays"], None, []),
        ("classification", ["classification"], None, []),
        ("aumUsd", ["aumUsd"], None, []),
        ("lei", ["lei"], None, []),
    ],
    "venues::TradingVenue": [
        ("volumeMillions", ["volumeMillions"], None, []),
        ("mic", ["mic"], None, []),
        ("country", ["country"], None, []),
        ("operator", ["operator"], None, []),
    ],
    "liquidity::LiquidityMetric": [
        ("bufferOverMinimum", ["bufferOverMinimum"], None, []),
        ("regulatoryMinimum", ["regulatoryMinimum"], None, []),
        ("isBreached", ["isBreached"], None, []),
        ("horizonDays", ["horizonDays"], None, []),
    ],
    "benchmarks::BenchmarkIndex": [
        ("dailyReturnPct", ["dailyReturnPct"], None, []),
        ("weightingScheme", ["weightingScheme"], None, []),
        ("constituentCount", ["constituentCount"], None, []),
        ("previousLevel", ["previousLevel"], None, []),
    ],
    "taxregimes::TaxRegimeApplication": [
        ("netReceived", ["netReceived"], None, []),
        ("taxRatePct", ["taxRatePct"], None, []),
        ("jurisdiction", ["jurisdiction"], None, []),
        ("reclaimableAmount", ["reclaimableAmount"], None, []),
    ],
    "portfolios::PortfolioRecord": [
        ("navMillions", ["navMillions"], None, []),
        ("baseCurrency", ["baseCurrency"], None, []),
        ("isActive", ["isActive"], None, []),
        ("managerId", ["managerId"], None, []),
    ],
    "seclending::SecuritiesLoan": [
        ("dailyFee", ["dailyFee"], None, []),
        ("feeRateBp", ["feeRateBp"], None, []),
        ("isOpen", ["isOpen"], None, []),
        ("endDate", ["endDate"], None, []),
    ],
    "kyc::ComplianceCheck": [
        ("daysToExpiry", ["daysToExpiry"], None, []),
        ("outcome", ["outcome"], None, []),
        ("riskRating", ["riskRating"], None, []),
        ("findings", ["findings"], None, []),
    ],
    "ledger::AccountingEntry": [
        ("netMovement", ["netMovement"], None, []),
        ("accountCode", ["accountCode"], None, []),
        ("isReversal", ["isReversal"], None, []),
        ("sourceRef", ["sourceRef"], None, []),
    ],
    "valmodels::ValuationModelRun": [
        ("elapsedSeconds", ["elapsedSeconds"], None, []),
        ("presentValue", ["presentValue"], None, []),
        ("isApproved", ["isApproved"], None, []),
        ("calibrationError", ["calibrationError"], None, []),
    ],
    "exceptions::ProcessingException": [
        ("minutesToResolve", ["minutesToResolve"], None, []),
        ("severity", ["severity"], None, []),
        ("sourceSystem", ["sourceSystem"], None, []),
        ("message", ["message"], None, []),
    ],
    "documents::LegalDocument": [
        ("daysValid", ["daysValid"], None, []),
        ("governingLaw", ["governingLaw"], None, []),
        ("isExecuted", ["isExecuted"], None, []),
        ("custodyRef", ["custodyRef"], None, []),
    ],
    "accounts::AccountRecord": [
        ("daysOpen", ["daysOpen"], None, []),
        ("balance", ["balance"], None, []),
        ("isSegregated", ["isSegregated"], None, []),
        ("custodian", ["custodian"], None, []),
    ],
    "fees::FeeCharge": [
        ("netAmount", ["netAmount"], None, []),
        ("taxAmount", ["taxAmount"], None, []),
        ("invoiceRef", ["invoiceRef"], None, []),
        ("basisPoints", ["basisPoints"], None, []),
    ],
    "marks::MarketDataPoint": [
        ("dailyChange", ["dailyChange"], None, []),
        ("source", ["source"], None, []),
        ("tenor", ["tenor"], None, []),
        ("isStale", ["isStale"], None, []),
    ],
    "collateraltypes::CollateralHolding": [
        ("collateralValue", ["collateralValue"], None, []),
        ("haircutPct", ["haircutPct"], None, []),
        ("custodian", ["custodian"], None, []),
        ("isEligible", ["isEligible"], None, []),
    ],
    "regreporting::RegulatorySubmission": [
        ("ackHours", ["ackHours"], None, []),
        ("authority", ["authority"], None, []),
        ("recordCount", ["recordCount"], None, []),
        ("rejectionReason", ["rejectionReason"], None, []),
    ],
    "limits::RiskLimitDefinition": [
        ("headroom", ["headroom"], None, []),
        ("scope", ["scope"], None, []),
        ("utilisation", ["utilisation"], None, []),
        ("breachedOn", ["breachedOn"], None, []),
    ],
    "orders::OrderTicket": [
        ("fillRatio", ["fillRatio"], None, []),
        ("side", ["side"], None, []),
        ("limitPrice", ["limitPrice"], None, []),
        ("timeInForce", ["timeInForce"], None, []),
    ],
}

# An identifier per base, so the service has something stable to sort on.
IDENT = {
    "derivatives::OtcTrade": "otcId",
    "risk::RiskMeasure": "measureId",
    "risk::Sensitivity": "sensitivityId",
    "middleoffice::LifecycleEvent": "eventId",
    "middleoffice::TradeBreak": "breakId",
    "backoffice::Payment": "paymentId",
    "backoffice::ReconciliationItem": "reconId",
    "corpactions::CorporateActionEvent": "actionId",
    "securities::MasterSecurity": "securityKey",
    "orders::OrderTicket": "ticketId",
    "greenfinance::SustainableFinanceItem": "recordId",
    "appetitemetrics::RiskAppetiteMetric": "recordId",
    "benchmarkreform::BenchmarkTransitionItem": "recordId",
    "shortselling::ShortPositionItem": "recordId",
    "issuanceprocess::IssuanceStage": "recordId",
    "clientreporting::ClientReport": "recordId",
    "outsourcing::OutsourcingArrangement": "recordId",
    "cashmanagement::CashManagementItem": "recordId",
    "prospectus::ProspectusItem": "recordId",
    "cbrelationships::CorrespondentRelationship": "recordId",
    "frtbdesks::FrtbDeskItem": "recordId",
    "legalagreements::LegalAgreement": "recordId",
    "recontypes::ReconciliationType": "recordId",
    "swiftmessages::SwiftMessage": "recordId",
    "lendingcovenants::LoanCovenant": "recordId",
    "bankingproducts::BankingProduct": "recordId",
    "marketmaking::MarketMakingObligation": "recordId",
    "stresstests::StressTestItem": "recordId",
    "valuationadj::ValuationAdjustment": "recordId",
    "clearingmembers::ClearingArrangement": "recordId",
    "taxlots::TaxLotMethod": "recordId",
    "proxyvoting::ProxyVoteItem": "recordId",
    "corpactionsx::CorporateEventType": "recordId",
    "identifiers::InstrumentIdentifier": "recordId",
    "realassets::RealAssetHolding": "recordId",
    "pensionsitems::PensionSchemeItem": "recordId",
    "wealthproducts::WealthProduct": "recordId",
    "tradefinance::TradeFinanceItem": "recordId",
    "islamicfinance::ShariahStructure": "recordId",
    "digitalassets::DigitalAssetPosition": "recordId",
    "insurancelinked::InsuranceLinkedItem": "recordId",
    "commodities::CommodityContract": "recordId",
    "ratingscales::RatingAssignment": "recordId",
    "indexrules::IndexMethodology": "recordId",
    "feetypes::FeeChargeType": "recordId",
    "ordertypes::OrderHandlingType": "recordId",
    "hedgeaccounting::HedgeRelationship": "recordId",
    "incidents::OperationalIncident": "recordId",
    "clientaccess::ClientAccessArrangement": "recordId",
    "employeeroles::EmployeeRole": "recordId",
    "bookinglocations::BookingLocation": "recordId",
    "regimes::RegulatoryRegime": "recordId",
    "dataquality::DataQualityRule": "recordId",
    "auditfindings::AuditFinding": "recordId",
    "modelrisk::ModelInventoryItem": "recordId",
    "impairment::ImpairmentStage": "recordId",
    "balancesheet::BalanceSheetItem": "recordId",
    "fundingsources::FundingSource": "recordId",
    "surveillance::SurveillanceAlert": "recordId",
    "settlementfails::SettlementFail": "recordId",
    "paymenttypes::PaymentInstruction": "recordId",
    "capitalinstruments::CapitalInstrument": "recordId",
    "fundtypes::FundVehicle": "recordId",
    "repotypes::RepoArrangement": "recordId",
    "structurednotes::StructuredNoteWrapper": "recordId",
    "securitisation::SecuritisationTranche": "recordId",
    "creditstructures::CreditStructure": "recordId",
    "ratesexotics::RatesExoticTrade": "recordId",
    "fxexotics::FxExoticStructure": "recordId",
    "eqexotics::EquityExoticPayoff": "recordId",
    "margincalls::MarginCallRecord": "callId",
    "pricesources::PriceSourceRecord": "recordId",
    "messaging::InboundMessage": "messageId",
    "esg::EsgMetric": "metricId",
    "custody::CustodyInstruction": "instructionId",
    "portfolios::PortfolioRecord": "portfolioId",
    "taxregimes::TaxRegimeApplication": "applicationId",
    "benchmarks::BenchmarkIndex": "indexId",
    "liquidity::LiquidityMetric": "metricId",
    "venues::TradingVenue": "venueId",
    "clients::ClientEntity": "clientId",
    "documents::LegalDocument": "documentId",
    "exceptions::ProcessingException": "exceptionId",
    "valmodels::ValuationModelRun": "runId",
    "ledger::AccountingEntry": "entryId",
    "kyc::ComplianceCheck": "checkId",
    "seclending::SecuritiesLoan": "loanId",
    "marks::MarketDataPoint": "pointId",
    "fees::FeeCharge": "chargeId",
    "accounts::AccountRecord": "accountId",
    "limits::RiskLimitDefinition": "limitId",
    "regreporting::RegulatorySubmission": "submissionId",
    "collateraltypes::CollateralHolding": "holdingId",
}


def build(c: model.Corpus, seeded: set[str], tables=None) -> list[Spec]:
    specs = []
    for base, discriminator, tag in TAXONOMIES:
        ident = IDENT[base]
        subtypes = sorted(k for k, v in c.classes.items() if v.supertype == base)
        for cls in subtypes:
            short = query.short_name(c, cls)
            spec = Spec(f"stress::{tag}_{short}", f"/stress/{tag.lower()}_{short.lower()}",
                        f"{cls} only. The subtype's ~filter is the whole test: if it were "
                        f"wrong this would return every row of the base table instead of "
                        f"its own, with no error and no type mismatch. Generated by "
                        f"scripts/corpus/taxonomy.py.", cls)
            # The discriminator itself is projected, so a wrong filter shows up as a wrong
            # VALUE in the expectation rather than only as a wrong row count.
            spec.projections = [Proj(ident, [ident]),
                                Proj(discriminator, [discriminator])]
            for alias, path, agg, args in EXTRAS.get(base, []):
                spec.projections.append(Proj(alias, list(path), agg, list(args)))
            spec.sort = (ident, False)
            query.apply_temporal(c, spec)
            specs.append(spec)
    return specs
