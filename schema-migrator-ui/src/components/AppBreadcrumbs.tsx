import { Link, useMatches } from "react-router-dom";
import { CaretRightIcon } from "@phosphor-icons/react/dist/csr/CaretRight";
import { useSelectedTargetId } from "../hooks/useSelectedTarget";
import { breadcrumbTarget, resolveRouteLabel, type RouteHandle } from "./breadcrumbs";
import { Icon } from "./ui/Icon";

export const AppBreadcrumbs = () => {
  const selectedTargetId = useSelectedTargetId();
  const matches = useMatches();
  const crumbs = matches
    .flatMap((match) => {
      const handle = match.handle as RouteHandle | undefined;
      if (!handle) {
        return [];
      }

      const label = resolveRouteLabel(handle.breadcrumb, match);
      if (!label) {
        return [];
      }

      return [
        ...(handle.parents ?? []).map((parent) => ({
          label: parent.breadcrumb,
          targetAware: parent.targetAware === true,
          to: parent.breadcrumbTo
        })),
        {
          label,
          targetAware: handle.targetAware === true,
          to: handle.breadcrumbTo ?? match.pathname
        }
      ];
    })
    .filter((crumb): crumb is { label: string; targetAware: boolean; to: string } => Boolean(crumb));

  if (crumbs.length === 0) {
    return null;
  }

  return (
    <nav className="app-breadcrumbs" aria-label="Breadcrumb">
      <ol>
        {crumbs.map((crumb, index) => {
          const isCurrent = index === crumbs.length - 1;
          return (
            <li key={`${crumb.to}-${index}`}>
              {index > 0 ? <Icon source={CaretRightIcon} size={16} /> : null}
              {isCurrent ? (
                <span aria-current="page">{crumb.label}</span>
              ) : (
                <Link to={breadcrumbTarget(crumb.to, crumb.targetAware ? selectedTargetId : null)}>{crumb.label}</Link>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
};
