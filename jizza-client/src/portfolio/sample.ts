/**
 * "Look around with sample data" (7.12): Fixture D's household, two earners and a child, as the
 * API would return it. Every person, amount and id is fictional and nothing here is ever saved.
 * Goal funding is 78% (goals arrive in Phase 1b), which gives the spec's score of 38.
*/
import type {
  CashFlowView, FinAction, FinAsset, FinEstate, FinLoan, FinLoanGiven, FinPolicy, FinProfileView, HealthView,
  LoanPlanView, NetWorthView, ProtectionView,
} from '@/types/finance'

export const SAMPLE_AS_OF = '2026-11-25'

export const SAMPLE_PROFILE = {
  "profile": {
    "id": "sample-00",
    "baseCurrency": "INR",
    "version": 6
  },
  "people": [
    {
      "id": "sample-01",
      "userId": "sample",
      "displayName": "Me",
      "kind": "self",
      "type": "individual",
      "dob": "1992-06-01",
      "earning": true,
      "dependent": false,
      "status": "active",
      "sortOrder": 0,
      "handedOverToUserId": null
    },
    {
      "id": "sample-03",
      "userId": "sample",
      "displayName": "Spouse",
      "kind": "spouse",
      "type": "individual",
      "dob": "1994-06-01",
      "earning": true,
      "dependent": false,
      "status": "active",
      "sortOrder": 1,
      "handedOverToUserId": null
    },
    {
      "id": "sample-04",
      "userId": "sample",
      "displayName": "Child",
      "kind": "child",
      "type": "minor",
      "dob": "2020-06-01",
      "earning": false,
      "dependent": true,
      "status": "active",
      "sortOrder": 2,
      "handedOverToUserId": null
    }
  ],
  "settings": {
    "advanced": false,
    "hideAmounts": false,
    "mutedAlerts": []
  },
  "setupProgress": {
    "quickStart": "done",
    "peopleManaged": 2
  }
} as unknown as FinProfileView

