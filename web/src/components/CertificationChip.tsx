import { Chip, Tooltip } from '@mui/material';
import type { Certification } from '../api/types';

/**
 * The conformance gate, as a badge.
 *
 * `gated` means the step has not been reviewed yet. A reviewed step carries a
 * `reviewedHash`; if that no longer matches `contentHash` the review has been
 * outrun by an edit, which is worth showing rather than hiding.
 */
const CertificationChip = ({
  certification,
}: {
  certification?: Certification | null;
}) => {
  if (!certification?.level) return null;

  const { level, contentHash, reviewedHash, reviewer, reviewedOn } = certification;
  const stale = Boolean(reviewedHash) && reviewedHash !== contentHash;

  const label = stale ? 'review stale' : level;
  const colour: 'green' | 'yellow' | 'grey' =
    stale ? 'yellow' : level === 'certified' ? 'green' : 'grey';

  const tip = stale
    ? 'Reviewed hash does not match current content - the step changed after review.'
    : reviewer
      ? `Reviewed by ${reviewer}${reviewedOn ? ` on ${reviewedOn}` : ''}`
      : 'Not reviewed yet';

  return (
    <Tooltip title={tip}>
      <Chip
        label={label}
        size="small"
        sx={{
          flexShrink: 0,
          color: '#fff',
          fontWeight: 700,
          backgroundColor: (t) => t.palette.status[colour],
        }}
      />
    </Tooltip>
  );
};

export default CertificationChip;
