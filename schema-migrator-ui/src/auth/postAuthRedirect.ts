const POST_AUTH_REDIRECT_KEY = "schemaMigrator.postAuthRedirect";

type RedirectLocation = {
  pathname?: string;
  search?: string;
  hash?: string;
};

type RedirectState = {
  from?: RedirectLocation;
};

const isAllowedRedirectPath = (path: string): boolean => {
  const pathname = path.split(/[?#]/, 1)[0];
  return path.startsWith("/") && !path.startsWith("//") && pathname !== "/login" && pathname !== "/callback";
};

export const redirectPathFromState = (state: unknown): string | undefined => {
  const from = (state as RedirectState | undefined)?.from;
  if (!from?.pathname || !isAllowedRedirectPath(from.pathname)) {
    return undefined;
  }
  return `${from.pathname}${from.search ?? ""}${from.hash ?? ""}`;
};

export const rememberPostAuthRedirect = (state: unknown): void => {
  const destination = redirectPathFromState(state);
  if (destination) {
    window.sessionStorage.setItem(POST_AUTH_REDIRECT_KEY, destination);
  }
};

export const takePostAuthRedirect = (state: unknown): string => {
  const stored = window.sessionStorage.getItem(POST_AUTH_REDIRECT_KEY);
  const storedDestination = stored && isAllowedRedirectPath(stored) ? stored : undefined;
  const destination = redirectPathFromState(state) ?? storedDestination ?? "/overview";
  window.sessionStorage.removeItem(POST_AUTH_REDIRECT_KEY);
  return destination;
};
