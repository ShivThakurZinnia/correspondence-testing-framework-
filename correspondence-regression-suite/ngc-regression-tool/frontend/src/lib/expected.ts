export const ROLES = ['OWNER', 'JOINTOWNER', 'ANNUITANT', 'PRIMARYSERVICINGAGENT', 'PRIMARYWRITINGAGENT', 'PRIMARYBENEFICIARY', 'ASSIGNEE', 'PAYOR', 'ORGANIZATION'];
export const STATUSES = ['SENT_TO_CDS', 'PROCESSED', 'PARTIALLY_PROCESSED', 'SENT_TO_MERRILL', 'SENT_TO_EMAIL_SERVICE', 'REVERT_TO_PRINT'];
/**
 * NGC journey steps (event_journey, type prefix correspondence.generation.). Names are NGC's CorrespondenceJourneyEventType;
 * the help text says what NGC does when it records each one. NGC records none of them when CDS publishing is disabled
 * (CDS_DISABLE_PUBLISH) or the event carries SKIP_ZINNIA_LIVE_EVENT.
 */
export const JOURNEY_STEPS: { code: string; help: string }[] = [
  { code: 'requestValid', help: 'NGC accepted the incoming event (it passed validation). Recorded when the event is received.' },
  { code: 'requestStart', help: 'NGC started processing the event. Recorded once per event, after the document type is known.' },
  { code: 'requestSentToCDS', help: 'NGC published the letter request to CDS. Recorded once per party (target) that gets a letter.' },
  { code: 'requestSentToCorroEmailService', help: 'E-delivery-only flow: the request went to the email service instead of CDS.' },
  { code: 'requestSentToMerrill', help: 'The letter went to Merrill (SFTP) because the CDS flow does not apply. Rare in Dev.' },
  { code: 'caseStatusCompleted', help: 'NGC told Zinnia Live the case completed. Only after CDS reports the final status, so it depends on callbacks arriving.' },
];
export const JOURNEY = JOURNEY_STEPS.map((j) => j.code);
export const CALLBACKS = ['pdf.generation.started', 'pdf.generation.completed', 'eds.upload.completed', 'delivery.sent.print', 'delivery.sent.email', 'delivery.completed', 'delivery.completed.email'];

export interface TargetExp {
  role: string;
  status: string;
  deliveryType: string;
  emailNotification: string;
  agentType: string;
  documentType: string;
  xmlPresent: boolean;
}

export interface ExpectedState {
  incomingStatus: string;
  targets: TargetExp[];
  cdsPublished: boolean;
  cdsCount: string;
  journey: string[];
  callbacks: string[];
  noTech: boolean;
  noDead: boolean;
  xml: { on: boolean; role: string; exists: boolean; xsdValid: boolean; goldenCompare: boolean; ignore: string };
}

export const emptyTarget = (): TargetExp => ({ role: 'OWNER', status: 'SENT_TO_CDS', deliveryType: '', emailNotification: '', agentType: '', documentType: '', xmlPresent: false });

export function emptyExpected(): ExpectedState {
  return {
    incomingStatus: 'SENT_TO_CDS',
    targets: [emptyTarget()],
    cdsPublished: true,
    cdsCount: '',
    journey: ['requestValid', 'requestStart', 'requestSentToCDS'],
    callbacks: [],
    noTech: true,
    noDead: true,
    xml: { on: false, role: '', exists: true, xsdValid: false, goldenCompare: false, ignore: '' },
  };
}

export function buildExpected(s: ExpectedState): Record<string, unknown> {
  const out: Record<string, unknown> = { incomingEvent: { status: s.incomingStatus } };
  if (s.targets.length) {
    out.targets = s.targets.map((t) => {
      const o: Record<string, unknown> = { role: t.role };
      if (t.status) o.status = t.status;
      if (t.deliveryType) o.deliveryType = t.deliveryType;
      if (t.emailNotification) o.emailNotification = t.emailNotification;
      if (t.agentType) o.agentType = t.agentType;
      if (t.documentType) o.documentType = t.documentType;
      if (t.xmlPresent) o.xmlFileName = 'present';
      return o;
    });
  }
  const cds: Record<string, unknown> = {};
  if (s.cdsPublished) cds.published = true;
  if (s.cdsCount !== '') cds.count = Number(s.cdsCount);
  if (Object.keys(cds).length) out.cds = cds;
  if (s.journey.length) out.journey = s.journey;
  if (s.callbacks.length) out.callbacks = s.callbacks;
  const noFailures = [...(s.noTech ? ['technical_failures'] : []), ...(s.noDead ? ['dead_letter_events'] : [])];
  if (noFailures.length) out.noFailures = noFailures;
  if (s.xml.on) {
    const x: Record<string, unknown> = {};
    if (s.xml.role) x.role = s.xml.role;
    if (s.xml.exists) x.exists = true;
    if (s.xml.xsdValid) x.xsdValid = true;
    if (s.xml.goldenCompare) x.goldenCompare = true;
    const ignore = s.xml.ignore.split(',').map((i) => i.trim()).filter(Boolean);
    if (ignore.length) x.ignore = ignore;
    out.xml = x;
  }
  return out;
}

function canon(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(canon).join(',')}]`;
  if (v && typeof v === 'object') {
    return `{${Object.keys(v as object).sort().map((k) => `${JSON.stringify(k)}:${canon((v as Record<string, unknown>)[k])}`).join(',')}}`;
  }
  return JSON.stringify(v);
}

/** Reads expected JSON into the form model; null when it uses options the form cannot represent (edit as JSON instead). */
export function parseExpected(text: string): ExpectedState | null {
  let raw: any;
  try {
    raw = JSON.parse(text);
  } catch {
    return null;
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const x = raw.xml ?? {};
  const s: ExpectedState = {
    incomingStatus: raw.incomingEvent?.status ?? '',
    targets: (raw.targets ?? []).map((t: any) => ({
      role: t.role ?? '',
      status: t.status ?? '',
      deliveryType: t.deliveryType ?? '',
      emailNotification: t.emailNotification ?? '',
      agentType: t.agentType ?? '',
      documentType: t.documentType ?? '',
      xmlPresent: t.xmlFileName === 'present',
    })),
    cdsPublished: raw.cds?.published === true,
    cdsCount: raw.cds?.count != null ? String(raw.cds.count) : '',
    journey: raw.journey ?? [],
    callbacks: raw.callbacks ?? [],
    noTech: (raw.noFailures ?? []).includes('technical_failures'),
    noDead: (raw.noFailures ?? []).includes('dead_letter_events'),
    xml: {
      on: raw.xml !== undefined,
      role: x.role ?? '',
      exists: raw.xml === undefined ? true : x.exists === true,
      xsdValid: x.xsdValid === true,
      goldenCompare: x.goldenCompare === true,
      ignore: (x.ignore ?? []).join(', '),
    },
  };
  return canon(buildExpected(s)) === canon(raw) ? s : null;
}

export const GILI_POLICY_PAGE_TEMPLATE: ExpectedState = {
  ...emptyExpected(),
  targets: [{ role: 'OWNER', status: 'SENT_TO_CDS', deliveryType: 'PRINT', emailNotification: '', agentType: '', documentType: 'POLPG', xmlPresent: true }],
  cdsCount: '1',
};
