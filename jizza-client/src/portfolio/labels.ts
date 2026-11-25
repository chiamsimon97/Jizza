import type { AssetClass, AssetKind, LoanKind, PolicyKind } from '@/types/finance'

export const LOAN_KINDS: Record<LoanKind, string> = {
  HOME: 'Home loan', VEHICLE: 'Vehicle loan', EDUCATION: 'Education loan', PERSONAL: 'Personal loan',
  CREDIT_CARD: 'Credit card', CONSUMER_EMI: 'Consumer EMI', GOLD_LOAN: 'Gold loan',
  LOAN_AGAINST_SECURITIES: 'Loan against shares or funds', LOAN_AGAINST_FD: 'Loan against FD',
  LOAN_AGAINST_INSURANCE: 'Loan against a policy', PPF_LOAN: 'PPF loan', OVERDRAFT: 'Overdraft',
  BUSINESS: 'Business loan', EMPLOYER_ADVANCE: 'Employer advance', FAMILY_FRIEND: 'Family or friend',
  MONEYLENDER: 'Moneylender', CHIT_PRIZE: 'Chit fund (prized)', OTHER: 'Other loan',
}

/** The kinds Quick start offers first; the full list is in the add-loan form. */
export const COMMON_LOAN_KINDS: LoanKind[] = ['HOME', 'VEHICLE', 'EDUCATION', 'PERSONAL', 'CREDIT_CARD', 'GOLD_LOAN',
  'LOAN_AGAINST_FD', 'LOAN_AGAINST_SECURITIES', 'FAMILY_FRIEND', 'MONEYLENDER', 'OTHER']

/** Borrowing from a person: the name stays private and the rate is often "no interest". */
export const isInformal = (k: LoanKind) => k === 'FAMILY_FRIEND' || k === 'MONEYLENDER'

export const POLICY_KINDS: Record<PolicyKind, string> = {
  term: 'Term life', health: 'Health', super_topup: 'Super top-up', personal_accident: 'Personal accident',
  critical_illness: 'Critical illness', vehicle: 'Vehicle', home: 'Home', endowment: 'Endowment', ulip: 'ULIP',
}

export const ASSET_KINDS: Record<AssetKind, string> = {
  savings_balance: 'Savings and sweep FD', fd: 'Fixed deposit', rd: 'Recurring deposit', epf: 'EPF', ppf: 'PPF',
  nps: 'NPS', ssy: 'Sukanya Samriddhi', post_office: 'Post office scheme', gold_physical: 'Gold',
  gold_digital: 'Digital gold', property: 'Property', policy_surrender: 'Policy surrender value', chit: 'Chit fund',
  cash: 'Cash at home', vehicle: 'Vehicle', other: 'Other',
}

export const ASSET_CLASSES: Record<AssetClass, string> = {
  cash: 'Cash', debt: 'Debt and retirement', equity: 'Equity', gold: 'Gold', other: 'Property and other',
}

/** Rough totals Quick start asks for, each mapped to what the server stores. */
export const SAVING_TYPES: { id: string; label: string; kind: AssetKind; assetClass: AssetClass; name: string; emergency: boolean }[] = [
  { id: 'savings', label: 'Savings and sweep FD', kind: 'savings_balance', assetClass: 'cash', name: 'Savings balance', emergency: true },
  { id: 'fd', label: 'Fixed deposits', kind: 'fd', assetClass: 'debt', name: 'Fixed deposits', emergency: false },
  { id: 'mf', label: 'Mutual funds', kind: 'other', assetClass: 'equity', name: 'Mutual funds', emergency: false },
  { id: 'shares', label: 'Shares', kind: 'other', assetClass: 'equity', name: 'Shares', emergency: false },
  { id: 'epf', label: 'EPF', kind: 'epf', assetClass: 'debt', name: 'EPF', emergency: false },
  { id: 'ppf', label: 'PPF', kind: 'ppf', assetClass: 'debt', name: 'PPF', emergency: false },
  { id: 'nps', label: 'NPS', kind: 'nps', assetClass: 'debt', name: 'NPS', emergency: false },
  { id: 'gold', label: 'Gold', kind: 'gold_physical', assetClass: 'gold', name: 'Gold', emergency: false },
  { id: 'property', label: 'Property', kind: 'property', assetClass: 'other', name: 'Property', emergency: false },
]

/** Who a person is, for a line of text: the user is "You". */
export function personName(names: Record<string, string>, id: string | null | undefined, selfId?: string): string {
  if (!id) return 'You'
  if (id === selfId) return 'You'
  return names[id] ?? 'Someone you manage'
}
