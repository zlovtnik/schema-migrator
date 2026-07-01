import { lazy, Suspense, type ComponentType } from "react";
import { Navigate, createBrowserRouter, useParams } from "react-router-dom";
import { AppShell } from "./layouts/AppShell";

const OverviewPage = lazy(() => import("./pages/Overview/OverviewPage").then((module) => ({ default: module.OverviewPage })));
const SchemaPage = lazy(() => import("./pages/Schema/SchemaPage").then((module) => ({ default: module.SchemaPage })));
const TargetListPage = lazy(() => import("./pages/Targets/TargetListPage").then((module) => ({ default: module.TargetListPage })));
const TargetFormPage = lazy(() => import("./pages/Targets/TargetFormPage").then((module) => ({ default: module.TargetFormPage })));
const PatchListPage = lazy(() => import("./pages/Patches/PatchListPage").then((module) => ({ default: module.PatchListPage })));
const PatchDetailPage = lazy(() => import("./pages/Patches/PatchDetailPage").then((module) => ({ default: module.PatchDetailPage })));
const RunListPage = lazy(() => import("./pages/Runs/RunListPage").then((module) => ({ default: module.RunListPage })));
const RunDetailPage = lazy(() => import("./pages/Runs/RunDetailPage").then((module) => ({ default: module.RunDetailPage })));
const DriftPage = lazy(() => import("./pages/Drift/DriftPage").then((module) => ({ default: module.DriftPage })));
const ValidationReportPage = lazy(() =>
  import("./pages/Validation/ValidationReportPage").then((module) => ({ default: module.ValidationReportPage }))
);
const SettingsPage = lazy(() => import("./pages/Settings/SettingsPage").then((module) => ({ default: module.SettingsPage })));

const PatchDetailRedirect = () => {
  const { id } = useParams();
  return <Navigate to={id ? `/migrations/${id}` : "/migrations"} replace />;
};

const routeElement = (Component: ComponentType) => (
  <Suspense fallback={<div className="page empty-state" role="status">Loading page...</div>}>
    <Component />
  </Suspense>
);

export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShell />,
    handle: { breadcrumb: "Bedrock", breadcrumbTo: "/overview" },
    children: [
      { index: true, element: <Navigate to="/overview" replace /> },
      { path: "overview", element: routeElement(OverviewPage), handle: { breadcrumb: "Overview", title: "Overview" } },
      {
        path: "schema",
        element: routeElement(SchemaPage),
        handle: { breadcrumb: "Schema", targetAware: true, title: "Schema" }
      },
      { path: "targets", element: routeElement(TargetListPage), handle: { breadcrumb: "Targets", title: "Targets" } },
      {
        path: "targets/:id",
        element: routeElement(TargetFormPage),
        handle: {
          breadcrumb: "Target detail",
          parents: [{ breadcrumb: "Targets", breadcrumbTo: "/targets" }],
          title: "Target"
        }
      },
      {
        path: "migrations",
        element: routeElement(PatchListPage),
        handle: { breadcrumb: "Migrations", targetAware: true, title: "Migrations" }
      },
      {
        path: "migrations/:id",
        element: routeElement(PatchDetailPage),
        handle: {
          breadcrumb: "Migration detail",
          parents: [{ breadcrumb: "Migrations", breadcrumbTo: "/migrations", targetAware: true }],
          title: "Migration"
        }
      },
      { path: "patches", element: <Navigate to="/migrations" replace /> },
      { path: "patches/:id", element: <PatchDetailRedirect /> },
      {
        path: "runs",
        element: routeElement(RunListPage),
        handle: { breadcrumb: "Runs", targetAware: true, title: "Runs" }
      },
      {
        path: "runs/:id",
        element: routeElement(RunDetailPage),
        handle: {
          breadcrumb: "Run detail",
          parents: [{ breadcrumb: "Runs", breadcrumbTo: "/runs", targetAware: true }],
          title: "Run"
        }
      },
      {
        path: "drift",
        element: routeElement(DriftPage),
        handle: { breadcrumb: "Drift", targetAware: true, title: "Drift" }
      },
      {
        path: "validation/:runId",
        element: routeElement(ValidationReportPage),
        handle: {
          breadcrumb: "Validation",
          parents: [{ breadcrumb: "Runs", breadcrumbTo: "/runs", targetAware: true }],
          title: "Validation"
        }
      },
      { path: "settings", element: routeElement(SettingsPage), handle: { breadcrumb: "Settings", title: "Settings" } },
      {
        path: "settings/targets",
        element: routeElement(TargetListPage),
        handle: {
          breadcrumb: "Targets",
          parents: [{ breadcrumb: "Settings", breadcrumbTo: "/settings" }],
          title: "Targets"
        }
      },
      {
        path: "settings/targets/:id",
        element: routeElement(TargetFormPage),
        handle: {
          breadcrumb: "Target detail",
          parents: [
            { breadcrumb: "Settings", breadcrumbTo: "/settings" },
            { breadcrumb: "Targets", breadcrumbTo: "/settings/targets" }
          ],
          title: "Target"
        }
      }
    ]
  }
]);