export const SAMPLE_HEALTH = {
  "asOf": "2026-11-25",
  "score": 38,
  "rawScore": 37.5,
  "checks": [
    {
      "key": "emergencyFund",
      "status": "amber",
      "value": 3.2051282051,
      "shown": 3.2,
      "unit": "months",
      "target": 6,
      "weight": 20,
      "points": 10.0,
      "facts": {
        "essentialMonthly": 78000.0,
        "liquid": 250000
      }
    },
    {
      "key": "debt",
      "status": "red",
      "value": 16.170212766,
      "shown": 16.2,
      "unit": "pctOfGrossIncome",
      "target": 30,
      "weight": 20,
      "points": 0,
      "facts": {
        "grossMonthly": 235000,
        "highInterestLoanIds": [
          "sample-05"
        ],
        "debtPayments": 38000
      }
    },
    {
      "key": "savingsRate",
      "status": "amber",
      "value": 16.6666666667,
      "shown": 16.7,
      "unit": "pctOfTakeHome",
      "target": 20,
      "weight": 20,
      "points": 10.0,
      "facts": {
        "investedMonthly": 30000,
        "takeHome": 180000
      }
    },
    {
      "key": "goalFunding",
      "status": "amber",
      "value": 78,
      "shown": 78,
      "unit": "pctFunded",
      "target": 90,
      "weight": 15,
      "points": 7.5,
      "facts": {}
    },
    {
      "key": "protection",
      "status": "red",
      "value": 3,
      "shown": 3.0,
      "unit": "gaps",
      "target": 0,
      "weight": 15,
      "points": 0,
      "facts": {
        "affordabilityFlag": false
      }
    },
    {
      "key": "spendingControl",
      "status": "green",
      "value": 43.3333333333,
      "shown": 43.3,
      "unit": "needsPctOfTakeHome",
      "target": 50,
      "weight": 10,
      "points": 10,
      "facts": {
        "takeHome": 180000,
        "essentialMonthly": 78000.0
      }
    }
  ],
  "trend": [
    {
      "at": "2026-09-01T00:30:00Z",
      "score": 31
    },
    {
      "at": "2026-10-01T00:30:00Z",
      "score": 34
    },
    {
      "at": "2026-11-01T00:30:00Z",
      "score": 37.5
    }
  ],
  "flags": {
    "nomineeMissing": false,
    "needsVerification": true,
    "willMissing": true
  },
  "surplus": 22000.0,
  "essentialMonthly": 78000.0,
  "topActions": [
    {
      "id": "sample-06",
      "personId": null,
      "actionKey": "debt.high_interest",
      "layer": "survival",
      "title": "Repay the ₹1.5 lakh personal loan at 14%",
      "reason": "Use the ₹22,000 unallocated monthly surplus on top of the ₹8,000 EMI: cleared in about 6 months. The emergency fund is not used for this.",
      "impactPoints": 20,
      "rank": 1,
      "details": {
        "months": 6,
        "loanIds": [
          "sample-05"
        ],
        "debtFreeBy": "2027-05",
        "outstanding": 150000,
        "monthlyExtra": 22000,
        "fundingSource": "monthly_surplus"
      },
      "status": "open",
      "snoozedUntil": null,
      "dismissReason": null,
      "sourceCheck": "debt",
      "dueDate": null,
      "createdAt": "2026-11-01T00:30:00Z",
      "updatedAt": "2026-11-01T00:30:00Z"
    },
    {
      "id": "sample-07",
      "personId": null,
      "actionKey": "protection.gaps",
      "layer": "survival",
      "title": "Close protection gaps",
      "reason": "Close 3 gaps: term cover of about ₹2.72 crore for Me and ₹1.35 crore for Spouse; a personal family floater of at least ₹10 lakh with a super top-up. Employer cover is shown but does not count. Kai never recommends an insurer.",
      "impactPoints": 15,
      "rank": 2,
      "details": {
        "gaps": [
          {
            "kind": "term",
            "amount": 27200000,
            "personIds": [
              "sample-01"
            ]
          },
          {
            "kind": "term",
            "amount": 13500000,
            "personIds": [
              "sample-03"
            ]
          },
          {
            "kind": "health",
            "amount": 1000000,
            "personIds": [
              "sample-01",
              "sample-03",
              "sample-04"
            ]
          }
        ]
      },
      "status": "open",
      "snoozedUntil": null,
      "dismissReason": null,
      "sourceCheck": "protection",
      "dueDate": null,
      "createdAt": "2026-11-01T00:30:00Z",
      "updatedAt": "2026-11-01T00:30:00Z"
    },
    {
      "id": "sample-08",
      "personId": null,
      "actionKey": "emergency.full",
      "layer": "survival",
      "title": "Grow the emergency fund to ₹4.68 lakh",
      "reason": "It covers 3.2 months of essential spending; the target is 6 months: ₹4.68 lakh today (6 × ₹78,000, a gap of ₹2.18 lakh), or ₹4.2 lakh once the loan EMI stops. Start after the loan is repaid.",
      "impactPoints": 10,
      "rank": 3,
      "details": {
        "gap": 218000,
        "months": 3.2,
        "target": 468000,
        "targetAfterDebt": 420000,
        "essentialMonthly": 78000
      },
      "status": "open",
      "snoozedUntil": null,
      "dismissReason": null,
      "sourceCheck": "emergencyFund",
      "dueDate": null,
      "createdAt": "2026-11-01T00:30:00Z",
      "updatedAt": "2026-11-01T00:30:00Z"
    }
  ]
} as unknown as HealthView

