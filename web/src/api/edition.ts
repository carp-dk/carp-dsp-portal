import { useQuery } from '@tanstack/react-query';

/** Nav tabs, by route name, as editions.yaml names them. */
export type Tab =
  | 'ubilife'
  | 'workflows'
  | 'runs'
  | 'results'
  | 'data'
  | 'compose'
  | 'library'
  | 'environments'
  | 'upload'
  | 'settings';

/** The edition the server was built as. See editions.yaml. */
export interface Edition {
  name: string;
  content: boolean;
  tabs: Tab[];
}

export const getEdition = async (): Promise<Edition> => {
  const response = await fetch('/api/edition');
  if (!response.ok) throw new Error(`HTTP_${response.status}`);
  return (await response.json()) as Edition;
};

/** The edition is fixed for the life of the server, so it is fetched once. */
export const useEdition = () =>
  useQuery({ queryKey: ['edition'], queryFn: getEdition, staleTime: Infinity });
