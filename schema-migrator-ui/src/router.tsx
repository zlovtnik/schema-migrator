import { lazy, Suspense, type ComponentType } from "react";
import { Navigate, createBrowserRouter, useParams } from "react-router-dom";
import { RequireAuth } from "./auth/RequireAuth";
import { AppShell } from "./layouts/AppShell";

const OverviewPage = lazy(() =>
  import("./pages/Overview/OverviewPage").then((module) => ({ default: module.OverviewPage }))
);
const SchemaPage = lazy(() => import("./pages/Schema/SchemaPage").then((module) => ({ default: module.SchemaPage })));
const SchemaUpgradeWizard = lazy(() =>
  import("./pages/SchemaUpgrade/SchemaUpgradeWizard").then((module) => ({ default: module.SchemaUpgradeWizard }))
);
const TargetListPage = lazy(() =>
  import("./pages/Targets/TargetListPage").then((module) => ({ default: module.TargetListPage }))
);
const TargetFormPage = lazy(() =>
  import("./pages/Targets/TargetFormPage").then((module) => ({ default: module.TargetFormPage }))
);
const TargetDetailPage = lazy(() =>
  import("./pages/Targets/TargetDetailPage").then((module) => ({ default: module.TargetDetailPage }))
);
const SnapshotListPage = lazy(() =>
  import("./pages/Snapshots/SnapshotListPage").then((module) => ({ default: module.SnapshotListPage }))
);
const SnapshotDetailPage = lazy(() =>
  import("./pages/Snapshots/SnapshotDetailPage").then((module) => ({ default: module.SnapshotDetailPage }))
);
const SnapshotDiffPage = lazy(() =>
  import("./pages/Snapshots/SnapshotDiffPage").then((module) => ({ default: module.SnapshotDiffPage }))
);
const RunListPage = lazy(() => import("./pages/Runs/RunListPage").then((module) => ({ default: module.RunListPage })));
const RunDetailPage = lazy(() =>
  import("./pages/Runs/RunDetailPage").then((module) => ({ default: module.RunDetailPage }))
);
const DriftPage = lazy(() => import("./pages/Drift/DriftPage").then((module) => ({ default: module.DriftPage })));
const PatchesPage = lazy(() =>
  import("./pages/Patches/PatchesPage").then((module) => ({ default: module.PatchesPage }))
);
const ValidationReportPage = lazy(() =>
  import("./pages/Validation/ValidationReportPage").then((module) => ({ default: module.ValidationReportPage }))
);
const SettingsPage = lazy(() =>
  import("./pages/Settings/SettingsPage").then((module) => ({ default: module.SettingsPage }))
);
const SqlFilesPage = lazy(() =>
  import("./pages/SqlFiles/SqlFilesPage").then((module) => ({ default: module.default }))
);
const AuditLogPage = lazy(() =>
  import("./pages/Audit/AuditLogPage").then((module) => ({ default: module.AuditLogPage }))
);
const LoginPage = lazy(() => import("./pages/Login/LoginPage").then((module) => ({ default: module.LoginPage })));
const CallbackPage = lazy(() =>
  import("./pages/Login/CallbackPage").then((module) => ({ default: module.CallbackPage }))
);

const snapshotRouteLabel = ({ params }: { params: Record<string, string | undefined> }) =>
  params.id ? `Snapshot · ${params.id.slice(0, 8)}` : "Snapshot";

const SettingsTargetRedirect = () => {
  const { id } = useParams();
  return <Navigate to={id ? `/targets/${id}` : "/targets"} replace />;
};

const routeElement = (Component: ComponentType) => (
  <Suspense
    fallback={
      <div className="page empty-state" role="status">
        Loading page...
      </div>
    }
  >
    <Component />
  </Suspense>
);

export const router = createBrowserRouter([
  { path: "/login", element: routeElement(LoginPage), handle: { title: "Sign in" } },
  { path: "/callback", element: routeElement(CallbackPage), handle: { title: "Completing sign-in" } },
  {
    path: "/",
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        handle: { breadcrumb: "Bedrock", breadcrumbTo: "/overview" },
        children: [
          { index: true, element: <Navigate to="/overview" replace /> },
          {
            path: "overview",
            element: routeElement(OverviewPage),
            handle: { breadcrumb: "Overview", title: "Overview" }
          },
          {
            path: "schema",
            element: routeElement(SchemaPage),
            handle: { breadcrumb: "Schema", targetAware: true, title: "Schema" }
          },
          {
            path: "schema-upgrade",
            element: routeElement(SchemaUpgradeWizard),
            handle: { breadcrumb: "Schema Upgrade", targetAware: true, title: "Schema Upgrade" }
          },
          {
            path: "targets",
            element: routeElement(TargetListPage),
            handle: { breadcrumb: "Targets", title: "Targets" }
          },
          {
            path: "targets/:id/overview",
            element: routeElement(TargetDetailPage),
            handle: {
              breadcrumb: "Target overview",
              parents: [{ breadcrumb: "Targets", breadcrumbTo: "/targets" }],
              title: "Target overview"
            }
          },
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
            path: "snapshots",
            element: routeElement(SnapshotListPage),
            handle: { breadcrumb: "Snapshots", targetAware: true, title: "Snapshots" }
          },
          {
            path: "snapshots/:id",
            element: routeElement(SnapshotDetailPage),
            handle: {
              breadcrumb: snapshotRouteLabel,
              parents: [{ breadcrumb: "Snapshots", breadcrumbTo: "/snapshots", targetAware: true }],
              title: snapshotRouteLabel
            }
          },
          {
            path: "snapshots/:id/diff/:otherId",
            element: routeElement(SnapshotDiffPage),
            handle: {
              breadcrumb: "Snapshot diff",
              parents: [{ breadcrumb: "Snapshots", breadcrumbTo: "/snapshots", targetAware: true }],
              title: "Snapshot diff"
            }
          },
          {
            path: "patches",
            element: routeElement(PatchesPage),
            handle: { breadcrumb: "Patches", targetAware: true, title: "Patches" }
          },
          {
            path: "patches/:id",
            element: routeElement(PatchesPage),
            handle: {
              breadcrumb: "Patch detail",
              parents: [{ breadcrumb: "Patches", breadcrumbTo: "/patches", targetAware: true }],
              title: "Patch"
            }
          },
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
          {
            path: "sql-files",
            element: routeElement(SqlFilesPage),
            handle: { breadcrumb: "SQL Files", title: "SQL Files" }
          },
          {
            path: "audit",
            element: routeElement(AuditLogPage),
            handle: { breadcrumb: "Audit", title: "Audit" }
          },
          {
            path: "settings",
            element: routeElement(SettingsPage),
            handle: { breadcrumb: "Settings", title: "Settings" }
          },
          { path: "settings/targets", element: <Navigate to="/targets" replace /> },
          { path: "settings/targets/:id", element: <SettingsTargetRedirect /> }
        ]
      }
    ]
  }
]);
