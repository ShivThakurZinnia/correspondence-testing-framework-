import { useQuery } from '@tanstack/react-query';
import { api, json } from '../lib/api';
import { minimumDuration } from '../lib/runs';
import { describeSelection, tagExpression, tagRef, toggleTag } from '../lib/tags';
import type { RunPreview, TagCounts } from '../lib/types';

interface Props {
  /** Limit to one module; without it the counts and the selection cover every module. */
  module?: string;
  env: string;
  versionNo?: number | null;
  selected: string[];
  onChange: (refs: string[]) => void;
  /** Show the "N events" box (the run dialog has its own, with pacing). */
  showPreview?: boolean;
}

const KINDS = [
  { category: 'MAPPER', title: 'Mappers', hint: 'Pick several: test cases of any selected mapper run' },
  { category: 'CLIENT', title: 'Clients', hint: '' },
  { category: 'PRIORITY', title: 'Priority', hint: '' },
] as const;

/**
 * Multi-select for tags, showing how many events each tag selects, and the total for the whole
 * selection. Within a kind (e.g. mappers) the choices are alternatives; across kinds they must all match.
 */
export function TagPicker({ module, env, versionNo = null, selected, onChange, showPreview = true }: Props) {
  const counts = useQuery({
    queryKey: ['tag-counts', module, versionNo],
    queryFn: () => api<TagCounts>(module ? `/api/modules/${module}/tag-counts${versionNo ? `?versionNo=${versionNo}` : ''}` : '/api/modules/tag-counts'),
  });
  const body = { module: module ?? null, env, versionNo, tags: tagExpression(selected), trigger: 'SCHEDULE' };
  const preview = useQuery({
    queryKey: ['selection-preview', body],
    queryFn: () => api<RunPreview>('/api/runs/preview', { method: 'POST', ...json(body) }),
    enabled: !!env && showPreview,
    retry: false,
  });
  const p = preview.data;

  return (
    <div className="tag-picker">
      {counts.data && (
        <p className="hint">
          {module ? (counts.data.version ? `Test module v${counts.data.version}` : 'No test cases yet') : 'All test modules'} · {counts.data.total_cases} test case{counts.data.total_cases === 1 ? '' : 's'} · {counts.data.total_events} event{counts.data.total_events === 1 ? '' : 's'} in total
        </p>
      )}
      {counts.data && counts.data.no_mapper_cases > 0 && (
        <p className="note warn-note" role="status">
          {counts.data.no_mapper_cases} test case{counts.data.no_mapper_cases === 1 ? '' : 's'} ({counts.data.no_mapper_events} event{counts.data.no_mapper_events === 1 ? '' : 's'}) {counts.data.no_mapper_cases === 1 ? 'is' : 'are'} not in any mapper group, so {counts.data.no_mapper_cases === 1 ? 'it runs' : 'they run'} only when no mapper is selected. Open the test case and choose its mapper.
        </p>
      )}
      {KINDS.map((kind) => {
        const tags = counts.data?.tags.filter((t) => t.category === kind.category) ?? [];
        if (tags.length === 0) return null;
        const ofKind = tags.map((t) => tagRef(t.category, t.code));
        const withTests = tags.filter((t) => t.events > 0).map((t) => tagRef(t.category, t.code));
        return (
          <fieldset key={kind.category} className="tag-kind">
            <legend>
              {kind.title}
              {kind.hint && <span className="hint"> — {kind.hint}</span>}
            </legend>
            {kind.category === 'MAPPER' && (
              <div className="kind-actions">
                <button type="button" className="link-btn" onClick={() => onChange([...selected.filter((r) => !ofKind.includes(r)), ...withTests])}>All with tests</button>
                <button type="button" className="link-btn" onClick={() => onChange(selected.filter((r) => !ofKind.includes(r)))}>None</button>
              </div>
            )}
            <div className="tag-options">
              {tags.map((t) => {
                const ref = tagRef(t.category, t.code);
                const on = selected.includes(ref);
                return (
                  <button
                    type="button"
                    key={ref}
                    aria-pressed={on}
                    className={`tag-option${on ? ' on' : ''}${t.events === 0 ? ' empty' : ''}`}
                    title={`${t.cases} test case${t.cases === 1 ? '' : 's'} · ${t.events} event${t.events === 1 ? '' : 's'} (one event per plan code)`}
                    onClick={() => onChange(toggleTag(selected, ref))}
                  >
                    <span className="tag-name">{t.label}</span>
                    <span className="tag-count" aria-label={`${t.events} events`}>{t.events}</span>
                  </button>
                );
              })}
            </div>
          </fieldset>
        );
      })}
      {showPreview && (
        <div className={p?.requiresConfirmation ? 'preview-box warn' : 'preview-box'} aria-live="polite">
          {preview.isLoading && 'Counting events…'}
          {preview.error && <span className="bad-text">{(preview.error as Error).message}</span>}
          {p && (
            <>
              <strong>{p.events} event{p.events === 1 ? '' : 's'}</strong> from {p.cases} test case{p.cases === 1 ? '' : 's'} · {describeSelection(selected)} · at least {minimumDuration(p.minimumSeconds)} to send
            </>
          )}
        </div>
      )}
    </div>
  );
}
