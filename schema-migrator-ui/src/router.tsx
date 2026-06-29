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
    children: [
      { index: true, element: <Navigate to="/overview" replace /> },
      { path: "overview", element: routeElement(OverviewPage) },
      { path: "schema", element: routeElement(SchemaPage) },
      { path: "targets", element: routeElement(TargetListPage) },
      { path: "targets/:id", element: routeElement(TargetFormPage) },
      { path: "migrations", element: routeElement(PatchListPage) },
      { path: "migrations/:id", element: routeElement(PatchDetailPage) },
      { path: "patches", element: <Navigate to="/migrations" replace /> },
      { path: "patches/:id", element: <PatchDetailRedirect /> },
      { path: "runs", element: routeElement(RunListPage) },
      { path: "runs/:id", element: routeElement(RunDetailPage) },
      { path: "drift", element: routeElement(DriftPage) },
      { path: "validation/:runId", element: routeElement(ValidationReportPage) },
      { path: "settings", element: routeElement(SettingsPage) }
    ]
  }
]);
