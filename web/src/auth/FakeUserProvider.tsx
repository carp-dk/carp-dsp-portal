import { createContext, useContext, type ReactNode } from 'react';

/**
 * Stands in for carp-portal's OIDC session (@carp-dk/authentication-react).
 *
 * The mock has no login. Components read the same shape they would get from
 * the real provider, so porting them into carp-portal means swapping this
 * provider out and leaving the consumers alone.
 */
export type PortalUser = {
  id: string;
  name: string;
  email: string;
  roles: string[];
};

const FAKE_USER: PortalUser = {
  id: 'fake-user-0001',
  name: 'Demo Researcher',
  email: 'demo@carp.dk',
  roles: ['researcher'],
};

const UserContext = createContext<PortalUser>(FAKE_USER);

export const FakeUserProvider = ({ children }: { children: ReactNode }) => (
  <UserContext.Provider value={FAKE_USER}>{children}</UserContext.Provider>
);

export const useCurrentUser = () => useContext(UserContext);
