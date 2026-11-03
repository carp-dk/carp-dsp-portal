import { CircularProgress } from '@mui/material';
import { Suspense, lazy } from 'react';
import { Navigate, Outlet, Route, Routes } from 'react-router-dom';
import PortalLayout from '@Components/PortalLayout';
import { useEdition, type Tab } from './api/edition';
import Data from './pages/Data';
import Environments from './pages/Environments';
import Library from './pages/Library';
import LibraryStep from './pages/LibraryStep';
import Results from './pages/Results';
import ResultSets from './pages/ResultSets';
import RunView from './pages/RunView';
import Runs from './pages/Runs';
import Settings from './pages/Settings';
import UbiLifeDashboard from './pages/UbiLifeDashboard';
import Upload from './pages/Upload';
import WorkflowDetail from './pages/WorkflowDetail';
import Workflows from './pages/Workflows';

// The composer pulls in CodeMirror, which is most of the bundle. Loading it on
// demand keeps the pages people open first small.
const Compose = lazy(() => import('./pages/Compose'));

/**
 * Renders a tab's routes only when the edition shows that tab; otherwise
 * redirects to /workflows, which every edition has. If the edition cannot be
 * read, every tab is allowed, as in the nav.
 */
const RequireTab = ({ tab }: { tab: Tab }) => {
  const edition = useEdition();

  if (edition.isPending) return <CircularProgress sx={{ m: 4 }} />;
  if (edition.data && !edition.data.tabs.includes(tab)) return <Navigate to="/workflows" replace />;
  return <Outlet />;
};

/** Each route maps to a bullet in W5-v0's Definition of Done. */
const App = () => (
  <Suspense fallback={<CircularProgress sx={{ m: 4 }} />}>
    <Routes>
      <Route element={<PortalLayout />}>
        <Route path="/" element={<Navigate to="/workflows" replace />} />
        <Route path="/workflows" element={<Workflows />} />
        <Route path="/workflows/:workflowId" element={<WorkflowDetail />} />
        <Route element={<RequireTab tab="data" />}>
          <Route path="/data" element={<Data />} />
        </Route>
        <Route element={<RequireTab tab="compose" />}>
          <Route path="/compose" element={<Compose />} />
        </Route>
        <Route element={<RequireTab tab="library" />}>
          <Route path="/library" element={<Library />} />
          <Route path="/library/:stepId" element={<LibraryStep />} />
        </Route>
        <Route element={<RequireTab tab="environments" />}>
          <Route path="/environments" element={<Environments />} />
        </Route>
        <Route element={<RequireTab tab="upload" />}>
          <Route path="/upload" element={<Upload />} />
        </Route>
        <Route element={<RequireTab tab="settings" />}>
          <Route path="/settings" element={<Settings />} />
        </Route>
        <Route element={<RequireTab tab="ubilife" />}>
          <Route path="/ubilife" element={<UbiLifeDashboard />} />
        </Route>
        <Route element={<RequireTab tab="runs" />}>
          <Route path="/runs" element={<Runs />} />
          <Route path="/runs/:runId" element={<RunView />} />
          <Route path="/runs/:runId/results" element={<Results />} />
        </Route>
        <Route element={<RequireTab tab="results" />}>
          <Route path="/results" element={<ResultSets />} />
        </Route>
        <Route path="*" element={<Navigate to="/workflows" replace />} />
      </Route>
    </Routes>
  </Suspense>
);

export default App;
