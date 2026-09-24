import { CircularProgress } from '@mui/material';
import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import PortalLayout from '@Components/PortalLayout';
import Data from './pages/Data';
import Environments from './pages/Environments';
import Library from './pages/Library';
import LibraryStep from './pages/LibraryStep';
import Results from './pages/Results';
import RunView from './pages/RunView';
import Runs from './pages/Runs';
import Upload from './pages/Upload';
import WorkflowDetail from './pages/WorkflowDetail';
import Workflows from './pages/Workflows';

// The composer pulls in CodeMirror, which is most of the bundle. Loading it on
// demand keeps the pages people open first small.
const Compose = lazy(() => import('./pages/Compose'));

/** Each route maps to a bullet in W5-v0's Definition of Done. */
const App = () => (
  <Suspense fallback={<CircularProgress sx={{ m: 4 }} />}>
    <Routes>
      <Route element={<PortalLayout />}>
        <Route path="/" element={<Navigate to="/workflows" replace />} />
        <Route path="/data" element={<Data />} />
        <Route path="/workflows" element={<Workflows />} />
        <Route path="/workflows/:workflowId" element={<WorkflowDetail />} />
        <Route path="/compose" element={<Compose />} />
        <Route path="/library" element={<Library />} />
        <Route path="/environments" element={<Environments />} />
        <Route path="/library/:stepId" element={<LibraryStep />} />
        <Route path="/upload" element={<Upload />} />
        <Route path="/runs" element={<Runs />} />
        <Route path="/runs/:runId" element={<RunView />} />
        <Route path="/runs/:runId/results" element={<Results />} />
        <Route path="*" element={<Navigate to="/workflows" replace />} />
      </Route>
    </Routes>
  </Suspense>
);

export default App;