export const SAMPLE_ACTIONS = [
  {
    "id": "sample-06",
    "personId": null,
    "actionKey": "debt.high_interest",
    "layer": "survival",
    "title": "Repay the ₹1.5 lakh personal loan at 14%",
    "reason": "Use the ₹22,000 unallocated monthly surplus on top of the ₹8,000 EMI: cleared in about 6 months. The emergency fund is not used for this.",
    "impactPoints": 20,
    "rank": 1,
    "details": {
      "months": 6,
      "loanIds": [
        "sample-05"
      ],
      "debtFreeBy": "2027-05",
      "outstanding": 150000,
      "monthlyExtra": 22000,
      "fundingSource": "monthly_surplus"
    },
    "status": "open",
    "snoozedUntil": null,
    "dismissReason": null,
    "sourceCheck": "debt",
    "dueDate": null,
    "createdAt": "2026-11-01T00:30:00Z",
    "updatedAt": "2026-11-01T00:30:00Z"
  },
  {
    "id": "sample-07",
    "personId": null,
    "actionKey": "protection.gaps",
    "layer": "survival",
    "title": "Close protection gaps",
    "reason": "Close 3 gaps: term cover of about ₹2.72 crore for Me and ₹1.35 crore for Spouse; a personal family floater of at least ₹10 lakh with a super top-up. Employer cover is shown but does not count. Kai never recommends an insurer.",
    "impactPoints": 15,
    "rank": 2,
    "details": {
      "gaps": [
        {
          "kind": "term",
          "amount": 27200000,
          "personIds": [
            "sample-01"
          ]
        },
        {
          "kind": "term",
          "amount": 13500000,
          "personIds": [
            "sample-03"
          ]
        },
        {
          "kind": "health",
          "amount": 1000000,
          "personIds": [
            "sample-01",
            "sample-03",
            "sample-04"
          ]
        }
      ]
    },
    "status": "open",
    "snoozedUntil": null,
    "dismissReason": null,
    "sourceCheck": "protection",
    "dueDate": null,
    "createdAt": "2026-11-01T00:30:00Z",
    "updatedAt": "2026-11-01T00:30:00Z"
  },
  {
    "id": "sample-08",
    "personId": null,
    "actionKey": "emergency.full",
    "layer": "survival",
    "title": "Grow the emergency fund to ₹4.68 lakh",
    "reason": "It covers 3.2 months of essential spending; the target is 6 months: ₹4.68 lakh today (6 × ₹78,000, a gap of ₹2.18 lakh), or ₹4.2 lakh once the loan EMI stops. Start after the loan is repaid.",
    "impactPoints": 10,
    "rank": 3,
    "details": {
      "gap": 218000,
      "months": 3.2,
      "target": 468000,
      "targetAfterDebt": 420000,
      "essentialMonthly": 78000
    },
    "status": "open",
    "snoozedUntil": null,
    "dismissReason": null,
    "sourceCheck": "emergencyFund",
    "dueDate": null,
    "createdAt": "2026-11-01T00:30:00Z",
    "updatedAt": "2026-11-01T00:30:00Z"
  },
  {
    "id": "sample-09",
    "personId": null,
    "actionKey": "hygiene.will",
    "layer": "hygiene",
    "title": "Record a will",
    "reason": "No will is recorded.",
    "impactPoints": 0,
    "rank": 4,
    "details": {},
    "status": "later",
    "snoozedUntil": null,
    "dismissReason": null,
    "sourceCheck": null,
    "dueDate": null,
    "createdAt": "2026-11-01T00:30:00Z",
    "updatedAt": "2026-11-01T00:30:00Z"
  }
] as unknown as FinAction[]

export const SAMPLE_CASH_FLOW = {
  "asOf": "2026-11-25",
  "month": "2026-11",
  "income": [
    {
      "personId": "sample-03",
      "name": "Spouse",
      "grossMonthly": 75000,
      "takeHomeMonthly": 60000
    },
    {
      "personId": "sample-01",
      "name": "Me",
      "grossMonthly": 160000,
      "takeHomeMonthly": 120000
    }
  ],
  "spendByBucket": {
    "need": 78000,
    "want": 50000,
    "save": 30000
  },
  "takeHome": 180000,
  "essential": 78000,
  "loanPayments": 38000,
  "premiumsMonthly": 0,
  "surplus": 22000,
  "surplusUse": [
    {
      "key": "debt",
      "label": "Loans (debt plan)",
      "amount": 22000
    }
  ],
  "needsWantsSavePct": {
    "need": 43.3,
    "want": 27.8,
    "save": 16.7
  }
} as unknown as CashFlowView

