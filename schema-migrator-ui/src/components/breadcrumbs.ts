export interface RouteHandle {
  breadcrumb?: string;
  breadcrumbTo?: string;
  parents?: BreadcrumbDefinition[];
  targetAware?: boolean;
  title?: string;
}

interface BreadcrumbDefinition {
  breadcrumb: string;
  breadcrumbTo: string;
  targetAware?: boolean;
}

export const activeRouteTitle = (matches: Array<{ handle: unknown }>): string | undefined =>
  matches
    .slice()
    .reverse()
    .map((match) => (match.handle as RouteHandle | undefined)?.title)
    .find((title): title is string => Boolean(title));

export const breadcrumbTarget = (pathname: string, targetId?: string | null) => {
  if (!targetId) {
    return pathname;
  }

  const [path, search = ""] = pathname.split("?");
  const searchParams = new URLSearchParams(search);
  searchParams.set("target", targetId);
  return `${path}?${searchParams.toString()}`;
};
