import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { FakeUserProvider } from './auth/FakeUserProvider';
import { themeInstance } from './utils/theme';

const queryClient = new QueryClient();

const container = document.getElementById('root');
if (!container) throw new Error('#root not found');

createRoot(container).render(
  <StrictMode>
    <ThemeProvider theme={themeInstance}>
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <FakeUserProvider>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </FakeUserProvider>
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);