export const SAMPLE_NET_WORTH = {
  "asOf": "2026-11-25",
  "assets": 250000,
  "liabilities": 3650000,
  "netWorth": -3400000,
  "pledged": 0,
  "byClass": [
    {
      "key": "cash",
      "assets": 250000,
      "liabilities": 0,
      "netWorth": 250000
    }
  ],
  "byPerson": [
    {
      "key": "sample-01",
      "assets": 250000,
      "liabilities": 3650000,
      "netWorth": -3400000
    },
    {
      "key": "sample-03",
      "assets": 0,
      "liabilities": 0,
      "netWorth": 0
    },
    {
      "key": "sample-04",
      "assets": 0,
      "liabilities": 0,
      "netWorth": 0
    }
  ],
  "trend": [
    {
      "asOf": "2026-09-01",
      "assets": 200000,
      "liabilities": 3720000,
      "netWorth": -3520000
    },
    {
      "asOf": "2026-10-01",
      "assets": 225000,
      "liabilities": 3685000,
      "netWorth": -3460000
    },
    {
      "asOf": "2026-11-01",
      "assets": 250000,
      "liabilities": 3650000,
      "netWorth": -3400000
    }
  ]
} as unknown as NetWorthView

export const SAMPLE_LOANS = [
  {
    "id": "sample-10",
    "personId": "sample-01",
    "isJoint": false,
    "ownerSharePct": 100,
    "jointUserIds": [],
    "jointLabel": null,
    "kind": "HOME",
    "lenderType": "bank",
    "lenderName": null,
    "isPrivate": false,
    "principalTaken": null,
    "outstanding": 3500000,
    "rateInput": "8.5%",
    "effectiveAnnualRate": 8.5,
    "rateType": "fixed",
    "repaymentMode": "emi",
    "emiOrInterest": 30000,
    "startDate": null,
    "maturityOrPromisedDate": null,
    "prepaymentPenaltyPct": null,
    "taxBenefit": "none",
    "collateralType": "property",
    "status": "active",
    "pledges": [],
    "label": "Home loan",
    "sharedLabel": "Home loan",
    "ltv": null
  },
  {
    "id": "sample-05",
    "personId": "sample-01",
    "isJoint": false,
    "ownerSharePct": 100,
    "jointUserIds": [],
    "jointLabel": null,
    "kind": "PERSONAL",
    "lenderType": "bank",
    "lenderName": null,
    "isPrivate": false,
    "principalTaken": null,
    "outstanding": 150000,
    "rateInput": "14% a year",
    "effectiveAnnualRate": 14,
    "rateType": "fixed",
    "repaymentMode": "emi",
    "emiOrInterest": 8000,
    "startDate": null,
    "maturityOrPromisedDate": null,
    "prepaymentPenaltyPct": null,
    "taxBenefit": "none",
    "collateralType": "none",
    "status": "active",
    "pledges": [],
    "label": "Personal loan",
    "sharedLabel": "Personal loan",
    "ltv": null
  }
] as unknown as FinLoan[]

export const SAMPLE_PLAN = {
  "asOf": "2026-11-25",
  "monthlySurplus": 22000.0,
  "method": "avalanche",
  "plan": {
    "order": [
      {
        "loanId": "sample-05",
        "label": "Personal loan",
        "step": "prepay",
        "ratePct": 14,
        "dueDate": null
      },
      {
        "loanId": "sample-10",
        "label": "Home loan",
        "step": "keep_emi",
        "ratePct": 8.5,
        "dueDate": null
      }
    ],
    "monthly": [
      {
        "month": "2026-12",
        "payments": {
          "sample-05": 22000.0
        },
        "investable": 0
      },
      {
        "month": "2027-01",
        "payments": {
          "sample-05": 22000.0
        },
        "investable": 0
      },
      {
        "month": "2027-02",
        "payments": {
          "sample-05": 22000.0
        },
        "investable": 0
      },
      {
        "month": "2027-03",
        "payments": {
          "sample-05": 22000.0
        },
        "investable": 0
      },
      {
        "month": "2027-04",
        "payments": {
          "sample-05": 22000.0
        },
        "investable": 0
      },
      {
        "month": "2027-05",
        "payments": {},
        "investable": 22000
      },
      {
        "month": "2027-06",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2027-07",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2027-08",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2027-09",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2027-10",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2027-11",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2027-12",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2028-01",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2028-02",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2028-03",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2028-04",
        "payments": {},
        "investable": 30000
      },
      {
        "month": "2028-05",
        "payments": {},
        "investable": 30000
      }
    ],
    "loans": [
      {
        "loanId": "sample-10",
        "debtFreeBy": "2047-08",
        "interestPaid": 3941975,
        "monthlySetAside": null
      },
      {
        "loanId": "sample-05",
        "debtFreeBy": "2027-05",
        "interestPaid": 5479,
        "monthlySetAside": null
      }
    ],
    "goldLoans": [],
    "interestSaved": 14792,
    "debtFreeBy": "2047-08",
    "dateBoundFirst": false
  },
  "labels": {
    "sample-10": "Home loan",
    "sample-05": "Personal loan"
  },
  "figuresNeedVerification": true
} as unknown as LoanPlanView

