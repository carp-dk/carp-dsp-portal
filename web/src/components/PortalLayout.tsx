import { AppBar, Avatar, Box, Button, Container, Toolbar, Typography } from '@mui/material';
import { NavLink, Outlet } from 'react-router-dom';
import { useCurrentUser } from '../auth/FakeUserProvider';
import RunModeSwitch from './RunModeSwitch';

const NAV = [
  { to: '/workflows', label: 'Workflows' },
  { to: '/runs', label: 'Runs' },
  { to: '/data', label: 'Data' },
  { to: '/compose', label: 'Compose' },
  { to: '/library', label: 'Step library' },
  { to: '/environments', label: 'Environments' },
  { to: '/upload', label: 'Upload' },
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
            {NAV.map((item) => (
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
