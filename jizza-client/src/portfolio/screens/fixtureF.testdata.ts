/** Fixture F's repayment plan as the API returned it (ids replaced). Test data only. */
import type { LoanPlanView } from '@/types/finance'

export const FIXTURE_F_PLAN = {
  "asOf": "2026-11-25",
  "monthlySurplus": 70000.0,
  "method": "avalanche",
  "plan": {
    "order": [
      {
        "loanId": "f-00",
        "label": "Credit card",
        "step": "pay_in_full",
        "ratePct": 42,
        "dueDate": null
      },
      {
        "loanId": "f-01",
        "label": "Brother",
        "step": "date_bound",
        "ratePct": 0,
        "dueDate": "2027-03-31"
      },
      {
        "loanId": "f-02",
        "label": "Gold loan",
        "step": "date_bound",
        "ratePct": 9.5,
        "dueDate": "2027-05-31"
      }
    ],
    "monthly": [
      {
        "month": "2026-12",
        "payments": {
          "f-00": 60000,
          "f-01": 10000.0
        },
        "investable": 0
      },
      {
        "month": "2027-01",
        "payments": {
          "f-02": 40000.0,
          "f-01": 30000
        },
        "investable": 0
      },
      {
        "month": "2027-02",
        "payments": {
          "f-02": 40000.0,
          "f-01": 30000
        },
        "investable": 0
      },
      {
        "month": "2027-03",
        "payments": {
          "f-02": 40000.0,
          "f-01": 30000.0
        },
        "investable": 0
      },
      {
        "month": "2027-04",
        "payments": {
          "f-02": 49474
        },
        "investable": 20526
      },
      {
        "month": "2027-05",
        "payments": {
          "f-02": 49474.0
        },
        "investable": 20526
      },
      {
        "month": "2027-06",
        "payments": {},
        "investable": 70000
      },
      {
        "month": "2027-07",
        "payments": {},
        "investable": 70000
      }
    ],
    "loans": [
      {
        "loanId": "f-00",
        "debtFreeBy": "2026-12",
        "interestPaid": 0,
        "monthlySetAside": null
      },
      {
        "loanId": "f-02",
        "debtFreeBy": "2027-05",
        "interestPaid": 0,
        "monthlySetAside": 40000.0
      },
      {
        "loanId": "f-01",
        "debtFreeBy": "2027-03",
        "interestPaid": 0,
        "monthlySetAside": 10000.0
      }
    ],
    "goldLoans": [
      {
        "loanId": "f-02",
        "principal": 200000,
        "termDays": 364,
        "maturityAmount": 218948,
        "maturityInterest": 18948,
        "accruedDays": 177,
        "accruedInterest": 9214,
        "pledgedGrams": 30,
        "valuation": 280000,
        "ltvPct": 78.2,
        "capPct": 85,
        "warn": false,
        "maturityWithinWarning": false,
        "ltvBasis": "amountRepayableAtMaturity"
      }
    ],
    "interestSaved": 8199,
    "debtFreeBy": "2027-05",
    "dateBoundFirst": false
  },
  "labels": {
    "f-00": "Credit card",
    "f-02": "Gold loan",
    "f-01": "Brother"
  },
  "figuresNeedVerification": true
} as unknown as LoanPlanView