export const SAMPLE_POLICIES = [
  {
    "id": "sample-11",
    "personId": "sample-01",
    "kind": "term",
    "providerName": null,
    "sumAssured": 5000000,
    "deductible": null,
    "premium": null,
    "premiumFrequency": "yearly",
    "renewalDate": null,
    "policyEndDate": null,
    "employerProvided": false,
    "coveredPersonIds": [],
    "surrenderValue": null,
    "documentFileId": null
  },
  {
    "id": "sample-12",
    "personId": "sample-01",
    "kind": "health",
    "providerName": null,
    "sumAssured": 500000,
    "deductible": null,
    "premium": null,
    "premiumFrequency": "yearly",
    "renewalDate": null,
    "policyEndDate": null,
    "employerProvided": true,
    "coveredPersonIds": [
      "sample-01",
      "sample-03",
      "sample-04"
    ],
    "surrenderValue": null,
    "documentFileId": null
  }
] as unknown as FinPolicy[]

export const SAMPLE_PROTECTION = {
  "asOf": "2026-11-25",
  "known": true,
  "protection": {
    "perPerson": [
      {
        "personId": "sample-01",
        "name": "Me",
        "needsTerm": true,
        "method": "MULTIPLE",
        "multiple": 15,
        "termNeed": 32200000,
        "termCover": 5000000,
        "termGap": 27200000,
        "personalHealthCover": 0,
        "employerHealthCover": 500000,
        "superTopUp": 0
      },
      {
        "personId": "sample-03",
        "name": "Spouse",
        "needsTerm": true,
        "method": "MULTIPLE",
        "multiple": 15,
        "termNeed": 13500000,
        "termCover": 0,
        "termGap": 13500000,
        "personalHealthCover": 0,
        "employerHealthCover": 500000,
        "superTopUp": 0
      },
      {
        "personId": "sample-04",
        "name": "Child",
        "needsTerm": false,
        "method": "MULTIPLE",
        "multiple": 15,
        "termNeed": 0,
        "termCover": 0,
        "termGap": 0,
        "personalHealthCover": 0,
        "employerHealthCover": 500000,
        "superTopUp": 0
      }
    ],
    "gaps": [
      {
        "kind": "term",
        "personIds": [
          "sample-01"
        ],
        "amount": 27200000
      },
      {
        "kind": "term",
        "personIds": [
          "sample-03"
        ],
        "amount": 13500000
      },
      {
        "kind": "health",
        "personIds": [
          "sample-01",
          "sample-03",
          "sample-04"
        ],
        "amount": 1000000
      }
    ],
    "premiumsYearly": 0,
    "premiumsPctOfIncome": 0.0,
    "affordabilityFlag": false,
    "healthFloor": 1000000
  },
  "names": {
    "sample-01": "Me",
    "sample-03": "Spouse",
    "sample-04": "Child"
  }
} as unknown as ProtectionView

export const SAMPLE_ASSETS = [
  {
    "id": "sample-13",
    "personId": "sample-01",
    "accountId": null,
    "kind": "savings_balance",
    "name": "Savings balance",
    "assetClass": "cash",
    "subCategory": null,
    "value": 250000,
    "valueAsOf": "2026-11-25",
    "rate": null,
    "maturityDate": null,
    "grams": null,
    "purityKarat": null,
    "isEmergency": true,
    "isEstimate": true,
    "pledged": false
  }
] as unknown as FinAsset[]

export const SAMPLE_LOANS_GIVEN = [] as unknown as FinLoanGiven[]

export const SAMPLE_ESTATE = {
  "personId": "sample-01",
  "willStatus": "none"
} as unknown as FinEstate
