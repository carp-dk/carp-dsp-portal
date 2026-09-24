/**
 * WorkflowArtifact types, mirrored from carp.core-kotlin @ feature/core-analytics,
 * carp.analytics.core/.../analytics/application/data/.
 *
 * These are already implemented upstream (W2-1 steps 1-8), so the mock returns
 * the real shapes rather than invented ones. Keep in sync by hand - if a leaf
 * changes upstream, change it here.
 *
 * DISCRIMINATOR: artefacts travel on core's convention, which is `__type`, NOT
 * `type`. CoreAnalyticsSerializer uses `type` internally, but W2-1 step 10
 * settled that artefacts use core's. Mock JSON must emit `__type` or the real
 * client will fail to deserialise the day it is swapped in.
 */

export const DSP_NAMESPACE = 'dk.cachet.carp.dsp';
export const ANALYTIC_NAMESPACE = `${DSP_NAMESPACE}.analytic`;
export const MEDIA_NAMESPACE = `${DSP_NAMESPACE}.media`;
export const CONTROL_NAMESPACE = `${DSP_NAMESPACE}.control`;

/**
 * The branch is encoded in the flat type string so string-only consumers can
 * prefix-filter. That is how the results view groups artefacts.
 */
export type ArtifactType =
  | 'dk.cachet.carp.dsp.generic'
  | 'dk.cachet.carp.dsp.analytic.feature'
  | 'dk.cachet.carp.dsp.analytic.summary'
  | 'dk.cachet.carp.dsp.analytic.classification'
  | 'dk.cachet.carp.dsp.analytic.table'
  | 'dk.cachet.carp.dsp.media.image'
  | 'dk.cachet.carp.dsp.media.plot'
  | 'dk.cachet.carp.dsp.media.document'
  | 'dk.cachet.carp.dsp.control.trigger';

/** Every file-backed artefact points through this. Never inline bytes. */
export interface ArtifactReference {
  uri: string;
  mimeType: string;
  sizeBytes: number;
  /** Lowercase hex, 64 chars. */
  sha256: string;
}

// ---- AnalyticResult ---------------------------------------------------------

export interface ComputedFeature {
  __type: 'dk.cachet.carp.dsp.analytic.feature';
  name: string;
  value: number;
  unit?: string | null;
}

/** A ComputedFeature that also knows how many observations it describes. */
export interface SummaryStatistic {
  __type: 'dk.cachet.carp.dsp.analytic.summary';
  name: string;
  value: number;
  sampleCount: number;
  unit?: string | null;
}

export interface Classification {
  __type: 'dk.cachet.carp.dsp.analytic.classification';
  label: string;
  /** 0..1 when present. */
  confidence?: number | null;
  modelRef?: string | null;
}

/** The reference-backed analytic result, e.g. features.parquet. */
export interface FeatureTable {
  __type: 'dk.cachet.carp.dsp.analytic.table';
  reference: ArtifactReference;
  columns: string[];
  rowCount?: number | null;
}

// ---- MediaArtifact ----------------------------------------------------------

export interface ImageArtifact {
  __type: 'dk.cachet.carp.dsp.media.image';
  reference: ArtifactReference;
  widthPixels?: number | null;
  heightPixels?: number | null;
}

/**
 * The one media type carrying a payload. `spec` travels inline so a client can
 * re-render interactively with no second fetch; the rendered image is still a
 * reference. `specFormat` is required whenever `spec` is set.
 */
export interface PlotArtifact {
  __type: 'dk.cachet.carp.dsp.media.plot';
  reference: ArtifactReference;
  spec?: unknown | null;
  specFormat?: string | null;
  title?: string | null;
}

export interface DocumentArtifact {
  __type: 'dk.cachet.carp.dsp.media.document';
  reference: ArtifactReference;
  title?: string | null;
  pageCount?: number | null;
}

// ---- ControlSignal ----------------------------------------------------------

/** The auditable record of an activation, not the activation itself. */
export interface TriggerActivation {
  __type: 'dk.cachet.carp.dsp.control.trigger';
  triggerId: number;
  studyDeploymentId: string;
  /** ISO-8601 instant. */
  activatedAt: string;
}

// ---- Fallback ---------------------------------------------------------------

/** Carries either an inline payload or a reference - at least one is set. */
export interface GenericArtifact {
  __type: 'dk.cachet.carp.dsp.generic';
  payload?: unknown | null;
  reference?: ArtifactReference | null;
  schemaRef?: string | null;
}

export type AnalyticResult =
  | ComputedFeature
  | SummaryStatistic
  | Classification
  | FeatureTable;

export type MediaArtifact = ImageArtifact | PlotArtifact | DocumentArtifact;

export type ControlSignal = TriggerActivation;

export type WorkflowArtifact =
  | AnalyticResult
  | MediaArtifact
  | ControlSignal
  | GenericArtifact;

// ---- Branch helpers ---------------------------------------------------------

export type ArtifactBranch = 'analytic' | 'media' | 'control' | 'generic';

/** Groups the results view. Prefix-filtering is the documented contract. */
export const branchOf = (artifact: WorkflowArtifact): ArtifactBranch => {
  if (artifact.__type.startsWith(`${ANALYTIC_NAMESPACE}.`)) return 'analytic';
  if (artifact.__type.startsWith(`${MEDIA_NAMESPACE}.`)) return 'media';
  if (artifact.__type.startsWith(`${CONTROL_NAMESPACE}.`)) return 'control';
  return 'generic';
};

/** True for artefacts the results view can render inline (W5-v0 DoD). */
export const isInlineRenderable = (
  artifact: WorkflowArtifact,
): artifact is ImageArtifact | PlotArtifact =>
  artifact.__type === 'dk.cachet.carp.dsp.media.image' ||
  artifact.__type === 'dk.cachet.carp.dsp.media.plot';
