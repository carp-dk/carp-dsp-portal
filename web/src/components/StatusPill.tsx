import { Chip } from '@mui/material';
import type { ExecutionStatus } from '../api/types';

/**
 * Maps ExecutionStatus onto carp-portal's status palette.
 *
 * RUNNING uses `purple` rather than `blue`: the palette defines `blue` at
 * runtime but omits it from the Palette type augmentation, so it does not
 * typecheck. Worth fixing upstream.
 */
const COLOURS: Record<ExecutionStatus, 'green' | 'purple' | 'grey' | 'red' | 'yellow'> = {
  SUCCEEDED: 'green',
  RUNNING: 'purple',
  PENDING: 'grey',
  FAILED: 'red',
  SKIPPED: 'yellow',
  CANCELLED: 'grey',
};

const StatusPill = ({
  status,
  size = 'small',
}: {
  status: ExecutionStatus;
  size?: 'small' | 'medium';
}) => (
  <Chip
    label={status.toLowerCase()}
    size={size}
    sx={{
      color: '#fff',
      fontWeight: 700,
      textTransform: 'capitalize',
      backgroundColor: (t) => t.palette.status[COLOURS[status]],
    }}
  />
);

export default StatusPill;
