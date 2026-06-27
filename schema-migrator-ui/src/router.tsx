import { Navigate, createBrowserRouter } from "react-router-dom";
import { AppShell } from "./layouts/AppShell";
import { TargetListPage } from "./pages/Targets/TargetListPage";
import { TargetFormPage } from "./pages/Targets/TargetFormPage";
import { PatchListPage } from "./pages/Patches/PatchListPage";
import { PatchDetailPage } from "./pages/Patches/PatchDetailPage";
import { RunListPage } from "./pages/Runs/RunListPage";
import { RunDetailPage } from "./pages/Runs/RunDetailPage";
import { ValidationReportPage } from "./pages/Validation/ValidationReportPage";
import { SettingsPage } from "./pages/Settings/SettingsPage";

export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/targets" replace /> },
      { path: "targets", element: <TargetListPage /> },
      { path: "targets/:id", element: <TargetFormPage /> },
      { path: "patches", element: <PatchListPage /> },
      { path: "patches/:id", element: <PatchDetailPage /> },
      { path: "runs", element: <RunListPage /> },
      { path: "runs/:id", element: <RunDetailPage /> },
      { path: "validation/:runId", element: <ValidationReportPage /> },
      { path: "settings", element: <SettingsPage /> }
    ]
  }
]);
