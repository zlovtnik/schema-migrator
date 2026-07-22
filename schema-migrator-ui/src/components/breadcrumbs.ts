interface RouteLabelContext {
  params: Record<string, string | undefined>;
  pathname: string;
}

type RouteLabel = string | ((match: RouteLabelContext) => string | undefined);

export interface RouteHandle {
  breadcrumb?: RouteLabel;
  breadcrumbTo?: string;
  hideBreadcrumb?: boolean;
  parents?: BreadcrumbDefinition[];
  targetAware?: boolean;
  title?: RouteLabel;
}

interface BreadcrumbDefinition {
  breadcrumb: string;
  breadcrumbTo: string;
  targetAware?: boolean;
}

export const resolveRouteLabel = (label: RouteLabel | undefined, match: RouteLabelContext): string | undefined =>
  typeof label === "function" ? label(match) : label;

export const activeRouteTitle = (
  matches: Array<{ handle: unknown; params: Record<string, string | undefined>; pathname: string }>
): string | undefined =>
  matches
    .slice()
    .reverse()
    .map((match) => resolveRouteLabel((match.handle as RouteHandle | undefined)?.title, match))
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
