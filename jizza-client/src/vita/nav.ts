/**
 * The Health tab's views and their labels (spec 7.1), in sub-navigation order. `phase` is the build
 * phase that brings a view; a view from a later phase shows what it will hold until then.
 */
export interface HealthView {
  id: string
  label: string
  phase: string
  /** One line for the placeholder of a view that is not built yet, and for its empty state. */
  about: string
  /** Reached from another view, not listed in the sub-navigation. */
  hidden?: boolean
  /** Starts a new group in the sub-navigation. */
  groupStart?: boolean
  /** What "Ask Vita" starts the question with on this view. */
  ask: string
}

export const HEALTH_VIEWS: HealthView[] = [
  { id: 'home', label: 'Home', phase: '0', about: 'Today’s medicines, the latest readings and what is coming up.', ask: 'About today: ' },
  { id: 'weekly-note', label: 'Weekly note', phase: '4', about: 'What changed this week, from the record: medicine changes, missed doses, readings outside range, visits and refills.', ask: 'About this week: ' },
  { id: 'conditions', label: 'Conditions', phase: '0', about: 'Long-term conditions, each with its own page of medicines, readings and reports.', ask: 'About this condition: ' },
  { id: 'medicines', label: 'Medicines', phase: '1a', about: 'Each medicine with its times, stock and refills, and the reminders on the phone.', ask: 'About these medicines: ' },
  { id: 'vitals', label: 'Vitals', phase: '1a', about: 'Blood pressure, sugar, weight and pulse, with a 7-day diary and a chart against the target.', ask: 'About these readings: ' },
  { id: 'visits', label: 'Doctors and visits', phase: '1a', about: 'Doctors with their phone, the next visit, and the targets each doctor set.', ask: 'Help me prepare: ' },
  { id: 'labs', label: 'Lab results', phase: '1b', about: 'Lab reports read for you, each test with its trend; nothing is saved until you confirm it.', ask: 'About this report: ' },
  { id: 'documents', label: 'Documents', phase: '1b', about: 'Prescriptions, reports and discharge papers, kept encrypted.', ask: 'Find in documents: ' },
  { id: 'vaccinations', label: 'Vaccinations', phase: '1b', about: 'Doses and their certificates.', ask: 'About vaccinations: ' },
  { id: 'surgeries', label: 'Surgeries and stays', phase: '1b', about: 'Operations and hospital stays.', ask: 'About this stay: ' },
  { id: 'allergies', label: 'Allergies', phase: '0', about: 'Allergies, shown first on the emergency card.', ask: 'About allergies: ' },
  { id: 'sharing', label: 'Sharing', phase: '2a', groupStart: true, about: 'Who in your Family sees which part of a record, with a consent note.', ask: 'About sharing: ' },
  { id: 'insurance', label: 'Insurance', phase: '2b', about: 'Health policies, government and employer cover, and claims.', ask: 'About this policy: ' },
  { id: 'journal', label: 'Journal', phase: '2c', about: 'A daily check-in of mood, energy, pain and symptoms in under 30 seconds.', ask: 'About how I have been: ' },
  { id: 'goals', label: 'Goals', phase: '2c', about: 'Targets with a weekly plan and check-ins.', ask: 'About this goal: ' },
  { id: 'activity', label: 'Activity and sleep', phase: '3', about: 'Steps, sleep and resting heart rate from a phone or watch.', ask: 'About activity and sleep: ' },
  { id: 'emergency', label: 'Emergency card', phase: '0', groupStart: true, about: 'What a stranger or a hospital needs in the first minutes.', ask: 'About the emergency card: ' },
  { id: 'connect', label: 'Connect', phase: '1b', about: 'Upload reports, and pull them from your mailbox.', ask: 'About connecting: ' },
  { id: 'settings', label: 'Settings', phase: '0', about: 'Units, emergency numbers and the figures Vita uses, with where each comes from.', ask: 'About settings: ' },
  { id: 'insights', label: 'Goes with', phase: '4', hidden: true, about: 'What in the journal goes together with sleep, steps, missed doses or readings.', ask: 'About what goes together: ' },
  { id: 'profile', label: 'Profile', phase: '0', hidden: true, about: 'Date of birth, blood group, height, hospital and reminder mode.', ask: 'About this profile: ' },
  { id: 'visit-prep', label: 'Visit prep', phase: '1b', hidden: true, about: 'What to take to a visit: questions, readings, medicines, changes and labs.', ask: 'Help me prepare: ' },
  { id: 'summary', label: 'Health summary', phase: '1b', hidden: true, about: 'A print-ready summary of the record.', ask: 'About this summary: ' },
  { id: 'lab', label: 'Lab report', phase: '1b', hidden: true, about: 'One lab report with each test, its lab flag and its target.', ask: 'About this report: ' },
  { id: 'document', label: 'Document', phase: '1b', hidden: true, about: 'One document beside what Vita read from it, to check before saving.', ask: 'About this document: ' },
  { id: 'condition', label: 'Condition', phase: '1a', hidden: true, about: 'One condition with its readings, checks and medicines.', ask: 'About this condition: ' },
  { id: 'quick-start', label: 'Quick start', phase: '0', hidden: true, about: 'Eight short questions that set up the record and the emergency card.', ask: 'Helping me through Quick start: ' },
]

export const DEFAULT_VIEW = 'home'

export function findView(id: string | null | undefined): HealthView {
  return HEALTH_VIEWS.find(v => v.id === id) ?? HEALTH_VIEWS[0]
}

/** Views built in this release; the rest show what they will hold. */
export const BUILT_VIEWS = new Set(['home', 'sharing', 'insurance', 'journal', 'goals', 'activity', 'weekly-note', 'insights', 'conditions', 'condition', 'medicines', 'vitals', 'visits', 'labs', 'lab', 'documents', 'document', 'vaccinations', 'surgeries', 'connect', 'visit-prep', 'summary', 'allergies', 'emergency', 'settings', 'profile', 'quick-start'])

/** The update that brings each later phase, in plain words. */
export const PHASE_WORDS: Record<string, string> = {
  '1b': 'the next update, with vaccinations, stays and Connect',
  '2a': 'the Family update',
  '2b': 'the insurance update',
  '2c': 'the journal and goals update',
  '3': 'the phone and watch update',
}
