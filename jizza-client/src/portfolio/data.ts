import { createContext, useContext } from 'react'
import { useQuery } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import { financeKeys } from './queryKeys'
import {
  SAMPLE_ACTIONS, SAMPLE_ASSETS, SAMPLE_CASH_FLOW, SAMPLE_ESTATE, SAMPLE_HEALTH, SAMPLE_LOANS, SAMPLE_LOANS_GIVEN,
  SAMPLE_NET_WORTH, SAMPLE_PLAN, SAMPLE_POLICIES, SAMPLE_PROTECTION,
} from './sample'

/**
 * Whether the Portfolio shows the sample household instead of the user's own figures. In sample
 * mode every read answers from {@link ./sample} and nothing can be saved.
 */
export const SampleContext = createContext(false)

export function useSample() {
  return useContext(SampleContext)
}

/** Each area's read, from the API or, in sample mode, from the sample household. */
export function useHealth() {
  const sample = useSample()
  return useQuery({ queryKey: financeKeys.health(sample), queryFn: sample ? async () => SAMPLE_HEALTH : financeApi.health })
}

export function useActions() {
  const sample = useSample()
  return useQuery({ queryKey: financeKeys.actions(sample), queryFn: sample ? async () => SAMPLE_ACTIONS : financeApi.actions })
}

export function useCashFlow() {
  const sample = useSample()
  return useQuery({ queryKey: financeKeys.cashFlow(sample), queryFn: sample ? async () => SAMPLE_CASH_FLOW : financeApi.cashFlow })
}

export function useNetWorth() {
  const sample = useSample()
  return useQuery({ queryKey: financeKeys.netWorth(sample), queryFn: sample ? async () => SAMPLE_NET_WORTH : financeApi.netWorth })
}

export function useAssets() {
  const sample = useSample()
  return useQuery({ queryKey: financeKeys.assets(sample), queryFn: sample ? async () => SAMPLE_ASSETS : financeApi.assets })
}

export function useLoans() {
  const sample = useSample()
  return useQuery({ queryKey: financeKeys.loans(sample), queryFn: sample ? async () => SAMPLE_LOANS : financeApi.loans })
}

export function useLoanPlan(method: 'avalanche' | 'snowball', extra: number) {
  const sample = useSample()
  return useQuery({
    queryKey: financeKeys.loanPlan(sample, method, extra),
    queryFn: sample ? async () => ({ ...SAMPLE_PLAN, method }) : () => financeApi.loanPlan(method, extra),
    placeholderData: prev => prev,
  })
}

export function useLoansGiven() {
  const sample = useSample()
  return useQuery({ queryKey: financeKeys.loansGiven(sample), queryFn: sample ? async () => SAMPLE_LOANS_GIVEN : financeApi.loansGiven })
}

export function usePolicies() {
  const sample = useSample()
  return useQuery({ queryKey: financeKeys.policies(sample), queryFn: sample ? async () => SAMPLE_POLICIES : financeApi.policies })
}

export function useProtection() {
  const sample = useSample()
  return useQuery({ queryKey: financeKeys.protection(sample), queryFn: sample ? async () => SAMPLE_PROTECTION : financeApi.protection })
}

export function useEstate(personId: string | undefined) {
  const sample = useSample()
  return useQuery({
    queryKey: financeKeys.estate(sample, personId ?? ''),
    queryFn: sample ? async () => SAMPLE_ESTATE : () => financeApi.estate(personId!),
    enabled: !!personId,
  })
}

export function errorText(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback
}
