import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { NavLink, Outlet } from "react-router-dom";
import { ClockCounterClockwiseIcon } from "@phosphor-icons/react/dist/csr/ClockCounterClockwise";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { GearIcon } from "@phosphor-icons/react/dist/csr/Gear";
import { ListIcon } from "@phosphor-icons/react/dist/csr/List";
import { ListBulletsIcon } from "@phosphor-icons/react/dist/csr/ListBullets";
import { ShieldCheckIcon } from "@phosphor-icons/react/dist/csr/ShieldCheck";
import { SidebarSimpleIcon } from "@phosphor-icons/react/dist/csr/SidebarSimple";
import { UploadSimpleIcon } from "@phosphor-icons/react/dist/csr/UploadSimple";
import { ErrorGateBanner } from "../components/ErrorGateBanner";
import { TargetSelector } from "../components/TargetSelector";
import { Icon } from "../components/ui/Icon";
import { useErrorGate } from "../hooks/useErrorGate";

const SIDEBAR_KEY = "schemaMigrator.sidebarCollapsed";

const navItems = [
  { to: "/overview", label: "Overview", icon: ShieldCheckIcon },
  { to: "/schema", label: "Schema", icon: DatabaseIcon },
  { to: "/targets", label: "Targets", icon: DatabaseIcon },
  { to: "/migrations", label: "Migrations", icon: UploadSimpleIcon },
  { to: "/runs", label: "Runs", icon: ClockCounterClockwiseIcon },
  { to: "/drift", label: "Drift", icon: ListBulletsIcon },
  { to: "/validation/latest", label: "Validation", icon: ShieldCheckIcon },
  { to: "/settings", label: "Settings", icon: GearIcon }
];

export const AppShell = () => {
  const [collapsed, setCollapsed] = useState(() => window.localStorage.getItem(SIDEBAR_KEY) === "true");
  const { failedRun } = useErrorGate();
  const queryClient = useQueryClient();

  useEffect(() => {
    window.localStorage.setItem(SIDEBAR_KEY, String(collapsed));
  }, [collapsed]);

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      const modifier = event.metaKey || event.ctrlKey;
      if (!modifier) {
        return;
      }

      if (event.key === "\\") {
        event.preventDefault();
        setCollapsed((value) => !value);
      }

      if (event.key.toLowerCase() === "r") {
        event.preventDefault();
        void queryClient.invalidateQueries();
      }
    };

    window.addEventListener("keydown", handleShortcut);
    return () => window.removeEventListener("keydown", handleShortcut);
  }, [queryClient]);

  return (
    <>
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>
      <div className={collapsed ? "app-shell app-shell--collapsed" : "app-shell"}>
        <aside className="sidebar" aria-label="Primary navigation">
        <div className="sidebar__brand">
          <div className="brand-mark">SM</div>
          <div className="brand-copy">
            <strong>Schema Migrator</strong>
            <span>Patch operations</span>
          </div>
        </div>
        <nav className="sidebar__nav">
          {navItems.map((item) => {
            return (
              <NavLink className="nav-link" to={item.to} key={item.to}>
                <Icon source={item.icon} size={20} />
                <span>{item.label}</span>
              </NavLink>
            );
          })}
        </nav>
        <div className="sidebar__selector">
          <TargetSelector compact />
        </div>
        <button className="sidebar__collapse" type="button" onClick={() => setCollapsed((value) => !value)}>
          <Icon source={collapsed ? ListIcon : SidebarSimpleIcon} size={20} />
          <span>{collapsed ? "Expand" : "Collapse"}</span>
        </button>
        </aside>
        <div className="app-main">
          <ErrorGateBanner failedRun={failedRun} />
          <main className="main-scroll" id="main-content" tabIndex={-1}>
            <Outlet />
          </main>
        </div>
      </div>
    </>
  );
};
