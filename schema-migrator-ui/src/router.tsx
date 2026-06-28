import { Navigate, createBrowserRouter, useParams } from "react-router-dom";
import { AppShell } from "./layouts/AppShell";
import { DriftPage } from "./pages/Drift/DriftPage";
import { OverviewPage } from "./pages/Overview/OverviewPage";
import { TargetListPage } from "./pages/Targets/TargetListPage";
import { TargetFormPage } from "./pages/Targets/TargetFormPage";
import { PatchListPage } from "./pages/Patches/PatchListPage";
import { PatchDetailPage } from "./pages/Patches/PatchDetailPage";
import { RunListPage } from "./pages/Runs/RunListPage";
import { RunDetailPage } from "./pages/Runs/RunDetailPage";
import { SchemaPage } from "./pages/Schema/SchemaPage";
import { ValidationReportPage } from "./pages/Validation/ValidationReportPage";
import { SettingsPage } from "./pages/Settings/SettingsPage";

const PatchDetailRedirect = () => {
  const { id } = useParams();
  return <Navigate to={id ? `/migrations/${id}` : "/migrations"} replace />;
};

export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/overview" replace /> },
      { path: "overview", element: <OverviewPage /> },
      { path: "schema", element: <SchemaPage /> },
      { path: "targets", element: <TargetListPage /> },
      { path: "targets/:id", element: <TargetFormPage /> },
      { path: "migrations", element: <PatchListPage /> },
      { path: "migrations/:id", element: <PatchDetailPage /> },
      { path: "patches", element: <Navigate to="/migrations" replace /> },
      { path: "patches/:id", element: <PatchDetailRedirect /> },
      { path: "runs", element: <RunListPage /> },
      { path: "runs/:id", element: <RunDetailPage /> },
      { path: "drift", element: <DriftPage /> },
      { path: "validation/:runId", element: <ValidationReportPage /> },
      { path: "settings", element: <SettingsPage /> }
    ]
  }
]);
