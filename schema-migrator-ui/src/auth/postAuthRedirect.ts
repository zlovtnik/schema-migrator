const POST_AUTH_REDIRECT_KEY = "schemaMigrator.postAuthRedirect";

type RedirectLocation = {
  pathname?: string;
  search?: string;
  hash?: string;
};

type RedirectState = {
  from?: RedirectLocation;
};

const redirectPathFromState = (state: unknown): string | undefined => {
  const from = (state as RedirectState | undefined)?.from;
  if (!from?.pathname || !from.pathname.startsWith("/") || from.pathname === "/login" || from.pathname === "/callback") {
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
  const destination = redirectPathFromState(state) ?? window.sessionStorage.getItem(POST_AUTH_REDIRECT_KEY) ?? "/overview";
  window.sessionStorage.removeItem(POST_AUTH_REDIRECT_KEY);
  return destination;
};
