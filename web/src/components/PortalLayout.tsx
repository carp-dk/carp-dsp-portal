import { AppBar, Avatar, Box, Button, Container, Toolbar, Typography } from '@mui/material';
import { NavLink, Outlet } from 'react-router-dom';
import { useEdition, type Tab } from '../api/edition';
import { useCurrentUser } from '../auth/FakeUserProvider';
import RunModeSwitch from './RunModeSwitch';

// Every tab the app has, in display order. The edition picks which show.
const NAV: { tab: Tab; to: string; label: string }[] = [
  { tab: 'ubilife', to: '/ubilife', label: 'UbiLife 2026' },
  { tab: 'workflows', to: '/workflows', label: 'Workflows' },
  { tab: 'runs', to: '/runs', label: 'Runs' },
  { tab: 'results', to: '/results', label: 'Results' },
  { tab: 'data', to: '/data', label: 'Data' },
  { tab: 'compose', to: '/compose', label: 'Compose' },
  { tab: 'library', to: '/library', label: 'Step library' },
  { tab: 'environments', to: '/environments', label: 'Environments' },
  { tab: 'upload', to: '/upload', label: 'Upload' },
  { tab: 'settings', to: '/settings', label: 'Settings' },
];

/**
 * Stand-in for carp-portal's PrivatePageLayout.
 *
 * The real one pulls in Logo and BannerAccountButton, which drag in the OIDC
 * session and the CARP client. This keeps the same shape - banner plus a
 * full-width container - without those dependencies, so the mock stays
 * installable. Swap it for the real layout when these pages move into
 * carp-portal.
 */
const PortalLayout = () => {
  const user = useCurrentUser();
  const edition = useEdition();

  // Nothing while the edition loads, so hidden tabs never flash. If it cannot
  // be read, show every tab rather than none.
  const nav = edition.isPending
    ? []
    : NAV.filter((item) => !edition.data || edition.data.tabs.includes(item.tab));

  return (
    <>
      <AppBar position="static" elevation={0} color="default">
        <Toolbar sx={{ justifyContent: 'space-between' }}>
          <Typography
            variant="h4"
            sx={{ color: (t) => t.palette.company.logotype }}
          >
            CARP{' '}
            <Box
              component="span"
              sx={{ color: (t) => t.palette.company.isotype }}
            >
              DSP
            </Box>
          </Typography>

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flex: 1, ml: 4 }}>
            {nav.map((item) => (
              <Button
                key={item.to}
                component={NavLink}
                to={item.to}
                sx={{
                  color: 'text.primary',
                  '&.active': {
                    color: 'primary.main',
                    backgroundColor: (t) => t.palette.drawer.active,
                  },
                }}
              >
                {item.label}
              </Button>
            ))}
          </Box>

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <RunModeSwitch />
            <Typography variant="h5_web">{user.name}</Typography>
            <Avatar sx={{ width: 32, height: 32 }}>
              {user.name.charAt(0)}
            </Avatar>
          </Box>
        </Toolbar>
      </AppBar>

      <Container maxWidth={false} sx={{ py: 4 }}>
        <Outlet />
      </Container>
    </>
  );
};

export default PortalLayout;
